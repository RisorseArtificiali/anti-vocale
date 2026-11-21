#!/usr/bin/env bash
# fdroid-watch: unattended guard against the recurring F-Droid release failure
# classes. Runs on bird as a systemd user timer (TASK-1028); Telegram-alerts
# the maintainer on real findings only, never on OK / IN-PROGRESS. All
# queries are anonymous; the identity of "the release" comes from the latest
# GitHub release tag, so this works for every future release without edits.
#
# Failure classes covered:
#   1. an open fdroiddata MR for the latest release carries a stale
#      sherpa_onnx srclib pin and NO open MR carries the correct one
#      (the 1.15.0 and 1.17.0 class)                      -> ALERT (once/tag)
#   2. every open MR for the latest release has a failed pipeline -> ALERT (once/tag)
#   3. a green, pin-correct MR has sat unmerged for >24h   -> ALERT (once/MR)
#   4. no fdroiddata activity 72h after the release (bot missed the tag,
#      the 1.14.0 blind spot)                             -> ALERT (once/tag)
#   5. the watch's own checks fail 3 times in a row (infra) -> ALERT
#
# Transient API hiccups must never produce a false alert: a failed recipe or
# pipeline fetch degrades the run (log + exit 2) instead of feeding the
# verdicts, and repeated degradation trips class 5 through check_fail.
#
# Usage: fdroid-watch.sh [--dry-run]   (--dry-run prints alerts, sends nothing)
# Self-test: FDROID_WATCH_PIN_OVERRIDE=<sha> forces the expected pin.
# Exit: 0 quiet/good/in-progress, 1 alerted, 2 check failure.
set -u

DRY_RUN=0
[ "${1:-}" = "--dry-run" ] && DRY_RUN=1

STATE_DIR="$HOME/.local/state/fdroid-watch"
mkdir -p "$STATE_DIR"
STATE="$STATE_DIR/state"
LOG="$STATE_DIR/watch.log"
CHAT_ID="363645715"

GH_API="https://api.github.com/repos/RisorseArtificiali/anti-vocale"
FD_API="https://gitlab.com/api/v4/projects/fdroid%2Ffdroiddata"
API_BASE="https://gitlab.com/api/v4/projects"
RECIPE_ENC="metadata%2Fcom.antivocale.app.yml"
INDEX_API="https://f-droid.org/api/v1/packages/com.antivocale.app"
CURL="curl -sf --retry 2 --retry-delay 5 --max-time 30"

# One writer at a time: a hung run must not overlap the next tick.
exec 9>"$STATE_DIR/lock"
flock -n 9 || exit 0

# Keep the log bounded (one line per matching MR per tick adds up).
[ -f "$LOG" ] && [ "$(wc -c < "$LOG")" -gt 1048576 ] && : > "$LOG"

say() { echo "$(date -u +%FT%TZ) $*" >> "$LOG"; }

was_seen() { grep -qF "$1" "$STATE" 2>/dev/null; }
# Dry-run must never write dedup state: a self-test must not suppress a
# real alert for the same tag later.
mark_seen() { [ "$DRY_RUN" = 1 ] && return 0; echo "$1 $(date -u +%FT%TZ)" >> "$STATE"; }

# notify <kind> <text>: Telegram the maintainer (or print in dry-run).
notify() {
  say "ALERT[$1]: $2"
  if [ "$DRY_RUN" = 1 ]; then say "(dry-run: not sent)"; return 0; fi
  if [ ! -f "$HOME/.ccgram/.env" ]; then say "(no ~/.ccgram/.env; alert not sent)"; return 1; fi
  set -a; . "$HOME/.ccgram/.env"; set +a
  $CURL -X POST "https://api.telegram.org/bot${TELEGRAM_BOT_TOKEN:-}/sendMessage" \
    --data-urlencode "chat_id=$CHAT_ID" \
    --data-urlencode "text=fdroid-watch ($1): $2" >/dev/null \
    || say "(telegram send failed)"
}

