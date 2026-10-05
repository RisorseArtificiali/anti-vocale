#!/usr/bin/env bash
# TASK-753 guard: the compose-foundation version must resolve IDENTICALLY on
# the compile and the runtime classpath. A split there means call sites were
# compiled against one FlowRow/experimental signature and packaged against
# another: NoSuchMethodError at runtime, nothing fails at build time.
#
# Usage: scripts/check-compose-parity.sh [flavor]   (default: playStore)
# Exit 0 = parity; exit 1 = split (or resolution failure), with both versions
# printed. Run it in the release preflight and after any dependency edit.

set -euo pipefail
FLAVOR="${1:-playStore}"
cd "$(dirname "$0")/.."

# JAVA_HOME pins the toolchain on the Mac (PATH default breaks the wrapper).
if [[ -x "$HOME/.sdkman/candidates/java/21.0.10-tem/bin/java" ]]; then
    export JAVA_HOME="$HOME/.sdkman/candidates/java/21.0.10-tem"
fi

resolve() {
    # dependencyInsight's FIRST line names the SELECTED version (the full
    # tree also lists requested/transitive versions, which are not what
    # lands in the artifact).
    ./gradlew -q :app:dependencyInsight --configuration "${FLAVOR}Debug${1}Classpath" \
        --dependency androidx.compose.foundation:foundation 2>/dev/null \
        | grep -m1 -oE 'androidx\.compose\.foundation:foundation:[0-9.]+' \
        | sed 's/.*://'
}

COMPILE=$(resolve Compile)
RUNTIME=$(resolve Runtime)
echo "compile foundation: ${COMPILE:-unresolved}"
echo "runtime foundation: ${RUNTIME:-unresolved}"

if [[ -z "$COMPILE" || -z "$RUNTIME" || "$COMPILE" != "$RUNTIME" ]]; then
    echo "FAIL: compose-foundation version split (TASK-753 class)" >&2
    exit 1
fi
echo "parity OK"
