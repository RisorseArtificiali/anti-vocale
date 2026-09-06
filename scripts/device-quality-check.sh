#!/usr/bin/env bash
# Device-side transcription quality check (2026-09-06 session, scripted).
#
# Runs one audio file through one or more backends on the connected debug
# install, pulls the transcript from the Room logs DB, and (when a reference
# text is given) scores it with unit-cost WER over normalized text. Designed
# for the recurring "verify the fix end-to-end on device" gate before any
# release that changes chunking, backends, or audio preprocessing:
# docs/testing-spi.md documents the SPI used to drive the app.
#
# Usage:
#   scripts/device-quality-check.sh AUDIO.wav [BACKEND...] [--ref REF.txt]
#     AUDIO.wav    local file (any MediaCodec-decodable format)
#     BACKEND...   one or more catalog ids (gigaam, sherpa-onnx, whisper...);
#                  defaults to gigaam sherpa-onnx (the fix + the control)
#     --ref FILE   optional reference transcript (plain text, any language);
#                  with it, WER is computed per backend. The reference must
#                  cover the SAME audio span: proportional slices of a longer
#                  transcript are approximate (see #84 for the caveat).
#
# Env:
#   ADB          adb binary (default: ~/Android/Sdk/platform-tools/adb)
#   NO_INSTALL=1 skip the APK build+install step (reuse what is on device)
#
# Output: per backend, the transcript saved to OUT_DIR/<backend>.txt, the
# logcat evidence lines, and the WER when --ref is given.
#
# Evidence recorded per run (what a reviewer needs, #84 style):
#   - "expecting N chunks" and "Chunk cap tightened" from logcat
#   - "PERF: pipeline total" (device RTF)
#   - the DB row (status, model name, durationMs, audioDurationSeconds)
#   - whether the punctuation pass fired (llm activation lines)

set -euo pipefail

ADB="${ADB:-$HOME/Android/Sdk/platform-tools/adb}"
PKG="com.antivocale.app.debug"
HERE="$(cd "$(dirname "$0")" && pwd)"
PROJECT="$(cd "$HERE/.." && pwd)"
OUT_DIR="${OUT_DIR:-/tmp/device-quality-$(date +%Y%m%d-%H%M%S)}"

# ── args ────────────────────────────────────────────────────────────────────
AUDIO=""
REF=""
BACKENDS=()
while [ $# -gt 0 ]; do
  case "$1" in
    --ref) REF="${2:?--ref needs a file}"; shift 2 ;;
    -h|--help) sed -n '2,30p' "$0"; exit 0 ;;
    -*) echo "unknown flag: $1" >&2; exit 2 ;;
    *) if [ -z "$AUDIO" ]; then AUDIO="$1"; else BACKENDS+=("$1"); fi; shift ;;
  esac