# check_fail <detail>: count consecutive infra failures; alert at 3 and
# every 15th consecutive failure after that (once per count, not per tick).
check_fail() {
  local n
  n=$(cat "$STATE.fail" 2>/dev/null || true)
  case "$n" in ''|*[!0-9]*) n=0 ;; esac
  n=$((n + 1))
  echo "$n" > "$STATE.fail"
  say "CHECK-FAILED ($n consecutive): $1"
  if [ "$n" -eq 3 ] || [ $((n % 15)) -eq 0 ]; then
    notify "infra" "fdroid-watch checks failed $n times in a row: $1. The release watch is blind."
  fi
  exit 2
}

# --- release identity: latest GitHub release tag + its sherpa pin ----------
REL=$($CURL "$GH_API/releases/latest") || check_fail "cannot fetch github releases/latest"
eval "$(printf '%s' "$REL" | python3 -c '
import sys, json
r = json.load(sys.stdin)
print("TAG=" + r["tag_name"])
print("PUBLISHED=" + r["published_at"])
')" || check_fail "bad github releases/latest payload"
case "${TAG:-}" in v[0-9]*) ;; *) check_fail "unexpected tag format: ${TAG:-unset}" ;; esac
NAME="${TAG#v}"
PIN=$($CURL "https://raw.githubusercontent.com/RisorseArtificiali/anti-vocale/$TAG/.sherpa-version" \
  | grep '^srclib-commit ' | awk '{print $2}')
[ -n "$PIN" ] || check_fail "no srclib-commit in .sherpa-version at $TAG"
# Self-test hook: force the expected pin so the stale-pin alert path can be
# exercised with --dry-run.
[ -n "${FDROID_WATCH_PIN_OVERRIDE:-}" ] && PIN="$FDROID_WATCH_PIN_OVERRIDE"

# --- landed? ---------------------------------------------------------------
if $CURL "$INDEX_API" | grep -q "\"versionName\": \"$NAME\""; then
  echo 0 > "$STATE.fail"
  say "OK: $NAME is published in the F-Droid index; nothing in flight"
  exit 0
fi

# --- open MRs --------------------------------------------------------------
OPEN_JSON=$($CURL "$FD_API/merge_requests?state=opened&search=Anti-Vocale&per_page=20") \
  || check_fail "cannot list open fdroiddata MRs"

ROWS=$(printf '%s' "$OPEN_JSON" | python3 -c "
import sys, json
for m in json.load(sys.stdin):
    print(m['iid'], m['source_project_id'], m['sha'], m['created_at'], m['title'], sep='\t')
")

GOOD_PIN_MR=""; GOOD_PIN_PIPE=""; GOOD_PIN_CREATED=""
STALE_COUNT=0; FAIL_COUNT=0; RUN_COUNT=0; OK_COUNT=0; UNKNOWN_COUNT=0; FETCH_ERRORS=0
NOW=$(date -u +%s)

while IFS=$'\t' read -r IID SPID SHA CREATED TITLE; do
  # Light pre-filter: only fetch recipes for our own MRs. Bot MR titles carry
  # the versionCode ("Update Anti-Vocale to 524"), manual MRs carry the
  # versionName, so match either shape; everything else is skipped.
  case "$TITLE" in
    bot:*Anti-Vocale*|*"$NAME"*) ;;
    *) continue ;;
  esac
  RECIPE=$($CURL "$API_BASE/$SPID/repository/files/$RECIPE_ENC/raw?ref=$SHA" 2>/dev/null) || RECIPE=""
  if [ -z "$RECIPE" ]; then
    say "MR !$IID: recipe fetch failed; degrading this run"
    FETCH_ERRORS=$((FETCH_ERRORS + 1))
    continue
  fi
  printf '%s' "$RECIPE" | grep -q "versionName: $NAME\$" || continue
  PIPE=$($CURL "$FD_API/merge_requests/$IID/pipelines?per_page=1" \
    | python3 -c "
import sys, json
p = json.load(sys.stdin)
print(p[0]['status'] if p else 'none')
" 2>/dev/null || echo unknown)
  MRPIN=$(printf '%s' "$RECIPE" \
    | grep -o 'sherpa_onnx@[0-9a-f]*' | tail -1 | cut -d@ -f2)
  say "open MR !$IID ($TITLE) carries $NAME: pin ${MRPIN:-unknown} (want $PIN), pipeline $PIPE"
  if [ "$MRPIN" = "$PIN" ]; then
    GOOD_PIN_MR="$IID"
    GOOD_PIN_PIPE="$PIPE"
    GOOD_PIN_CREATED="$CREATED"
    case "$PIPE" in
      success|passed) OK_COUNT=$((OK_COUNT + 1)) ;;
      running|pending|created|none) RUN_COUNT=$((RUN_COUNT + 1)) ;;
      unknown) UNKNOWN_COUNT=$((UNKNOWN_COUNT + 1)) ;;
      *) FAIL_COUNT=$((FAIL_COUNT + 1)) ;;
    esac
  else
    STALE_COUNT=$((STALE_COUNT + 1))
  fi
