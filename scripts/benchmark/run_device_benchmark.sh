#!/usr/bin/env bash
# Automated device benchmark: runs all model × config combinations
# and captures preprocessing, inference, total, and TTFT timing.
#
# Key design: groups tests by backend to avoid cold starts.
# Only force-stops when switching models. Within a backend group,
# the model stays loaded — tests only change VAD/progressive/provider.
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

# Backend groups: each group has a model that stays loaded.
# Format: backend configs... (separated by |)
# Within a group: no force-stop, model stays warm.
declare -a SHERPA_CONFIGS=(
    # backend:vad:progressive:provider
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
$QUICK && NUM_RUNS=1

# Count total tests
TOTAL_TESTS=0
for group in SHERPA_CONFIGS WHISPER_CONFIGS LLM_CONFIGS; do
    TOTAL_TESTS=$((TOTAL_TESTS + ${#group} * NUM_RUNS))
done

CURRENT=0

echo "=== Anti-Vocale Device Benchmark ==="
echo "Configs: $(( ${#SHERPA_CONFIGS[@]} + ${#WHISPER_CONFIGS[@]} + ${#LLM_CONFIGS[@]} )), Runs per config: $NUM_RUNS, Total tests: $TOTAL_TESTS"
echo ""

run_test() {
    local backend="$1" vad="$2" progressive="$3" provider="$4" run_num="$5"
    CURRENT=$((CURRENT + 1))
    RUN_ID="${backend}_vad${vad}_prog${progressive}_${provider}_r${run_num}"
    echo -n "[$CURRENT/$TOTAL_TESTS] $RUN_ID ... "

    # Clear logcat before this test
    $ADB logcat -c 2>/dev/null

    # Launch BenchmarkActivity with config
    $ADB shell "am start -n $ACTIVITY \
        --es backend '$backend' \
        --ez vad $vad \
        --ez progressive $progressive \
        --es provider '$provider' \
        --es file_path '$AUDIO_FILE' \
        --es run_id '$RUN_ID'" 2>/dev/null

    # Wait for PERF log or timeout
    preprocess_ms=""
    inference_ms=""
    total_ms=""
    ttft_ms=""
    path=""
    elapsed=0

    while [[ $elapsed -lt $TIMEOUT_PER_TEST ]]; do
        sleep 5
        elapsed=$((elapsed + 5))

        # Check for errors
        bench_error=$($ADB logcat -d -s "BenchmarkActivity" -v brief 2>/dev/null | { grep "BENCH_ERROR" || true; } | tail -1)
        app_error=$($ADB logcat -d -s "AndroidRuntime" -v brief 2>/dev/null | { grep "FATAL\|Exception" || true; } | tail -1)

        if [[ -n "$bench_error" ]] || [[ -n "$app_error" ]]; then
            echo "ERROR"
            echo "  Error: ${bench_error:-}${app_error:-}"
            break
        fi

        # Check for PERF lines
        perf_line=$($ADB logcat -d -s "TranscriptionOrchestrator" -v brief 2>/dev/null | { grep "PERF:" || true; } | tail -1)

        if [[ -n "$perf_line" ]]; then
            if echo "$perf_line" | grep -q "preprocessing"; then
                # Preprocessing complete — capture it, keep polling for completion
                if [[ -z "$preprocess_ms" ]]; then
                    preprocess_ms=$(echo "$perf_line" | grep -oP 'preprocessing \K\d+' || true)
                    path="batch"
                fi
                # For single-chunk batch: look for Inference timing
                infer_line=$($ADB logcat -d -s "TranscriptionOrchestrator" -v brief 2>/dev/null | { grep "Inference timing:" || true; } | tail -1)
                inference_ms=$(echo "$infer_line" | grep -oP 'timing: \K\d+' || true)
                if [[ -n "$inference_ms" ]]; then
                    total_ms=$((preprocess_ms + inference_ms))
                    echo "OK (${total_ms}ms)"
                    break
                fi
            elif echo "$perf_line" | grep -q "pipeline total"; then
                total_ms=$(echo "$perf_line" | grep -oP 'pipeline total \K\d+' || true)
                ttft_decode=$(echo "$perf_line" | grep -oP 'ttft_decode=\K\d+' || true)
                path="pipeline"

                ttft_line=$($ADB logcat -d -s "TranscriptionOrchestrator" -v brief 2>/dev/null | { grep "time-to-first-text" || true; } | tail -1)
                ttft_ms=$(echo "$ttft_line" | grep -oP 'time-to-first-text = \K\d+' || true)

                echo "OK (${total_ms:-?}ms)"
                break
            elif echo "$perf_line" | grep -q "progressive total"; then
                total_ms=$(echo "$perf_line" | grep -oP 'progressive total \K\d+' || true)
                path="progressive"
                echo "OK (${total_ms:-?}ms)"
                break
            elif echo "$perf_line" | grep -q "parallel total"; then
                total_ms=$(echo "$perf_line" | grep -oP 'parallel total \K\d+' || true)
                path="parallel"
                echo "OK (${total_ms:-?}ms)"
                break
            fi
        fi

        echo -n "."
    done

    if [[ $elapsed -ge $TIMEOUT_PER_TEST ]]; then
        echo "TIMEOUT"
    fi

    echo "$RUN_ID,$backend,$vad,$progressive,$provider,$run_num,${preprocess_ms:-},${inference_ms:-},${total_ms:-},${ttft_ms:-},${path:-}" >> "$RESULTS_CSV"
}

run_backend_group() {
    local group_name="$1"
    shift
    local configs=("$@")

    echo "--- $group_name (force-stop + warmup) ---"

    # Force-stop to get a clean state for this model
    $ADB shell "am force-stop $PACKAGE" 2>/dev/null
    sleep 2

    # Warmup run: first config, discarded
    local first_config="${configs[0]}"
    IFS=':' read -r backend vad progressive provider <<< "$first_config"
    echo -n "[warmup] $backend ... "
    $ADB logcat -c 2>/dev/null
    $ADB shell "am start -n $ACTIVITY \
        --es backend '$backend' \
        --ez vad $vad \
        --ez progressive $progressive \
        --es provider '$provider' \
        --es file_path '$AUDIO_FILE' \
        --es run_id 'warmup_${backend}'" 2>/dev/null

    # Wait for warmup to complete
    local warmup_elapsed=0
    while [[ $warmup_elapsed -lt $TIMEOUT_PER_TEST ]]; do
        sleep 5
        warmup_elapsed=$((warmup_elapsed + 5))
        local warmup_done=$($ADB logcat -d -s "TranscriptionOrchestrator" -v brief 2>/dev/null | { grep -E "PERF:|Inference timing:|pipeline total" || true; } | tail -1)
        if [[ -n "$warmup_done" ]]; then
            echo "warmed up"
            break
        fi
        echo -n "."
    done
    if [[ $warmup_elapsed -ge $TIMEOUT_PER_TEST ]]; then
        echo "TIMEOUT (warmup failed)"
    fi

    # Brief pause after warmup
    sleep 3

    # Run all configs for this backend
    for config in "${configs[@]}"; do
        IFS=':' read -r backend vad progressive provider <<< "$config"
        for run_num in $(seq 1 $NUM_RUNS); do
            run_test "$backend" "$vad" "$progressive" "$provider" "$run_num"
        done
    done
}

# Run each backend group
run_backend_group "Parakeet (sherpa-onnx)" "${SHERPA_CONFIGS[@]}"
run_backend_group "Whisper Distil IT" "${WHISPER_CONFIGS[@]}"
run_backend_group "Gemma 4 E4B (llm)" "${LLM_CONFIGS[@]}"

echo ""
echo "=== Results written to $RESULTS_CSV ==="
echo ""
echo "Raw results:"
column -t -s',' "$RESULTS_CSV"
