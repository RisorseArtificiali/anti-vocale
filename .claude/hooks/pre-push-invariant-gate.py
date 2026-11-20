#!/usr/bin/env python3
"""PreToolUse gate: run the cheap i18n/asset invariant checks BEFORE git push.

Incident classes (2026-11-19/20, six red CI runs, four preventable here):
  1. strings.xml changed without regenerating the translation package
     (run 44612152837) - check-translation-package.sh catches it in <2s;
  2. the regeneration done but NOT COMMITTED (run 44797335318: the commit
     staged app/src/main only, the CSV stayed in the working tree; the local
     check passed on the TREE while CI checks the COMMIT) - the
     uncommitted-drift probe catches it;
  3. docs/play-store/release-notes.xml edited without the byte-copy in
     app/src/main/assets/release-notes.xml (runs 44813842362, 44815405448:
     ReleaseNotesTest pins the copy; the test name is the runbook) - the
     cmp probe catches it;
  4. model-size/format drift (check-model-sizes.sh, the TASK-948 class).

Fires only when the push command is a git push to origin AND the repo has
relevant drift: the checks themselves decide relevance (they no-op green in
seconds on untouched trees). The uncommitted-drift probe lists the offending
paths so the fix is obvious. Not covered: the await-timeout flake family
(pre-existing, rerun-green, documented - a local gate cannot see CI runner
slowness).

Escape: append ` #invariant-gate-escape` with a stated justification.
"""
import json
import os
import subprocess
import sys

MARKER = "#invariant-gate-escape"
REPO = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

CHECKS = [
    ("check-translation-package.sh", "strings.xml vs the volunteer package"),
    ("check-model-sizes.sh", "model size/count format contract"),
]

# The byte-copy invariant ReleaseNotesTest pins (class 3): one cmp.
NOTES_DOCS = os.path.join(REPO, "docs/play-store/release-notes.xml")
NOTES_ASSET = os.path.join(REPO, "app/src/main/assets/release-notes.xml")

# The committed-tree drift probe (class 2): i18n surfaces whose working-tree
# state the local checks read but CI reads from the commit.
DRIFT_PATHS = ["docs/i18n", "app/src/main/res", "app/src/main/assets"]


def sh(cmd: str, cwd: str = REPO) -> subprocess.CompletedProcess:
    return subprocess.run(cmd, shell=True, capture_output=True, text=True, cwd=cwd)


def main() -> None:
    payload = json.load(sys.stdin)
    if payload.get("tool_name") != "Bash":
        return
    cmd = (payload.get("tool_input") or {}).get("command", "")
    if "git push" not in cmd or MARKER in cmd:
        return
    # Only gate pushes from this repo checkout.
    if sh("git rev-parse --show-toplevel").stdout.strip() != REPO:
        return

    failures = []

    # Class 3: the release-notes byte copy.
    if os.path.exists(NOTES_DOCS) and os.path.exists(NOTES_ASSET):
        if sh(f"cmp -s {NOTES_DOCS} {NOTES_ASSET}").returncode != 0:
            failures.append(
                "release-notes byte copy: docs/play-store/release-notes.xml != "
                "app/src/main/assets/release-notes.xml "
                "(cp docs/play-store/release-notes.xml app/src/main/assets/)")

    # Classes 1 and 4: the script gates.
    for script, what in CHECKS:
        r = sh(f"./scripts/{script}")
        if r.returncode != 0:
            tail = (r.stdout + r.stderr).strip().splitlines()
            detail = tail[-1] if tail else "no output"
            failures.append(f"{script} ({what}): {detail}")

    # Class 2: uncommitted i18n drift (the local checks read the tree; CI
    # reads the commit: any diff here means what passed locally is not what
    # ships).
    r = sh("git status --porcelain -- " + " ".join(DRIFT_PATHS))
    if r.stdout.strip():
        paths = "\n".join("  " + line for line in r.stdout.strip().splitlines())
        failures.append(
            "uncommitted i18n/asset drift (CI checks the COMMIT, not the "
            "working tree; commit or stash these before pushing):\n" + paths)

    if not failures:
        return

    reason = (
        "⛔ invariant gate: the push would ship a state the CI gates will "
        "reject (four red runs on 2026-11-19/20 were exactly this class).\n"
        + "\n".join("- " + f for f in failures)
        + "\nFix the above, then retry the same push. To skip once: append "
        " `" + MARKER + "` with a stated justification."
    )
    print(json.dumps({
        "hookSpecificOutput": {
            "hookEventName": "PreToolUse",
            "permissionDecision": "deny",
            "permissionDecisionReason": reason,
        }
    }))


if __name__ == "__main__":
    main()