done <<< "$ROWS"

echo 0 > "$STATE.fail"

# A recipe fetch that failed must not feed the verdicts (a hiccup on the
# only correct-pin MR would otherwise fake a stale-pin alert).
if [ "$FETCH_ERRORS" -gt 0 ]; then
  check_fail "$FETCH_ERRORS recipe fetch(es) failed; verdicts suppressed this run"
fi

# --- verdicts --------------------------------------------------------------
if [ -n "$GOOD_PIN_MR" ]; then
  # A corrected MR exists: classes 1 and 2 cannot fire.
  if [ "$UNKNOWN_COUNT" -gt 0 ]; then
    check_fail "pipeline status unknown for MR !$GOOD_PIN_MR ($NAME)"
  fi
  if [ "$OK_COUNT" -gt 0 ]; then
    AGE_H=$(( (NOW - $(date -u -d "$GOOD_PIN_CREATED" +%s 2>/dev/null || echo "$NOW")) / 3600 ))
    if [ "$AGE_H" -ge 24 ] && ! was_seen "nudged-$GOOD_PIN_MR"; then
      notify "stuck-green" "MR !$GOOD_PIN_MR builds green for $NAME with the correct sherpa pin but has been unmerged for ${AGE_H}h. A short nudge to the F-Droid maintainers would help. https://gitlab.com/fdroid/fdroiddata/-/merge_requests/$GOOD_PIN_MR"
      mark_seen "nudged-$GOOD_PIN_MR"
    else
      say "OK: MR !$GOOD_PIN_MR green for $NAME (awaiting F-Droid review/merge)"
    fi
  else
    say "OK: MR !$GOOD_PIN_MR in flight for $NAME (pipeline: $GOOD_PIN_PIPE)"
  fi
  exit 0
fi

if [ "$STALE_COUNT" -gt 0 ]; then
  if was_seen "stalepin-$TAG"; then
    say "stale-pin condition persists for $NAME (alert already sent)"
  else
    mark_seen "stalepin-$TAG"
    notify "stale-pin" "$NAME: $STALE_COUNT open fdroiddata MR(s) carry the STALE sherpa srclib pin (want $PIN, the pin of the released APK). Their build will fail binary verification. Open the correction MR with the right pin (fdroid-data fork branch flow), as done for 1.15.0 (!55142) and 1.17.0 (!58474)."
  fi
  exit 1
fi

if [ "$FAIL_COUNT" -gt 0 ]; then
  if was_seen "buildfail-$TAG"; then
    say "build-failed condition persists for $NAME (alert already sent)"
  else
    mark_seen "buildfail-$TAG"
    notify "build-failed" "$NAME: every open fdroiddata MR's pipeline failed with no correction in flight. Check https://gitlab.com/fdroid/fdroiddata and the app repo's scripts/check-fdroid-pin.sh output."
  fi
  exit 1
fi

# --- nothing in flight at all (class 4) ------------------------------------
AGE_H=$(( (NOW - $(date -u -d "$PUBLISHED" +%s 2>/dev/null || echo "$NOW")) / 3600 ))
if [ "$AGE_H" -ge 72 ]; then
  if was_seen "nudged-noactivity-$TAG"; then
    say "no open MR for $NAME, already alerted"
  else
    mark_seen "nudged-noactivity-$TAG"
    notify "no-activity" "$NAME ($TAG) released ${AGE_H}h ago but fdroiddata has NO open MR for it: the checkupdates bot may have missed the tag. Verify the release tag and assets, then check fdroiddata."
  fi
else
  say "OK: $NAME released ${AGE_H}h ago, no fdroiddata MR yet (normal in the first hours)"
fi
exit 0
