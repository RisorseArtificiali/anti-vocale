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
#   2. a pin-CORRECT open MR for the latest release has a failed
#      pipeline -> ALERT (once/tag)
#   3. a green, pin-correct MR has sat unmerged for >24h   -> ALERT (once/MR)
#   4. no fdroiddata activity 72h after the release (bot missed the tag,
#      the 1.14.0 blind spot)                             -> ALERT (once/tag)
#   5. the watch's own checks fail 3 times in a row (infra) -> ALERT
#
# Transient API hiccups must never produce a false alert: a failed recipe or
# pipeline fetch degrades the run (log + exit 2) instead of feeding the
# verdicts, and repeated degradation trips class 5 through check_fail. The
# consecutive-failure counter resets on ANY definitive verdict, alert
# verdicts included: an alerting run is a healthy run (its checks all
# succeeded), and a later infra streak must not span it.
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

# epoch_of <iso8601>: portable epoch seconds (GNU date -d is Linux-only and
# this script must also self-test on the Mac). A malformed or empty stamp
# answers NOW (age 0) so a bad timestamp SUPPRESSES alerts instead of
# manufacturing a ~56-year age (review rounds 1-3: the old 0 fallback fired
# the stuck-green and no-activity classes on one bad created_at).
epoch_of() {
  python3 -c "
import sys, datetime
try:
    s = sys.argv[1]
    dt = datetime.datetime.fromisoformat(s.replace('Z', '+00:00'))
    assert abs(dt.year) < 3000
    print(int(dt.timestamp()))
except Exception:
    print(int(datetime.datetime.now().timestamp()))
" "$1" 2>/dev/null || date -u +%s
}

