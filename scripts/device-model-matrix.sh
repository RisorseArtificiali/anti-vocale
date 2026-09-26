#!/usr/bin/env bash
# Device model-matrix smoke: every bundled backend loads and transcribes once
# on the connected phone (TASK-413, GH #68 defense 3).
#
# GH #68 in one line: qwen3-asr shipped broken across five releases because
# no device ever loaded it; this script makes that gap a release BLOCKER. For
# every entry in models_catalog.json it (1) verifies the model is installed
# on the debug build, (2) fires one PROCESS_REQUEST (backend_id override,
# task_id matrix-<id>-<ts>) over a short REAL speech clip, and (3) requires
# status=SUCCESS for that task_id in the Room logs DB before the timeout.
#
# Usage:
#   scripts/device-model-matrix.sh --audio SPEECH.wav [--download]
#     --audio     REQUIRED. A short REAL speech clip (any MediaCodec-decodable
#                 format). Not a sine or silence: VAD strips speechless audio
#                 and the run fails with "No transcription produced" (the
#                 TASK-586 sine lesson).
#     --download  When a bundled model is absent, fetch its default variant
#                 from the catalog's HuggingFace URLs and stage it into the
#                 app sandbox (curl + adb push + run-as cp), instead of
#                 skipping. Without the flag absent models are skipped with a
#                 WARN line and the script STILL EXITS 1 at the end: a release
#                 gate must be explicit about coverage gaps.
#
# Env:
#   ADB       adb binary (default: ~/Android/Sdk/platform-tools/adb)
#   OUT_DIR   artifact dir (default: /tmp/device-matrix-<timestamp>)
#
# Exit: 0 only when every bundled entry PASSed. Any FAIL or SKIP exits 1.
#
# Scope honesty (review round): the gate exercises the DEBUG BUILD INSTALLED
# ON THE PHONE (the SPI and receivers live there); install the release tree
# first (./scripts/install.sh) so PASS says something about the code being
# tagged. Per entry it drives ONE variant: the first complete one in catalog
# order, which is what the app's own resolver (SherpaModelManager) loads.
# The Gemma/LiteRT backend is not a catalog entry (no pinned artifact) and is
# out of this matrix's catalog-driven scope.
#
# TASK-409 SPI note: the installed-model probe below shells `run-as ls` per
# variant dir because TEST_SPI has no install-state surface yet. Once
# nav/records cover model install state (the TASK-409 roadmap), this probe
# collapses to one SPI get key and the --download staging keeps only the
# fetch half.

set -euo pipefail

ADB="${ADB:-$HOME/Android/Sdk/platform-tools/adb}"
PKG="com.antivocale.app.debug"
DB_NAME="anti_vocale_database"
POLL_SECS=5
TIMEOUT_SECS=300
HERE="$(cd "$(dirname "$0")" && pwd)"
PROJECT="$(cd "$HERE/.." && pwd)"
CATALOG="$PROJECT/app/src/main/assets/models_catalog.json"
OUT_DIR="${OUT_DIR:-/tmp/device-matrix-$(date +%Y%m%d-%H%M%S)}"

AUDIO=""
DOWNLOAD=0
while [ $# -gt 0 ]; do
  case "$1" in
    --audio) AUDIO="${2:?--audio needs a file}"; shift 2 ;;
    --download) DOWNLOAD=1; shift ;;
    -h|--help) sed -n '2,/^$/p' "$0" | sed '$d'; exit 0 ;;
    *) echo "unknown flag: $1 (see --help)" >&2; exit 2 ;;
  esac
done
[ -n "$AUDIO" ] || { echo "usage: $0 --audio SPEECH.wav [--download]" >&2; exit 2; }
[ -f "$AUDIO" ] || { echo "audio not found: $AUDIO" >&2; exit 2; }

# ── device selection (wireless serial carries spaces: capture verbatim) ─────
serial="$("$ADB" devices 2>/dev/null | sed -n 's/^\(.*_adb-tls-connect\._tcp\)[[:space:]]*device$/\1/p' | head -1)"
if [ -z "$serial" ]; then
  # USB-attached fallback: any plain device-state serial.
  serial="$("$ADB" devices 2>/dev/null | sed -n 's/^\([A-Za-z0-9._:-]*\)[[:space:]]*device$/\1/p' | head -1)"
fi
adbsh() { # adb shell wrapper that survives the no-device dry run; stdin is
  # closed here so an adb invocation inside a while-read loop cannot eat it
  if [ -z "$serial" ]; then return 1; else "$ADB" -s "$serial" shell "$@" </dev/null 2>/dev/null; fi
}

