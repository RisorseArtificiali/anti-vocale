#!/usr/bin/env bash
# F-Droid checkupdates-bot MR check (runbook Step 7b).
#
# The Anti-Vocale recipe ships AutoUpdateMode: Version, so F-Droid's
# checkupdates bot opens the update MR on fdroiddata by itself once it sees
# the GitHub release. Our release flow ends at the fork push + reference
# pipeline; this script verifies the bot did its part, so nobody opens a
# duplicate manual MR out of a stale habit (2026-09-23 correction).
#
# Usage: scripts/check-fdroid-bot-mr.sh <CurrentVersionCode>
#   e.g. scripts/check-fdroid-bot-mr.sh 454
# Exit 0: the bot MR exists (prints state and URL; merged counts).
# Exit 1: no MR yet (normal for the first hours after publishing).

set -euo pipefail

CODE="${1:?usage: $0 <CurrentVersionCode (e.g. 454)>}"

# shellcheck disable=SC2016
DATA=$(curl -s --max-time 30 \
  "https://gitlab.com/api/v4/projects/fdroid%2Ffdroiddata/merge_requests?search=Anti-Vocale&per_page=50")

COUNT=$(printf '%s' "$DATA" | jq -r 'length')
if [ "${COUNT:-0}" -eq 0 ] 2>/dev/null; then
  echo "ERROR: GitLab API returned no usable payload:" >&2
  printf '%s' "$DATA" | head -c 300 >&2
  exit 2
fi

# The bot titles MRs "bot: Update Anti-Vocale to <CurrentVersionCode>".
# Match on the exact code to avoid hitting the previous release's MR.
MATCH=$(printf '%s' "$DATA" | jq -r --arg code "$CODE" '
  .[] | select(.title == ("bot: Update Anti-Vocale to " + $code)) |
  "!" + (.iid | tostring) + " " + .state + " " + .web_url')

if [ -z "$MATCH" ]; then
  echo "No bot MR for code ${CODE} yet (normal in the first hours after publishing)."
  echo "Re-run later: scripts/check-fdroid-bot-mr.sh ${CODE}"
  exit 1
fi

echo "$MATCH"
case "$MATCH" in
  *" merged "*) echo "Already merged: the update is in fdroiddata master." ;;
  *" opened "*) echo "Open: awaiting F-Droid review." ;;
  *) echo "Note: state is neither opened nor merged; check the URL." ;;
esac
