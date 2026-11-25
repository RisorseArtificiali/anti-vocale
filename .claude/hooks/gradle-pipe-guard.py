#!/usr/bin/env python3
"""PreToolUse guard: piping gradle to tail/head hides the build's exit code.

Incident class (2026-11-25, twice in one session): gradle battery
commands piped to `tail -N` reported tail's exit code, so BUILD FAILED
runs surfaced as "completed (exit 0)" and a failed battery was read as
green while its test reports rotted unexamined.

Block any Bash command that runs gradlew and pipes to tail/head without
pipefail. Fixes: prefix `set -o pipefail;`, or redirect to a file and
grep the file, or read PIPESTATUS.

Escape: `#pipe-escape` appended to the command (for interactive-style
invocations where only the last lines are wanted and the exit code is
deliberately ignored).
"""
import json
import re
import sys

MARKER = "#pipe-escape"


def main() -> None:
    payload = json.load(sys.stdin)
    if payload.get("tool_name") != "Bash":
        return
    command = payload.get("tool_input", {}).get("command", "")
    if MARKER in command:
        return
    if "gradlew" not in command:
        return
    if "pipefail" in command or "PIPESTATUS" in command:
        return
    if re.search(r"\|\s*(tail|head)\b", command) is None:
        return
    sys.stderr.write(
        "gradle-pipe-guard: `gradlew ... | tail/head` reports the pipe's "
        "exit code, not gradle's (2026-11-25: two failed batteries read as "
        "exit 0). Use `set -o pipefail; <command>`, or redirect to a file "
        "and grep it, or check ${PIPESTATUS[0]}.\n")
    sys.exit(2)


if __name__ == "__main__":
    main()