mkdir -p "$OUT_DIR"
echo "== device: ${serial:-NONE CONNECTED (dry run: every entry will report absent)}"
echo "== output: $OUT_DIR"

# ── catalog rows (id, storage dir, per-variant dir/files/urls) via python3 ──
catalog_tsv="$OUT_DIR/catalog.tsv"
python3 - "$CATALOG" > "$catalog_tsv" <<'PY'
import json, sys
catalog = json.loads(open(sys.argv[1], encoding="utf-8").read())
for entry in catalog["models"]:
    storage = entry.get("storageDir") or entry["id"]
    default = entry.get("flags", {}).get("defaultVariant") or entry["variants"][0]["name"]
    for variant in entry["variants"]:
        files = "|".join(f["name"] for f in variant["files"])
        urls = "|".join(
            f"{'https://huggingface.co/%s/resolve/main/%s' % (variant['source']['repo'], f['name']) if variant['source']['kind'] == 'huggingface' else (variant['source']['template'] or '').replace('{file}', f['name'])}"
            for f in variant["files"])
        shas = "|".join(f.get("sha256") or "" for f in variant["files"])
        print("\t".join([entry["id"], storage, variant["name"], variant["dirName"],
                         "1" if variant["name"] == default else "0", files, urls, shas]))
PY

