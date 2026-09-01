#!/usr/bin/env bash
# Post-release verification: run after the signing job (and any re-dispatch)
# completes. Checks the F-Droid-facing artifacts and prints the manual Play
# Console checklist, so nothing user-visible is left in a half-published state.
#
# Usage: scripts/release-verify.sh v1.10.0
set -uo pipefail

TAG="${1:?usage: release-verify.sh <tag> (e.g. v1.10.0)}"
REPO="RisorseArtificiali/anti-vocale"
failures=0
ok()   { echo "OK   $*"; }
fail() { echo "FAIL $*"; failures=$((failures+1)); }

for abi in armeabi-v7a arm64-v8a x86_64; do
  url="https://github.com/$REPO/releases/download/$TAG/app-fdroid-$abi-release.apk"
  code=$(curl -sIL -o /dev/null -w "%{http_code}" "$url")
  [ "$code" = "200" ] && ok "$abi signed reference resolves (200)" || fail "$abi signed reference HTTP $code: $url"
done

echo
echo "Release run states (latest three):"
gh run list --repo "$REPO" --limit 3 --json databaseId,event,status,conclusion,displayTitle \
  --jq '.[] | "  \(.databaseId) \(.event) \(.conclusion // .status) \(.displayTitle[0:60])"' 2>/dev/null \
  || echo "  (gh unavailable)"

echo
echo "Manual Play Console checklist (only you can do these):"
echo "  [ ] What's new: paste the <=490-char texts from docs/play-store/release-notes.xml"
echo "      (latest version section, both locales) if the upload predates a notes fix"
echo "  [ ] Advertising ID declaration (Policy > App content): answer NO (we ship no"
echo "      AD_ID permission by design; the console warning about zeroed IDs is expected)"
echo "  [ ] Native debug symbols: optional; upload a symbols zip only if native crash"
echo "      analysis is needed (prebuilt sherpa libs are stripped, value is limited)"
echo "  [ ] Promote internal -> production when satisfied; review approval is Google's"

echo
[ "$failures" -eq 0 ] && echo "VERIFY PASS ($TAG)" || echo "VERIFY FAIL: $failures signed reference(s) missing ($TAG)"
exit "$([ "$failures" -eq 0 ] && echo 0 || echo 1)"
