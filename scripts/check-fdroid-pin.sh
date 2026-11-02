#!/usr/bin/env bash
# Check that the LIVE fdroiddata recipe pins the sherpa_onnx commit our
# .sherpa-version marker carries (GH #175 class: the recipe drifted for
# three months and the 1.15.0 build failed on a Long/Int type change).
#
# Checks, in order:
#   1. fdroiddata master: the srclib pin of the NEWEST build block
#   2. the open checkupdates-bot MR (if any): the pins of its ADDED blocks
#      (the 1.15.0 failure lived there, not on master)
# Exits 1 with a message on any mismatch. Network required (read-only,
# anonymous); run from anywhere.
set -uo pipefail

REPO_DIR="$(cd "$(dirname "$0")/.." && pwd)"
WANT=$(grep '^srclib-commit ' "$REPO_DIR/.sherpa-version" | awk '{print $2}')
if [ -z "$WANT" ]; then
    echo "FAIL: no srclib-commit in .sherpa-version"
    exit 1
fi

RAW="https://gitlab.com/fdroid/fdroiddata/-/raw/master/metadata/com.antivocale.app.yml"
API="https://gitlab.com/api/v4/projects/fdroid%2Ffdroiddata"
failures=0

# 1. Master: the last sherpa_onnx@ line in the file is the newest block's pin
#    (the template the bot copies for the next version).
LIVE=$(curl -sf "$RAW" | grep -o 'sherpa_onnx@[0-9a-f]*' | tail -1 | cut -d@ -f2)
if [ -z "$LIVE" ]; then
    echo "FAIL: could not fetch or parse $RAW"
    exit 1
fi
if [ "$LIVE" != "$WANT" ]; then
    echo "FAIL: fdroiddata master newest pin is $LIVE, want $WANT (from .sherpa-version)"
    echo "      fix: MR to fdroiddata updating the newest block's srclibs line"
    failures=$((failures+1))
else
    echo "OK: fdroiddata master newest pin matches ($WANT)"
fi

# 2. Open MRs (bot or ours): their ADDED blocks build the NEXT release; a
#    stale pin there fails the build even when master is fixed (the GH #175
#    case lived in the checkupdates-bot MR, not on master).
python3 - "$WANT" <<'PYCHECK' || bot_bad=1
import json, sys, urllib.request

want = sys.argv[1]
api = "https://gitlab.com/api/v4/projects/fdroid%2Ffdroiddata"
mrs = json.load(urllib.request.urlopen(api + "/merge_requests?search=Anti-Vocale&state=opened"))
if not mrs:
    print("OK: no open fdroiddata MR mentioning Anti-Vocale")
    sys.exit(0)
bad = 0
for mr in mrs:
    iid = mr["iid"]
    who = mr["author"]["username"]
    try:
        diffs = json.load(urllib.request.urlopen(api + "/merge_requests/%d/diffs" % iid))
    except Exception:
        continue
    pins = set()
    for f in diffs:
        for line in f.get("diff", "").split(chr(10)):
            if line.startswith("+") and "sherpa_onnx@" in line:
                pins.add(line.split("sherpa_onnx@")[1].split()[0])
    for pin in sorted(pins):
        if pin != want:
            print("FAIL: open MR !%d (by %s) adds sherpa_onnx@%s, want %s" % (iid, who, pin, want))
            bad = 1
if not bad:
    print("OK: all open fdroiddata MRs pin %s" % want)
sys.exit(bad)
PYCHECK
if [ "${bot_bad:-0}" -ne 0 ]; then
    echo "FAIL: see the MR findings above"
    failures=$((failures+1))
fi

exit $((failures > 0))