# ── per-entry installed-variant probe (mirrors SherpaModelManager: first
#    catalog-order variant whose dir holds every file wins) ──────────────────
declare -A CHOSEN_DIR CHOSEN_VARIANT
SKIPPED=() FAILED=() PASSED=()
while IFS=$'\t' read -r id storage vname vdir is_default files _urls _shas; do
  [ -n "${CHOSEN_DIR[$id]:-}" ] && continue
  # tr -d '\r': adb shell's pty CRLF-izes output on this device class, and an
  # exact-line grep would then miss every name (review F2).
  listing="$(adbsh run-as "$PKG" ls "files/$storage/$vdir" | tr -d '\r' || true)"
  complete=1
  IFS='|' read -ra FARRAY <<< "$files"
  for f in "${FARRAY[@]}"; do
    if [[ "$f" == */* ]]; then
      # Nested file (qwen3 tokenizer/...): the NAME must be listed in its own
      # subdir; a bare tokenizer/ dir with missing members is NOT complete
      # (review F6: a partial install would otherwise doom every request).
      sub="$(adbsh run-as "$PKG" ls "files/$storage/$vdir/${f%/*}" | tr -d '\r' || true)"
      grep -qx "${f##*/}" <<< "$sub" || { complete=0; break; }
    else
      grep -qx "$f" <<< "$listing" || { complete=0; break; }
    fi
  done
  if [ "$complete" = 1 ]; then
    CHOSEN_DIR[$id]="files/$storage/$vdir"
    CHOSEN_VARIANT[$id]="$vname"
    echo "installed: $id -> $vname ($vdir)"
  fi
done < "$catalog_tsv"

# ── absent entries: download (default variant) or skip-with-WARN ────────────
while IFS=$'\t' read -r id storage vname vdir is_default files urls shas; do
  [ -n "${CHOSEN_DIR[$id]:-}" ] && continue
  [ "$is_default" = 1 ] || continue   # absent entry: fetch only its default variant
  if [ "$DOWNLOAD" != 1 ]; then
    echo "WARN: $id not installed ($storage/$vdir absent); SKIPPING (release-gate gap; rerun with --download or install from the Models tab)"
    SKIPPED+=("$id")
    continue
  fi
  echo "downloading: $id $vname from the catalog urls"
  IFS='|' read -ra FARRAY <<< "$files"
  IFS='|' read -ra UARRAY <<< "$urls"
  IFS='|' read -ra SARRAY <<< "$shas"
  ok=1
  for i in "${!FARRAY[@]}"; do
    dest="${FARRAY[$i]}"
    tmp="/data/local/tmp/$(basename "$dest")"
    curl -sS -L --fail --retry 3 --retry-delay 5 --connect-timeout 30 \
      -o "$OUT_DIR/$(basename "$dest")" "${UARRAY[$i]}" || { echo "FAIL: download ${UARRAY[$i]}" >&2; ok=0; break; }
    # Verify against the catalog pin IN HAND: URL-mirror drift fails here,
    # at the fetch site, instead of five steps later as CorruptModelFiles.
    if [ -n "${SARRAY[$i]:-}" ]; then
      actual="$(sha256sum "$OUT_DIR/$(basename "$dest")" | cut -d' ' -f1)"
      [ "$actual" = "${SARRAY[$i]}" ] || {
        echo "FAIL: sha256 mismatch for $dest (catalog pins ${SARRAY[$i]}, download is $actual)" >&2
        ok=0; break;
      }
    fi
    adbsh true || { ok=0; break; }
    "$ADB" -s "$serial" push "$OUT_DIR/$(basename "$dest")" "$tmp" >/dev/null || { ok=0; break; }
    # Nested destinations (tokenizer/merges.txt) need their parent dir in the sandbox.
    parent="$(dirname "files/$storage/$vdir/$dest")"
    adbsh run-as "$PKG" mkdir -p "$parent" || { ok=0; break; }
    adbsh run-as "$PKG" cp "$tmp" "files/$storage/$vdir/$dest" || { ok=0; break; }
  done
  if [ "$ok" = 1 ]; then
    CHOSEN_DIR[$id]="files/$storage/$vdir"
    CHOSEN_VARIANT[$id]="$vname"
    echo "downloaded: $id -> $vname"
  else
    echo "WARN: $id download/staging failed; SKIPPING"
    SKIPPED+=("$id")
  fi
done < "$catalog_tsv"

# ── stage the audio where PROCESS_REQUEST may read it (allowlist: the app's
#    filesDir/shared_audio; TASK-274 rejects every other path) ───────────────
AUDIO_NAME="matrix-audio.${AUDIO##*.}"
AUDIO_SANDBOX="/data/user/0/$PKG/files/shared_audio/$AUDIO_NAME"
if [ -n "$serial" ]; then
  "$ADB" -s "$serial" push "$AUDIO" /data/local/tmp/$AUDIO_NAME >/dev/null
  "$ADB" -s "$serial" shell run-as "$PKG" mkdir -p files/shared_audio
  "$ADB" -s "$serial" shell run-as "$PKG" cp /data/local/tmp/$AUDIO_NAME "files/shared_audio/$AUDIO_NAME"
  echo "== audio staged: $AUDIO_SANDBOX"
else
  echo "WARN: no device; audio staging skipped"
fi

# ── app state: launch once (stopped-state rule), save + flip the automation
#    consent the Tasker receiver gates on, restore on exit ───────────────────
# Capture-failure default is false (the shipping default): a broken SPI read
# must not leave the consent stuck ON (review F5), at worst it turns OFF a
# toggle the user had flipped on a DEBUG install.
PREV_AUTOMATION="false"
CAPTURE_OK=0
if [ -n "$serial" ]; then
  "$ADB" -s "$serial" shell am start -n "$PKG/com.antivocale.app.MainActivity" >/dev/null 2>&1 || true
  sleep 2
  captured="$("$ADB" -s "$serial" shell am broadcast -a com.antivocale.app.TEST_SPI \
    -n "$PKG/com.antivocale.app.receiver.TestSpiReceiver" --es op get \
    | grep -o '"externalAutomationEnabled":[a-z]*' | cut -d: -f2 | tr -d '\r' || true)"
  if [ "$captured" = "true" ] || [ "$captured" = "false" ]; then
    PREV_AUTOMATION="$captured"; CAPTURE_OK=1
  else
    echo "WARN: could not read externalAutomationEnabled via SPI; restore will default it to false"
  fi
  "$ADB" -s "$serial" shell am broadcast -a com.antivocale.app.TEST_SPI \
    -n "$PKG/com.antivocale.app.receiver.TestSpiReceiver" \
    --es op set --es key external_automation --es value true >/dev/null
fi
restore_automation() {
  if [ -n "$serial" ]; then
    [ "$CAPTURE_OK" = 1 ] || echo "WARN: restoring external_automation to the default false (capture had failed)"
    "$ADB" -s "$serial" shell am broadcast -a com.antivocale.app.TEST_SPI \
      -n "$PKG/com.antivocale.app.receiver.TestSpiReceiver" \
      --es op set --es key external_automation --es value "$PREV_AUTOMATION" >/dev/null 2>&1 || true
  fi
}
DB_TMP="$(mktemp -d)"
cleanup() { restore_automation; rm -rf "$DB_TMP"; }
trap cleanup EXIT
poll_db() { # $1 EXACT task id -> echoes "STATUS|error|model|ms|audioSecs"
  "$ADB" -s "$serial" exec-out run-as "$PKG" cat "databases/$DB_NAME-wal" > "$DB_TMP/db-wal" 2>/dev/null || true
  "$ADB" -s "$serial" exec-out run-as "$PKG" cat "databases/$DB_NAME" > "$DB_TMP/db" 2>/dev/null || true
  python3 - "$1" "$DB_TMP/db" <<'PY'
import sqlite3, sys
task_id, db = sys.argv[1], sys.argv[2]
try:
    con = sqlite3.connect(db)
    con.execute("PRAGMA wal_checkpoint(FULL)")
    # EXACT task-id match: previous runs' rows persist in Room history, and a
    # prefix LIKE would let a stale PASS/FAIL answer for this run (review F1).
    row = con.execute(
        "SELECT status, errorMessage, modelName, durationMs, audioDurationSeconds "
        "FROM logs WHERE taskId = ? LIMIT 1",
        (task_id,)).fetchone()
    if row:
        print("|".join("" if v is None else str(v) for v in row))
except Exception:
    pass
PY
}

# ── the matrix: one micro-transcription per bundled backend ─────────────────
# Unique entry ids, catalog order; one run-scoped task id per entry so the
# DB poll can match EXACTLY this run's row.
RUN_TS="$(date +%s)"
mapfile -t IDS < <(awk -F'\t' '!seen[$1]++ {print $1}' "$catalog_tsv")

for id in "${IDS[@]}"; do
  echo
  echo "===== $id (${CHOSEN_VARIANT[$id]:-ABSENT}) ====="
  # Entries recorded as skipped above (absent without --download, no device,
  # or a failed download) report once here instead of firing a doomed request.
  if [ -z "${CHOSEN_DIR[$id]:-}" ] || [ -z "$serial" ]; then
    echo "SKIP (gap recorded above)"
    case " ${SKIPPED[*]-} " in *" $id "*) ;; *) SKIPPED+=("$id");; esac
    continue
  fi

  task_id="matrix-$id-$RUN_TS"
  "$ADB" -s "$serial" shell am broadcast -a com.antivocale.app.PROCESS_REQUEST \
    -n "$PKG/com.antivocale.app.receiver.TaskerRequestReceiver" \
    --es request_type audio --es file_path "$AUDIO_SANDBOX" \
    --es task_id "$task_id" --es backend_id "$id" >/dev/null
  echo "request fired (backend_id=$id, task_id=$task_id); polling the logs DB (max ${TIMEOUT_SECS}s)"

  # Poll first, then sleep: a fast backend already in the DB at t~0 skips the
  # blind first interval.
  verdict=""
  row="$(poll_db "$task_id" || true)"
  for _ in $(seq 1 $((TIMEOUT_SECS / POLL_SECS))); do
    if [ -n "$row" ]; then
      status="${row%%|*}"
      if [ "$status" = "SUCCESS" ] || [ "$status" = "ERROR" ]; then
        verdict="$row"; break
      fi
    fi
    sleep "$POLL_SECS"
    row="$(poll_db "$task_id" || true)"
  done

  if [ -z "$verdict" ]; then
    echo "FAIL: no DB row reached SUCCESS/ERROR within ${TIMEOUT_SECS}s"
    FAILED+=("$id")
  elif [ "${verdict%%|*}" = "SUCCESS" ]; then
    echo "PASS: $verdict"
    PASSED+=("$id")
  else
    echo "FAIL: $verdict"
    # Processing-context evidence from logcat for the failure triage.
    "$ADB" -s "$serial" logcat -d -t 300 2>/dev/null \
      | grep -E "SherpaBackend|InferenceService|CorruptModelFiles|missing required metadata" | tail -8 || true
    FAILED+=("$id")
  fi
done

# ── summary: the release gate is explicit about every kind of gap ───────────
echo
echo "== matrix summary: ${#PASSED[@]} PASS, ${#FAILED[@]} FAIL, ${#SKIPPED[@]} SKIP"
[ ${#PASSED[@]} -gt 0 ] && echo "PASS: ${PASSED[*]}"
[ ${#FAILED[@]} -gt 0 ] && echo "FAIL: ${FAILED[*]}"
[ ${#SKIPPED[@]} -gt 0 ] && echo "SKIP (gaps; the gate fails): ${SKIPPED[*]}"
if [ ${#FAILED[@]} -gt 0 ] || [ ${#SKIPPED[@]} -gt 0 ]; then
  echo "release gate: BLOCKED (TASK-413: every bundled backend must load and transcribe before tag)"
  exit 1
fi
echo "release gate: PASS"
