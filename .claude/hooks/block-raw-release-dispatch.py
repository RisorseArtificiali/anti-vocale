#!/usr/bin/env python3
"""PreToolUse guard: release workflow dispatches go through the repo's scripts.

Incident class (2026-09-22/23, v1.13.1 night): hand-typed
`gh workflow run android-release.yml ...` invocations. Two wrong shapes were
dispatched in one session: the pre-3e6a27d7 track dispatch (twin-run risk) and
a publish-only references dispatch without `-f tag` (the attach step skipped,
and ~70 minutes of signed reference APKs were built and then discarded).

The owning script is scripts/release-fdroid-references.sh (prepare/dispatch/
finalize modes; it gates the checker, the mirror sync and the stale-asset
cleanup around the dispatch). Its own internal `gh` call never passes through
a Bash tool session, so blocking direct invocations here does not affect it.

Escape: append the marker ` #release-script-escape` to the command together
with a stated justification; the marker records the decision in the transcript.
"""
import json
import sys

MARKER = "#release-script-escape"
PATTERNS = (
    "gh workflow run android-release.yml",
)


def main() -> None:
    payload = json.load(sys.stdin)
    if payload.get("tool_name") != "Bash":
        return
    cmd = (payload.get("tool_input") or {}).get("command", "")
    if not any(p in cmd for p in PATTERNS):
        return
    if MARKER in cmd:
        return
    print(
        "⛔ raw-release-dispatch: android-release.yml is dispatched ONLY through "
        "scripts/release-fdroid-references.sh (prepare/dispatch/finalize), which "
        "gates the checker, the mirror sync and the stale-asset cleanup around it. "
        "Hand-typed dispatch shapes have already caused the twin-run risk and a "
        "70-minute signed-references build discarded for a skipped attach. If this "
        "genuinely needs raw HTTP, re-run the exact same command with the marker "
        "` #release-script-escape` appended: it changes nothing and records the "
        "justified decision.",
        file=sys.stderr,
    )
    sys.exit(2)


if __name__ == "__main__":
    main()
