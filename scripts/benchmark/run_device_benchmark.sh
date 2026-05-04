#!/usr/bin/env bash
# Automated device benchmark: runs all model × config combinations
# and captures preprocessing, inference, total, and TTFT timing.
#
# Each test force-stops the app first to satisfy Android 16 FGS
# restrictions (fresh foreground activity each time). All measurements
# are cold-start (model loaded fresh each time).
#
# Prerequisites:
#   - Device connected via adb (pass ADB_SERIAL env var if needed)
#   - App built with BenchmarkActivity and PERF timing logs
#   - Test audio file on device
#
# Usage: ./run_device_benchmark.sh [--quick]
#   --quick: run each config once instead of 3 times

set -euo pipefail

ADB="${ADB_PATH:-adb}"
SERIAL="${ADB_SERIAL:-}"
if [[ -n "$SERIAL" ]]; then
    ADB="$ADB -s $SERIAL"
fi

QUICK=false
[[ "${1:-}" == "--quick" ]] && QUICK=true

RESULTS_CSV="/tmp/benchmark_results.csv"
AUDIO_FILE="/data/data/com.antivocale.app/files/test_audio.ogg"
PACKAGE="com.antivocale.app"
ACTIVITY="${PACKAGE}/.receiver.BenchmarkActivity"
TIMEOUT_PER_TEST=300

echo "run_id,backend,vad,progressive,provider,run,preprocess_ms,inference_ms,total_ms,ttft_ms,path" > "$RESULTS_CSV"

# All configs to test
declare -a SHERPA_CONFIGS=(
    "sherpa-onnx:false:false:nnapi"
    "sherpa-onnx:false:false:cpu"
    "sherpa-onnx:false:true:nnapi"
    "sherpa-onnx:false:true:cpu"
    "sherpa-onnx:true:false:nnapi"
    "sherpa-onnx:true:false:cpu"
    "sherpa-onnx:true:true:nnapi"
    "sherpa-onnx:true:true:cpu"
)

declare -a WHISPER_CONFIGS=(
    "whisper:false:false:nnapi"
    "whisper:false:false:cpu"
    "whisper:false:true:nnapi"
    "whisper:false:true:cpu"
    "whisper:true:false:nnapi"
    "whisper:true:false:cpu"
    "whisper:true:true:nnapi"
    "whisper:true:true:cpu"
)

declare -a LLM_CONFIGS=(
    "llm:false:false:cpu"
    "llm:false:true:cpu"
    "llm:true:false:cpu"
    "llm:true:true:cpu"
)

NUM_RUNS=3
if $QUICK; then
    NUM_RUNS=1
fi

# Collect all configs into a single array
ALL_CONFIGS=()
for config in "${SHERPA_CONFIGS[@]}" "${WHISPER_CONFIGS[@]}" "${LLM_CONFIGS[@]}"; do
    ALL_CONFIGS+=("$config")
done

