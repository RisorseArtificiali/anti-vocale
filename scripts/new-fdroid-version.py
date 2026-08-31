#!/usr/bin/env python3
"""Generate the per-ABI F-Droid recipe blocks for a new release (runbook step 4).

Derives the three blocks (armeabi-v7a, arm64-v8a, x86_64) by cloning the LATEST
existing version's blocks and renumbering them, instead of hand-editing: the
hand edit of 2026-08-30 duplicated the file's top-level tail keys
(AllowedAPKSigningKeys, AutoUpdateMode...) because the block boundary sat at
CurrentVersion, AFTER those keys, and fdroid's strict parser rejects duplicate
keys (GH release v1.11.0, three failed reference builds).

Usage (from the repo root, after the version bump commit):
  python3 scripts/new-fdroid-version.py \
      --recipe ~/data/repo/personal/fdroid-data/metadata/com.antivocale.app.yml \
      [--commit <sha>]        # tag-target commit; default: peeled v<version> from origin
      [--version 1.12.0] [--base-code 39]  # default: read from app/build.gradle.kts

What it does:
  1. reads versionName + versionCode from app/build.gradle.kts,
  2. peels the matching tag from origin to get the source commit,
  3. finds the newest version's three blocks in the recipe (by max versionCode),
  4. copies them with the new versionName/versionCodes (base*10+1/2/4) and commit,
  5. inserts them after the last existing build block (BEFORE the top-level tail),
  6. updates CurrentVersion/CurrentVersionCode,
  7. validates: single occurrence of every top-level key, exactly three new
     blocks, YAML parses, ABI codes match base*10+{1,2,4}.

Prints the diff summary; applies nothing until --write is passed.
"""

import argparse
import re
import subprocess
from pathlib import Path

REPO = "https://github.com/RisorseArtificiali/anti-vocale"


def fail(msg: str) -> None:
    """Abort with exit 1; raising (not bare sys.exit) keeps it NoReturn for type checkers."""
    raise SystemExit(f"ERROR: {msg}")


def read_version() -> tuple[str, int]:
    gradle = open("app/build.gradle.kts").read()
    name = re.search(r'versionName = "([^"]+)"', gradle)
    code = re.search(r"versionCode = (\d+)", gradle)
    assert name is not None and code is not None, "versionName/versionCode not in app/build.gradle.kts"
    return name.group(1), int(code.group(1))


def peel_tag(version: str) -> str:
    out = subprocess.run(
        ["git", "ls-remote", REPO, f"refs/tags/v{version}^{{}}", f"refs/tags/v{version}"],
        capture_output=True, text=True, check=True).stdout
    lines = [line for line in out.splitlines() if line.strip()]
    if not lines:
        fail(f"tag v{version} not found on {REPO} (push it first)")
    # prefer the peeled line (committag -> commit)
    for line in lines:
        if line.endswith("^{}"):
            return line.split()[0]
    return lines[0].split()[0]