# notify <kind> <text>: Telegram the maintainer (or print in dry-run).
notify() {
  say "ALERT[$1]: $2"
  if [ "$DRY_RUN" = 1 ]; then say "(dry-run: not sent)"; return 0; fi
  if [ ! -f "$HOME/.ccgram/.env" ]; then say "(no ~/.ccgram/.env; alert not sent)"; return 1; fi
  set -a; . "$HOME/.ccgram/.env"; set +a
  if ! $CURL -X POST "https://api.telegram.org/bot${TELEGRAM_BOT_TOKEN:-}/sendMessage" \
      --data-urlencode "chat_id=$CHAT_ID" \
      --data-urlencode "text=fdroid-watch ($1): $2" >/dev/null; then
    say "(telegram send failed)"
    return 1
  fi
  return 0
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
# Validation happens IN the python filter: nothing is printed (so nothing is
# eval'd) unless the tag matches the expected release-tag shape.
eval "$(printf '%s' "$REL" | python3 -c '
import sys, json, re
r = json.load(sys.stdin)
tag = r["tag_name"]
if not re.match(r"^v[0-9][0-9A-Za-z.\-]*$", tag):
    sys.exit(3)
if not re.match(r"^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z$", r["published_at"]):
    sys.exit(3)
print("PUBLISHED=" + r["published_at"])
print("TAG=" + tag)
')" || check_fail "bad github releases/latest payload"
[ -n "${TAG:-}" ] || check_fail "github payload did not yield a valid tag"
NAME="${TAG#v}"
PIN=$($CURL "https://raw.githubusercontent.com/RisorseArtificiali/anti-vocale/$TAG/.sherpa-version" \
  | grep '^srclib-commit ' | awk '{print $2}')
[ -n "$PIN" ] || check_fail "no srclib-commit in .sherpa-version at $TAG"
# Self-test hook: force the expected pin so the alert paths can be exercised
# with --dry-run.
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

# Counters classify EVERY MR carrying the release versionName, good pin or
# not, so a failed correct-pin MR is visible (failure class 2) and a green
# correct-pin MR is tracked even when a newer MR follows it (class 3).
GOOD_ANY=""; GREEN_MR=""; GREEN_CREATED=""
GOOD_OK=0; GOOD_RUN=0; GOOD_FAIL=0; GOOD_UNKNOWN=0; STALE_COUNT=0; FETCH_ERRORS=0
NOW=$(date -u +%s)

while IFS=$'\t' read -r IID SPID SHA CREATED TITLE; do
  # search=Anti-Vocale already scopes the list to this app; admit every MR.
  case "$TITLE" in
    *Anti-Vocale*) ;;
    *) continue ;;
  esac
  RECIPE=$($CURL "$API_BASE/$SPID/repository/files/$RECIPE_ENC/raw?ref=$SHA" 2>/dev/null) || RECIPE=""
  if [ -z "$RECIPE" ]; then
    say "MR !$IID: recipe fetch failed; degrading this run"
    FETCH_ERRORS=$((FETCH_ERRORS + 1))
    continue
  fi
  printf '%s' "$RECIPE" | grep -q "versionName: $NAME\$" || continue
  # The pin that matters is the NEWEST Build block's: everything after the
  # LAST versionName line for this release. (The file carries historical
  # pins in older blocks; matching anywhere would misread them.)
  NEWEST_TAIL=$(printf '%s' "$RECIPE" | python3 -c "
import sys
t = sys.stdin.read()
key = 'versionName: $NAME'
i = t.rfind(key)
print(t[i:] if i >= 0 else '')
")
  MRPIN=$(printf '%s' "$NEWEST_TAIL" \
    | grep -o 'sherpa_onnx@[0-9a-f]*' | tail -1 | cut -d@ -f2)
  PIPE=$($CURL "$FD_API/merge_requests/$IID/pipelines?per_page=1" \
    | python3 -c "
import sys, json
p = json.load(sys.stdin)
print(p[0]['status'] if p else 'none')
" 2>/dev/null || echo unknown)
  say "open MR !$IID ($TITLE) carries $NAME: pin ${MRPIN:-unknown} (want $PIN), pipeline $PIPE"
  if [ "$MRPIN" = "$PIN" ]; then
    GOOD_ANY=1
    if { [ "$PIPE" = success ] || [ "$PIPE" = passed ]; }; then
      # Keep the OLDEST green MR (the API returns newest-first; review
      # finding 3: tracking the first SEEN computed the stuck-green age
      # from the newest MR, delaying the >24h nudge).
      if [ -z "$GREEN_MR" ] || [ "$(epoch_of "$CREATED")" -lt "$(epoch_of "$GREEN_CREATED")" ]; then
        GREEN_MR="$IID"; GREEN_CREATED="$CREATED"
      fi
    fi
    case "$PIPE" in
      success|passed) GOOD_OK=$((GOOD_OK + 1)) ;;
      # canceled/skipped are superseded pipelines, not failures (review
      # round 2: a canceled superseded-MR pipeline is routine, never a
      # build-failed alert); they ride the in-flight arm.
      running|pending|created|canceled|skipped|none) GOOD_RUN=$((GOOD_RUN + 1)) ;;
      unknown) GOOD_UNKNOWN=$((GOOD_UNKNOWN + 1)) ;;
      *) GOOD_FAIL=$((GOOD_FAIL + 1)) ;;
    esac
  else
    STALE_COUNT=$((STALE_COUNT + 1))
  fi
done <<< "$ROWS"

# A recipe fetch that failed must not feed the verdicts (a hiccup on the
# only correct-pin MR would otherwise fake a stale-pin alert). Note the
# failure counter is NOT reset here: degradation must be able to reach 3.
if [ "$FETCH_ERRORS" -gt 0 ]; then
  check_fail "$FETCH_ERRORS recipe fetch(es) failed; verdicts suppressed this run"
fi

# Every exit below reached a definitive verdict on healthy checks: the
# consecutive-failure counter resets on ALL of them (review finding 4: the
# alert exits used to skip the reset, so a later infra streak could span a
# healthy alerting run).
reset_and_exit0() { echo 0 > "$STATE.fail"; exit 0; }
alerted_reset_and_exit1() { echo 0 > "$STATE.fail"; exit 1; }

