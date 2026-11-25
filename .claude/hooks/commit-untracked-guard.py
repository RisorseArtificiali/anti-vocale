#!/usr/bin/env python3
"""PreToolUse guard: a commit must not name or reference untracked files.

Incident class (2026-11-25): commit 1325b840 migrated eleven call sites
onto a helper whose DEFINITION file sat untracked in the working tree and
was never `git add`ed. The push shipped: origin main did not compile for
two hours (repaired in 9922cb74). A green working tree proves nothing
about the commit; the commit message named the file, and every staged
file referenced it, yet nothing compared the two sets.

Two checks, both mechanical:
1. MESSAGE: if the commit message (the -m text or -F file inside the
   command) contains the basename of an untracked code/data file, the
   commit describes a file it does not contain -> block.
2. STAGED DIFF: if the staged diff (`git diff --cached`) references the
   basename of an untracked file (import, string, path), the staged code
   depends on a file the commit does not contain -> block.

Escape: `#untracked-escape` appended to the command with a stated
justification (the repo's --no-verify bypass also skips this hook, per
the standing gates policy).
"""
import json
import os
import re
import subprocess
import sys

MARKER = "#untracked-escape"
# Extensions whose untracked files are plausibly part of a commit.
CODE_SUFFIXES = (
    ".kt", ".kts", ".java", ".xml", ".json", ".csv", ".py", ".sh",
    ".yml", ".yaml", ".toml", ".gradle", ".pro", ".md",
)


def untracked_basenames() -> list:
    out = subprocess.run(
        ["git", "status", "--porcelain=v1", "--untracked-files=all"],
        capture_output=True, text=True, timeout=10,
    ).stdout
    names = []
    for line in out.splitlines():
        if line.startswith("?? "):
            path = line[3:].strip().strip('"')
            base = os.path.basename(path)
            if base.lower().endswith(CODE_SUFFIXES):
                names.append((path, os.path.splitext(base)[0]))
    return names


def main() -> None:
    payload = json.load(sys.stdin)
    if payload.get("tool_name") != "Bash":
        return
    command = payload.get("tool_input", {}).get("command", "")
    if MARKER in command:
        return
    if "git commit" not in command:
        return

    untracked = untracked_basenames()
    if not untracked:
        return

    # The message sources: literal -m text (possibly heredoc inside the
    # command) and any -F file.
    message_sources = [command]
    for match in re.finditer(r"-F\s+(\S+)", command):
        try:
            with open(match.group(1), encoding="utf-8") as fh:
                message_sources.append(fh.read())
        except OSError:
            pass

    staged = subprocess.run(
        ["git", "diff", "--cached"], capture_output=True, text=True, timeout=10
    ).stdout

    hits = []
    for path, base in untracked:
        if not base:
            continue
        if any(base in src for src in message_sources):
            hits.append((path, "the commit MESSAGE names it"))
        elif base in staged:
            hits.append((path, "the STAGED diff references it"))
    if not hits:
        return

    listing = "\n".join(f"  - {path} ({why})" for path, why in hits)
    sys.stderr.write(
        "commit-untracked-guard: the commit names or references untracked "
        "files it does not contain (the 1325b840 class: pushed main did not "
        "compile):\n" + listing + "\n"
        "git add the files if they belong in this commit, or justify with "
        "the #untracked-escape marker.\n")
    sys.exit(2)


if __name__ == "__main__":
    main()