done
[ -n "${AUDIO:-}" ] || { echo "usage: $0 AUDIO.wav [BACKEND...] [--ref REF.txt]" >&2; exit 2; }
[ -f "$AUDIO" ] || { echo "audio not found: $AUDIO" >&2; exit 2; }
[ -z "${REF:-}" ] || [ -f "$REF" ] || { echo "ref not found: $REF" >&2; exit 2; }
# Default: the fix (gigaam) + the control (parakeet)
[ ${#BACKENDS[@]} -gt 0 ] || BACKENDS=(gigaam sherpa-onnx)

BASENAME="$(basename "$AUDIO")"

# ── device selection ────────────────────────────────────────────────────────
serial="$("$ADB" devices | sed -n 's/^\(.*[[:space:]]device\)$/\1/p' | head -1 | awk '{print $1}')"
[ -n "$serial" ] || { echo "no adb device connected" >&2; exit 1; }
echo "== device: $serial"

# ── optional build+install (the SPI only exists on debug) ─────────────────
if [ "${NO_INSTALL:-0}" != "1" ]; then
  echo "== building current tree (debug)"
  (cd "$PROJECT" && ./gradlew -q assemblePlayStoreDebug)
  APK="$PROJECT/app/build/outputs/apk/playStore/debug/app-playStore-arm64-v8a-debug.apk"
  # install.sh's 60s mtime gate: the APK may be up-to-date
  touch "$APK"
  "$ADB" -s "$serial" install -r "$APK" >/dev/null
fi
# Stopped-state rule: one launch before any SPI call (docs/testing-spi.md)
"$ADB" -s "$serial" shell am start -n "$PKG/com.antivocale.app.MainActivity" >/dev/null 2>&1 || true
sleep 2

# ── stage the audio in the app sandbox (no storage permissions) ────────────
"$ADB" -s "$serial" push "$AUDIO" "/data/local/tmp/$BASENAME" >/dev/null
"$ADB" -s "$serial" shell "run-as $PKG cp /data/local/tmp/$BASENAME files/$BASENAME"
SANDBOX="/data/user/0/$PKG/files/$BASENAME"
echo "== audio staged: $SANDBOX ($(du -h "$AUDIO" | cut -f1))"

mkdir -p "$OUT_DIR"
echo "== output: $OUT_DIR"

# ── save the pre-run state so we can restore it ───────────────────────────
PREV_BACKEND="$("$ADB" -s "$serial" shell am broadcast -a com.antivocale.app.TEST_SPI \
  -n "$PKG/com.antivocale.app.receiver.TestSpiReceiver" --es op get \
  | grep -o '"transcriptionBackend":"[^"]*"' | cut -d'"' -f4 || true)"
echo "== previous backend: ${PREV_BACKEND:-unknown}"

wer_py="$OUT_DIR/wer.py"
cat > "$wer_py" <<'PY'
import re, sys
def norm(s):
    s = s.lower().replace("ё", "е")
    s = re.sub(r"[^\wа-я\s]", " ", s, flags=re.UNICODE)
    return re.sub(r"\s+", " ", s).strip()
ref = norm(open(sys.argv[1], encoding="utf-8").read()).split()
hyp = norm(open(sys.argv[2], encoding="utf-8").read()).split()
if not ref:
    print("WER: n/a (empty reference)"); sys.exit(0)
d = [[0]*(len(hyp)+1) for _ in range(len(ref)+1)]
for i in range(len(ref)+1): d[i][0]=i
for j in range(len(hyp)+1): d[0][j]=j
for i in range(1,len(ref)+1):
    for j in range(1,len(hyp)+1):
        d[i][j]=min(d[i-1][j]+1, d[i][j-1]+1, d[i-1][j-1]+(0 if ref[i-1]==hyp[j-1] else 1))
print(f"WER: {d[-1][-1]/len(ref)*100:.2f}%  (ref {len(ref)} words, hyp {len(hyp)})")
PY

# ── per-backend run ─────────────────────────────────────────────────────────
for BE in "${BACKENDS[@]}"; do
  echo
  echo "===== backend: $BE ====="
  "$ADB" -s "$serial" shell am broadcast -a com.antivocale.app.TEST_SPI \
    -n "$PKG/com.antivocale.app.receiver.TestSpiReceiver" \
    --es op set --es key backend --es value "$BE" >/dev/null

  LOG="$OUT_DIR/$BE.logcat"
  "$ADB" -s "$serial" logcat -c
  "$ADB" -s "$serial" logcat -v time > "$LOG" 2>/dev/null &
  LOGPID=$!

  "$ADB" -s "$serial" shell am broadcast -a com.antivocale.app.PROCESS_REQUEST \
    -n "$PKG/com.antivocale.app.receiver.TaskerRequestReceiver" \
    --es request_type audio --es file_path "$SANDBOX" \
    --es task_id "qc-$BE-$(date +%s)" >/dev/null
  echo "request fired; waiting for completion"

  # Poll the DB row until SUCCESS/ERROR (max 30 min: a 2h ceiling exists but
  # quality checks on multi-minute files land in single-digit minutes)
  for _ in $(seq 1 360); do  # SC2034: counter unused by design
    sleep 5
    STATUS="$("$ADB" -s "$serial" exec-out run-as "$PKG" cat databases/anti_vocale_database-wal 2>/dev/null > /tmp/qc.db-wal; \
      "$ADB" -s "$serial" exec-out run-as "$PKG" cat databases/anti_vocale_database 2>/dev/null > /tmp/qc.db; \
      python3 - "$BE" <<'PY'
import sqlite3, sys
try:
    con = sqlite3.connect("/tmp/qc.db")
    con.execute("PRAGMA wal_checkpoint(FULL)")
    row = con.execute(
        "SELECT status, result FROM logs WHERE taskId LIKE ? ORDER BY timestamp DESC LIMIT 1",
        (f"qc-{sys.argv[1]}-%",)).fetchone()
    if row: print(row[0])
except Exception: pass
PY
)"
    [ "$STATUS" = "SUCCESS" ] || [ "$STATUS" = "ERROR" ] || continue
    break
  done
  sleep 2  # let the tail lines land
  kill "$LOGPID" 2>/dev/null || true

  echo "-- evidence ($LOG):"
  grep -E "expecting [0-9]+ chunks|Chunk cap tightened|PERF: pipeline total|Punctuation pass applied|Punctuation pass skipped|Keep-alive" "$LOG" | tail -6 || true

  # Transcript from the DB (task-id scoped, so multiple runs coexist)
  python3 - "$BE" "$OUT_DIR" <<'PY'
import sqlite3, sys
be, out = sys.argv[1], sys.argv[2]
con = sqlite3.connect("/tmp/qc.db")
con.execute("PRAGMA wal_checkpoint(FULL)")
row = con.execute(
    "SELECT status, result, durationMs, audioDurationSeconds, modelName FROM logs "
    "WHERE taskId LIKE ? ORDER BY timestamp DESC LIMIT 1",
    (f"qc-{be}-%",)).fetchone()
if not row:
    print("no row found"); sys.exit(1)
status, result, dur, audur, model = row
print(f"-- DB: {status} | {model} | {dur}ms | {audur}s | {len(result)} chars")
open(f"{out}/{be}.txt", "w", encoding="utf-8").write(result)
PY

  if [ -n "${REF:-}" ]; then
    python3 "$wer_py" "$REF" "$OUT_DIR/$BE.txt"
  fi
  echo "-- transcript saved: $OUT_DIR/$BE.txt"
done

# ── restore ─────────────────────────────────────────────────────────────────
if [ -n "${PREV_BACKEND:-}" ]; then
  "$ADB" -s "$serial" shell am broadcast -a com.antivocale.app.TEST_SPI \
    -n "$PKG/com.antivocale.app.receiver.TestSpiReceiver" \
    --es op set --es key backend --es value "$PREV_BACKEND" >/dev/null
  echo "== backend restored: $PREV_BACKEND"
fi
echo
echo "done. artifacts in $OUT_DIR (transcripts, per-backend logcat, wer.py)"
[ "${REF:-}" = "" ] || echo "review the transcripts against the reference: a human who reads the language is the gate (#84)"
