#!/usr/bin/env python3
"""PreToolUse hook (Write|Edit): block unescaped apostrophes in Android
string resources.

The failure (2026-09-13, TASK-506): a Turkish translation carrying a bare
apostrophe ("Kopyala'yı") makes mergeResources fail with "Invalid unicode
escape sequence" on EVERY subsequent build, while filtered build commands
(grep -q pipelines) hide the BUILD FAILED. A broken string can then ship
stale APKs through several install attempts before anyone notices.

Rule: inside <string>...</string>, an apostrophe must be escaped (\\') or
the whole body must be wrapped in double quotes (the other legal form).
Only res/values*/strings.xml files are checked.
"""
import json
import re
import sys

PATH_RE = re.compile(r"res/values[^/]*/strings\.xml$")
STRING_RE = re.compile(r"<string\b[^>]*>(.*?)</string>", re.S)


def unescaped_apostrophes(content: str):
    findings = []
    for m in STRING_RE.finditer(content):
        body = m.group(1)
        if body.startswith('"') and body.endswith('"') and len(body) >= 2:
            continue  # double-quoted body: apostrophes are legal as-is
        for i, ch in enumerate(body):
            if ch == "'" and (i == 0 or body[i - 1] != "\\"):
                context = body[max(0, i - 25):i + 25].strip()
                findings.append(context)
                break  # one finding per string is enough
    return findings


def main() -> int:
    try:
        payload = json.load(sys.stdin)
        tool = payload.get("tool_name", "")
        tip = payload.get("tool_input", {})
        path = tip.get("file_path", "") or tip.get("notebook_path", "")
        content = tip.get("content") or tip.get("new_string") or ""
    except Exception:
        return 0  # never block on a malformed hook payload

    if tool not in ("Write", "Edit") or not path:
        return 0
    if not PATH_RE.search(path):
        return 0

    # For Edit, new_string is a FRAGMENT: the legal whole-body double-quote
    # form lives outside the fragment, so scanning it alone denies legal
    # edits. Reconstruct the post-edit file instead (replace_all honored),
    # and deny only when the EDIT INTRODUCES a new apostrophe: a remediation
    # edit that still leaves other pre-existing ones must pass, or the hook
    # would block its own fix (code review: the previous shape denied every
    # legitimate remediation path except whole-file Write).
    scan = content
    introduced = None
    if tool == "Edit":
        try:
            with open(path, encoding="utf-8") as f:
                disk = f.read()
            old_f = tip.get("old_string")
            if old_f is not None and old_f in disk:
                count = -1 if tip.get("replace_all") else 1
                scan = disk.replace(old_f, content) if count == -1 else disk.replace(old_f, content, 1)
                introduced = len(unescaped_apostrophes(scan)) - len(unescaped_apostrophes(disk))
            else:
                # unanchored edit (append/no old_string match): scan the
                # fragment as the delta against the disk state
                scan = disk
        except OSError:
            scan = content

    findings = unescaped_apostrophes(scan)
    if introduced is not None and introduced <= 0:
        findings = []  # the edit did not add any new apostrophe: allow
    if findings:
        examples = "; ".join(f"...{f}..." for f in findings[:3])
        msg = (
            "unescaped-apostrophe: Android string resources must escape "
            "apostrophes (\\') or wrap the whole body in double quotes. "
            "A bare ' breaks mergeResources on every build with the "
            "misleading error 'Invalid unicode escape sequence' "
            "(incident 2026-09-13, TASK-506). Offending strings: " + examples
        )
        print(json.dumps({
            "hookSpecificOutput": {
                "hookEventName": "PreToolUse",
                "permissionDecision": "deny",
                "permissionDecisionReason": msg,
            }
        }))
    return 0


if __name__ == "__main__":
    main()
