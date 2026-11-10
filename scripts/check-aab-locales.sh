#!/usr/bin/env bash
# AAB locale gate (TASK-976): assert the bundle carries no language Play's
# translation validation would reject. Run on the BUILT .aab, wired into
# android-release.yml before the Play upload so the "te" class fails
# minutes after the bundle build instead of at the store upload
# (v1.16.0's first publish: "One or more of the languages are not
# supported for translation: te").
#
# Usage: scripts/check-aab-locales.sh <app.aab> [res-dir]
#   res-dir defaults to app/src/main/res
# Env:
#   BUNDLETOOL_JAR  use this jar instead of downloading (CI caches it)
#   BUNDLETOOL_URL  override the pinned download URL
#
# Exit 0 green; 1 unexpected locales; 2 usage/tool failure.

set -euo pipefail

AAB="${1:-}"
RES_DIR="${2:-$(cd "$(dirname "$0")/.." && pwd)/app/src/main/res}"
HERE="$(cd "$(dirname "$0")" && pwd)"

[ -n "$AAB" ] && [ -f "$AAB" ] || { echo "usage: $0 <app.aab> [res-dir]" >&2; exit 2; }
[ -d "$RES_DIR" ] || { echo "FAIL: res dir not found: $RES_DIR" >&2; exit 2; }

BUNDLETOOL_VERSION="1.17.0"
BUNDLETOOL_URL="${BUNDLETOOL_URL:-https://github.com/google/bundletool/releases/download/${BUNDLETOOL_VERSION}/bundletool-all-${BUNDLETOOL_VERSION}.jar}"
CACHE_DIR="${BUNDLETOOL_CACHE_DIR:-${XDG_CACHE_HOME:-$HOME/.cache}/anti-vocale}"
JAR="${BUNDLETOOL_JAR:-$CACHE_DIR/bundletool-$BUNDLETOOL_VERSION.jar}"

if [ ! -f "$JAR" ]; then
  echo "== fetching bundletool $BUNDLETOOL_VERSION (cached at $JAR)"
  mkdir -p "$CACHE_DIR"
  # Atomic: a partial jar left in the cache would be skipped forever and
  # surface later as a misleading dump failure.
  if ! curl -fsSL --max-time 180 "$BUNDLETOOL_URL" -o "$JAR.part"; then
    rm -f "$JAR.part"
    echo "FAIL: the bundletool download failed (network or timeout); nothing cached" >&2
    exit 2
  fi #not-crw: pinned binary tool download from its official release, not a page fetch
  mv "$JAR.part" "$JAR"
fi

echo "== dumping the AAB resource table"
DUMP="$(mktemp /tmp/aab-res-dump.XXXXXX)"
trap 'rm -f "$DUMP"' EXIT
java -jar "$JAR" dump resources --bundle="$AAB" >"$DUMP" 2>/dev/null || {
  echo "FAIL: bundletool dump failed on $AAB" >&2
  exit 2
}

python3 "$HERE/aab_locale_check.py" --check "$DUMP" "$RES_DIR"