# alert_once <dedup-key> <kind> <text>: dedup'd notify whose marker is
# recorded ONLY when delivery succeeded (review finding 2: a send failure
# used to consume the once-per-tag slot and permanently swallow the alert).
alert_once() {
  local key="$1" kind="$2" text="$3"
  if was_seen "$key"; then
    say "$kind condition persists (alert already sent)"
    return 0
  fi
  notify "$kind" "$text" && mark_seen "$key"
}

# --- verdicts (classes evaluated 1, infra-unknown, 3, 2, in-flight, 4;
#     class 3 deliberately runs before class 2's exit: both can fire) -----------------------------------
# Class 1 FIRST and guarded: stale-pin MRs exist AND no open MR carries the
# correct pin (GOOD_ANY empty). The correction flow (stale bot MR + the
# maintainer's green correction MR) says OK, not alert (review rounds 2-4:
# the guard kept being lost or the branch order inverted, routing the
# stale-only case into the build-failed wording and consuming its marker).
if [ "$STALE_COUNT" -gt 0 ] && [ "$GOOD_ANY" = "" ]; then
  alert_once "stalepin-$TAG" "stale-pin" "$NAME: $STALE_COUNT open fdroiddata MR(s) carry the STALE sherpa srclib pin (want $PIN, the pin of the released APK). Their build will fail binary verification. Open the correction MR with the right pin (fdroid-data fork branch flow), as done for 1.15.0 (!55142) and 1.17.0 (!58474)."
  alerted_reset_and_exit1
fi

# A correct-pin MR exists: unknown pipeline status is infra, not a verdict.
if [ "$GOOD_UNKNOWN" -gt 0 ]; then
  check_fail "pipeline status unknown for a correct-pin MR of $NAME"
fi

# Class 3 BEFORE class 2's exit: one permanently failed correct-pin MR must
# not suppress the unmerged-green nudge (review round 2c); both can fire.
if [ -n "$GOOD_ANY" ] && [ "$GOOD_OK" -gt 0 ]; then
  AGE_H=$(( (NOW - $(epoch_of "$GREEN_CREATED")) / 3600 ))
  if [ "$AGE_H" -ge 24 ] && ! was_seen "nudged-$GREEN_MR"; then
    notify "stuck-green" "MR !$GREEN_MR builds green for $NAME with the correct sherpa pin but has been unmerged for ${AGE_H}h. A short nudge to the F-Droid maintainers would help. https://gitlab.com/fdroid/fdroiddata/-/merge_requests/$GREEN_MR" \
      && mark_seen "nudged-$GREEN_MR"
  else
    say "OK: MR !$GREEN_MR green for $NAME (awaiting F-Droid review/merge)"
  fi
fi

# Class 2 LAST, gated on an ACTUAL failed pipeline of a carrying MR with a
# fetched status (GOOD_FAIL only; the old stale-only disjunct alerted
# 'pipeline FAILED' on green/running stale MRs and consumed the marker).
if [ "$GOOD_FAIL" -gt 0 ]; then
  alert_once "buildfail-$TAG" "build-failed" "$NAME: an open fdroiddata MR's pipeline FAILED (correct-pin MRs: $GOOD_OK ok / $GOOD_RUN running / $GOOD_FAIL failed; stale-pin MRs: $STALE_COUNT). Check https://gitlab.com/fdroid/fdroiddata and scripts/check-fdroid-pin.sh output."
  alerted_reset_and_exit1
fi

if [ -n "$GOOD_ANY" ]; then
  say "OK: correct-pin MRs in flight for $NAME ($GOOD_RUN running)"
  reset_and_exit0
fi

# --- nothing in flight at all (class 4) ------------------------------------
AGE_H=$(( (NOW - $(epoch_of "$PUBLISHED")) / 3600 ))
if [ "$AGE_H" -ge 72 ]; then
  alert_once "nudged-noactivity-$TAG" "no-activity" "$NAME ($TAG) released ${AGE_H}h ago but fdroiddata has NO open MR for it: the checkupdates bot may have missed the tag. Verify the release tag and assets, then check fdroiddata."
else
  say "OK: $NAME released ${AGE_H}h ago, no fdroiddata MR yet (normal in the first hours)"
fi
reset_and_exit0
