#!/usr/bin/env python3
"""PreToolUse reminder gate: read the release gotchas BEFORE running release scripts.

Incident class (v1.14.1 AND v1.15.0, both re-committed despite being
documented in the play-store release memory after the first time):
  - release-create.sh invoked bare, dying on `mapfile` under macOS bash 3.2
    (the scripts now self-re-exec under bash 5, but the habit matters for
    every other release script);
  - the GitLab fork push attempted from the Mac, which has NO GitLab SSH key
    and NO token: the only working route is bundle + scp + push via bird.

Prose memory diluted exactly as the manual warns. This gate forces the read:
the FIRST release-script invocation in a rolling hour window is BLOCKED with
the digest below (the deploy-gate pattern); the identical retry passes and
the window stays open, so normal work is never interrupted twice.

Escape: append ` #release-gate-escape` with a stated justification to skip.
"""
import json
import os
import sys
import time

MARKER = "#release-gate-escape"
SCRIPT_TOKENS = (
    "release-create.sh",
    "release-fdroid-references.sh",
    "release-preflight.sh",
    "release-verify.sh",
    "release-fork-bootstrap.sh",
    "release-sanity-campaign.sh",
    "check-fdroid-release.sh",
    "sync-fdroid-mirror.sh",
    "new-fdroid-version.py",
    "check-fdroid-bot-mr.sh",
    "verify-github-workflow-before-recipe-push.sh",
)
STATE_FILE = "/tmp/anti-vocale-release-ops-gate.state"
WINDOW_SECONDS = 3600

DIGEST = """⛔ release-ops gate (first release script of this hour): read before running.

1. BASH 5: release scripts need bash >= 5 (mapfile). release-create.sh and
   device-model-matrix.sh now self-re-exec from /opt/homebrew/bin/bash, but
   if ANY release script dies on mapfile/declare -A, re-run it as
   /opt/homebrew/bin/bash <script>.
2. GITLAB PUSH: this Mac has NO GitLab SSH key and NO token; SSH and HTTPS
   pushes both fail. The only working route is via bird:
     git -C ~/data/repo/personal/fdroid-data bundle create /tmp/rel.bundle <branch>
     scp -q /tmp/rel.bundle bird:/tmp/
     ssh bird 'cd ~/data/repo/personal/fdroid-data && git fetch -q /tmp/rel.bundle <branch>:<branch> && git checkout -q <branch> && git push --force-with-lease origin <branch>'
3. FIRST F-DROID BUILD WITH NEW NATIVE CODE: the recipe needs the app's NDK
   alias (27.0.12077973 is fdroid r27) and the whistle-stub prebuild
   (echo whistleEngineDisabled >> ../gradle.properties); CXX1104 or a
   missing libneedle.a on the buildserver means one of those is missing.
4. FULL HISTORY: the per-release gotcha blocks live in the play-store
   release memory file (project_play_store_release.md). READ THE LATEST
   BLOCK before any release work; two v1.15.0 mistakes were already
   documented there from v1.14.1 and re-happened anyway.

The retry of the same command passes. To skip this gate once:
append ` #release-gate-escape` with a stated justification."""


def window_open() -> bool:
    try:
        mtime = os.path.getmtime(STATE_FILE)
    except OSError:
        return True
    return (time.time() - mtime) >= WINDOW_SECONDS


def main() -> None:
    payload = json.load(sys.stdin)
    if payload.get("tool_name") != "Bash":
        return
    cmd = (payload.get("tool_input") or {}).get("command", "")
    if not any(t in cmd for t in SCRIPT_TOKENS):
        return
    if MARKER in cmd:
        return
    if not window_open():
        return
    decision = {
        "hookSpecificOutput": {
            "hookEventName": "PreToolUse",
            "permissionDecision": "deny",
            "permissionDecisionReason": DIGEST,
        }
    }
    print(json.dumps(decision))
    with open(STATE_FILE, "w", encoding="utf-8") as fh:
        fh.write(str(time.time()))


if __name__ == "__main__":
    main()
