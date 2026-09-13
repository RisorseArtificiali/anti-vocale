#!/usr/bin/env python3
"""CI-side twin of .claude/hooks/check-strings-escapes.py.

The PreToolUse hook only sees Claude-tool edits; a human commit, a
translation-platform sync, or any other tool can still land an
unescaped apostrophe in res/values*/strings.xml and fail every
subsequent mergeResources with the misleading "Invalid unicode escape
sequence" (incident 2026-09-13, TASK-506). This script closes every
path: run it in CI (or a pre-commit hook) over the working tree.

Exit 0 = clean; exit 1 = violations listed on stdout.
"""
import re
import sys
from pathlib import Path

STRING_RE = re.compile(r"<string\b[^>]*>(.*?)</string>", re.S)


def unescaped_apostrophes(path: Path):
    findings = []
    for m in STRING_RE.finditer(path.read_text(encoding="utf-8")):
        body = m.group(1)
        if body.startswith('"') and body.endswith('"') and len(body) >= 2:
            continue
        for i, ch in enumerate(body):
            if ch == "'" and (i == 0 or body[i - 1] != "\\"):
                findings.append((path, body[max(0, i - 25):i + 25].strip()))
                break
    return findings


def main() -> int:
    bad = []
    for path in Path("app/src/main/res").glob("values*/strings.xml"):
        bad.extend(unescaped_apostrophes(path))
    if bad:
        for path, ctx in bad:
            print(f"{path}: ...{ctx}...")
        print(
            f"{len(bad)} unescaped apostrophe(s); escape them (\\') or wrap "
            "the whole string body in double quotes."
        )
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