def split_recipe(text: str) -> tuple[str, list[tuple[int, str]], str]:
    """Split into (header, [(versionCode, block)...], tail)."""
    m = re.search(r"^Builds:\n", text, re.M)
    if m is None:
        fail("no 'Builds:' section found")
    header = text[:m.end()]
    rest = text[m.end():]
    # Top-level tail = first line at column 0 that is not a list item or blank.
    tail_match = re.search(r"^(?![- \n])(\S.*)$", rest, re.M)
    if tail_match is None:
        fail("no top-level tail after the build blocks (AllowedAPKSigningKeys...)")
    blocks_text, tail = rest[:tail_match.start()], rest[tail_match.start():]
    blocks = []
    for bm in re.finditer(r"^  - versionName: \S+\n    versionCode: (\d+)\n.*?(?=^  - versionName: |\Z)",
                          blocks_text, re.S | re.M):
        blocks.append((int(bm.group(1)), bm.group(0)))
    if not blocks:
        fail("no build blocks found")
    return header, blocks, tail


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--recipe", required=True)
    ap.add_argument("--version")
    ap.add_argument("--base-code", type=int)
    ap.add_argument("--commit")
    ap.add_argument("--write", action="store_true")
    args = ap.parse_args()

    version = args.version
    base = args.base_code
    if version is None or base is None:
        gradle_version, gradle_base = read_version()
        version = version or gradle_version
        base = base if base is not None else gradle_base
    commit = args.commit or peel_tag(version)

    text = open(args.recipe).read()
    header, blocks, tail = split_recipe(text)

    newest = max(code for code, _ in blocks)
    newest_blocks = sorted((b for b in blocks if b[0] // 10 == newest // 10), key=lambda b: b[0])
    if len(newest_blocks) != 3:
        fail(f"expected 3 blocks for the newest version (codes {newest // 10}x), found {len(newest_blocks)}")

    expected_codes = sorted(base * 10 + s for s in (1, 2, 4))
    # Reusing an existing versionCode under a different version name would ship
    # green (fdroid has no duplicate-code lint) and break later: refuse it.
    reused = sorted(set(expected_codes) & {code for code, _ in blocks})
    if reused:
        fail(f"versionCodes {reused} already exist in the recipe (wrong --base-code? "
             f"the next free base is {max(code for code, _ in blocks) // 10 + 1})")
    # srclib pin: the app repo's .sherpa-version is the source of truth (issue #38).
    # The generator clones the previous blocks verbatim, and the checkupdates bot
    # does the same, so a stale sherpa pin propagates silently (2026-08-31: 1.13.4
    # shipped into the 1.11.0 blocks while the app built 1.13.5). Sync it here.
    pin = Path(".sherpa-version").read_text() if Path(".sherpa-version").exists() else ""
    pin_match = re.search(r"[0-9a-f]{40}", pin)
    new_blocks = []
    for code, block in newest_blocks:
        nb = re.sub(r"versionName: \S+", f"versionName: {version}", block, count=1)
        nb = re.sub(r"versionCode: \d+", f"versionCode: {base * 10 + code % 10}", nb, count=1)
        nb = re.sub(r"commit: [0-9a-f]{40}", f"commit: {commit}", nb)
        if pin_match:
            nb = re.sub(r"sherpa_onnx@[0-9a-f]{40}", f"sherpa_onnx@{pin_match.group(0)}", nb)
        new_blocks.append(nb)

    # One canonical blank line between blocks: re-joining with a separator line
    # per block would add an extra blank line at every junction on every run.
    canonical = [block.rstrip("\n") for _, block in blocks] + [nb.rstrip("\n") for nb in new_blocks]
    body = "\n\n".join(canonical) + "\n\n"
    # tail: bump CurrentVersion/CurrentVersionCode, preserving everything else once
    new_tail = re.sub(r"CurrentVersion: \S+", f"CurrentVersion: {version}", tail, count=1)
    new_tail = re.sub(r"CurrentVersionCode: \d+", f"CurrentVersionCode: {base * 10 + 2}", new_tail, count=1)
    out = header + body + "\n" + new_tail

    # --- validation (the duplicate-key class this script exists to prevent) ---
    top_keys = re.findall(r"^(\S[^:\n]*):", out, re.M)
    dupes = {k for k in top_keys if top_keys.count(k) > 1}
    if dupes:
        fail(f"duplicate top-level keys after edit: {sorted(dupes)}")
    try:
        import yaml
        yaml.safe_load(out)
    except ImportError:
        print("note: pyyaml not available, skipped YAML parse check")
    except Exception as e:
        fail(f"result does not parse as YAML: {e}")
    added = re.findall(rf"versionName: ({re.escape(version)})\n    versionCode: (\d+)", out)
    found_codes = sorted(int(c) for _, c in added)
    if found_codes != expected_codes:
        fail(f"expected codes {expected_codes} for {version}, found {found_codes}")
    if f"commit: {commit}" not in out:
        fail("source commit not injected")

    print(f"OK: {version} blocks {expected_codes} -> commit {commit[:12]}")
    print(f"    build blocks: {len(blocks)} -> {len(blocks) + 3}; CurrentVersionCode -> {base * 10 + 2}")
    if args.write:
        open(args.recipe, "w").write(out)
        print(f"    written to {args.recipe}")
    else:
        print("    dry run (pass --write to apply)")
        print(out[out.index(f"  - versionName: {version}"):][:600])


if __name__ == "__main__":
    main()