TOTAL_TESTS=${#ALL_CONFIGS[@]}
TOTAL_COUNT=$((TOTAL_TESTS * NUM_RUNS))

CURRENT=0

echo "=== Anti-Vocale Device Benchmark ==="
echo "Configs: $TOTAL_TESTS, Runs per config: $NUM_RUNS, Total runs: $TOTAL_COUNT"
echo ""

# Append a single result row to the CSV
write_result() {
    echo "$RUN_ID,$backend,$vad,$progressive,$provider,${run_num:-1},${preprocess_ms:-},${inference_ms:-},${total_ms:-},${ttft_ms:-},${path:-}" >> "$RESULTS_CSV"
}

run_single() {
    local backend="$1" vad="$2" progressive="$3" provider="$4"
    local preprocess_ms="" inference_ms="" total_ms="" ttft_ms="" path=""

    # Clear logcat
    $ADB logcat -c 2>/dev/null

    # Launch BenchmarkActivity
    $ADB shell "am start -n $ACTIVITY \
        --es backend '$backend' \
        --ez vad $vad \
        --ez progressive $progressive \
        --es provider '$provider' \
        --es file_path '$AUDIO_FILE' \
        --es run_id '$RUN_ID'" 2>/dev/null

    local elapsed=0
    while [[ $elapsed -lt $TIMEOUT_PER_TEST ]]; do
        sleep 5
        elapsed=$((elapsed + 5))

        # Check for errors
        local bench_error=$($ADB logcat -d -s "BenchmarkActivity" -v brief 2>/dev/null | { grep "BENCH_ERROR" || true; } | tail -1)
        local app_error=$($ADB logcat -d -s "AndroidRuntime" -v brief 2>/dev/null | { grep "FATAL\|Exception" || true; } | tail -1)

        if [[ -n "$bench_error" ]] || [[ -n "$app_error" ]]; then
            echo "ERROR"
            echo "  Error: ${bench_error:-}${app_error:-}"
            write_result
            return 1
        fi

        # Capture orchestrator log once per poll
        local orch_log=$($ADB logcat -d -s "TranscriptionOrchestrator" -v brief 2>/dev/null)
        local perf_line=$(echo "$orch_log" | { grep "PERF:" || true; } | tail -1)

        if [[ -n "$perf_line" ]]; then
            if echo "$perf_line" | grep -q "preprocessing"; then
                if [[ -z "$preprocess_ms" ]]; then
                    preprocess_ms=$(echo "$perf_line" | grep -oP 'preprocessing \K\d+' || true)
                    path="batch"
                fi
                local infer_line=$(echo "$orch_log" | { grep "Inference timing:" || true; } | tail -1)
                inference_ms=$(echo "$infer_line" | grep -oP 'timing: \K\d+' || true)
                if [[ -n "$inference_ms" ]]; then
                    total_ms=$((preprocess_ms + inference_ms))
                    echo "OK (${total_ms}ms)"
                    write_result
                    return 0
                fi
            elif echo "$perf_line" | grep -q "pipeline total"; then
                total_ms=$(echo "$perf_line" | grep -oP 'pipeline total \K\d+' || true)
                path="pipeline"
                ttft_ms=$(echo "$orch_log" | { grep "time-to-first-text" || true; } | tail -1 | grep -oP 'time-to-first-text = \K\d+' || true)
                echo "OK (${total_ms:-?}ms)"
                write_result
                return 0
            elif echo "$perf_line" | grep -q "progressive total"; then
                total_ms=$(echo "$perf_line" | grep -oP 'progressive total \K\d+' || true)
                path="progressive"
                echo "OK (${total_ms:-?}ms)"
                write_result
                return 0
            elif echo "$perf_line" | grep -q "parallel total"; then
                total_ms=$(echo "$perf_line" | grep -oP 'parallel total \K\d+' || true)
                path="parallel"
                echo "OK (${total_ms:-?}ms)"
                write_result
                return 0
            fi
        fi

        echo -n "."
    done

    if [[ $elapsed -ge $TIMEOUT_PER_TEST ]]; then
        echo "TIMEOUT"
        write_result
        return 1
    fi
}

# Run each config: force-stop → measure (cold-start)
for config in "${ALL_CONFIGS[@]}"; do
    IFS=':' read -r backend vad progressive provider <<< "$config"

    for run_num in $(seq 1 $NUM_RUNS); do
        CURRENT=$((CURRENT + 1))
        RUN_ID="${backend}_vad${vad}_prog${progressive}_${provider}_r${run_num}"
        echo -n "[$CURRENT/$TOTAL_COUNT] $RUN_ID ... "

        # Force-stop for clean state (satisfies Android 16 FGS)
        $ADB shell "am force-stop $PACKAGE" 2>/dev/null
        sleep 2

        # Measure (cold-start)
        run_single "$backend" "$vad" "$progressive" "$provider" || true
    done
done

echo ""
echo "=== Results written to $RESULTS_CSV ==="
echo ""

echo "Measured results:"
tail -n +2 "$RESULTS_CSV" | column -t -s','
