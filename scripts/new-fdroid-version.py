#!/usr/bin/env python3
"""Generate the per-ABI F-Droid recipe blocks for a new release (runbook step 4).

Derives the per-ABI blocks (one per ABI in the gradle abiCode map) by cloning
the LATEST existing version's blocks and renumbering them, instead of hand-editing: the
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
  3. finds the newest version's per-ABI blocks in the recipe (by max versionCode),
  4. copies them with the new versionName/versionCodes (base*10+ABI codes), commit,
     and the sherpa_onnx srclib pin synced from .sherpa-version (issue #38),
  5. inserts them after the last existing build block (BEFORE the top-level tail),
  6. updates CurrentVersion/CurrentVersionCode,
  7. validates: single occurrence of every top-level key, one new block per
     ABI, YAML parses, ABI codes match base*10+{the gradle abiCode map}.

Prints the diff summary; applies nothing until --write is passed.
"""

import argparse
import re
import subprocess
import sys
from pathlib import Path

# TASK-633: the shared inert-line patterns (one owner with the finalize script).
sys.path.insert(0, str(Path(__file__).resolve().parent))
from fdroid_recipe_patterns import INERT_LINE_PATTERNS, INERT_LITERAL_REPLACES

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


def read_abi_codes() -> list[int]:
    """The ABI suffix set from the app's own gradle when-map: the single
    owner (TASK-525). The generator WRITES the blocks, so deriving the set
    here (not hardcoding {1,2,4}) is what makes an app-side ABI change fail
    loudly at generation time instead of silently producing a stale trio."""
    gradle = open("app/build.gradle.kts").read()
    when = re.search(r"val abiCode = when(.*?)else -> 0", gradle, re.S)
    assert when is not None, "abiCode when-map not found in app/build.gradle.kts"
    codes = [int(m) for m in re.findall(r'"[^"]+" -> (\d+)', when.group(1))]
    assert codes, "abiCode when-map has no entries"
    return codes


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
    # A short hash here writes recipe blocks whose checkout-by-name fails on
    # the buildserver (the 1.13.0 first-dispatch incident): actions/checkout
    # and fdroid both resolve 40-char SHAs. Refuse anything else.
    if not re.fullmatch(r"[0-9a-f]{40}", commit):
        fail(f"--commit must be a full 40-char SHA, got {commit!r}")

    text = open(args.recipe).read()
    header, blocks, tail = split_recipe(text)

    newest = max(code for code, _ in blocks)
    newest_blocks = sorted((b for b in blocks if b[0] // 10 == newest // 10), key=lambda b: b[0])
    abi_codes = read_abi_codes()
    if len(newest_blocks) != len(abi_codes):
        fail(f"expected {len(abi_codes)} blocks for the newest version (codes {newest // 10}x, "
             f"gradle declares {len(abi_codes)} ABIs), found {len(newest_blocks)}")

    expected_codes = sorted(base * 10 + s for s in abi_codes)
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
    pin_text = Path(".sherpa-version").read_text() if Path(".sherpa-version").exists() else ""
    pin_match = re.search(r"[0-9a-f]{40}", pin_text)
    # A missing/unparseable .sherpa-version must FAIL, not silently clone the
    # stale pin (the 2026-08-31 incident was exactly a silently cloned stale pin).
    if pin_match is None:
        fail(".sherpa-version missing or has no 40-hex srclib commit; it is a release requirement (issue #38)")
    new_blocks = []
    for code, block in newest_blocks:
        nb = re.sub(r"versionName: \S+", f"versionName: {version}", block, count=1)
        nb = re.sub(r"versionCode: \d+", f"versionCode: {base * 10 + code % 10}", nb, count=1)
        nb = re.sub(r"commit: [0-9a-f]{40}", f"commit: {commit}", nb)
        if pin_match:
            nb = re.sub(r"sherpa_onnx@[0-9a-f]{40}", f"sherpa_onnx@{pin_match.group(0)}", nb)
        # TASK-525: strip inert lines from the NEW blocks only (shipped blocks
        # describe what built their APKs and are never rewritten here):
        # - `sdkmanager 'ndk;r27c'` downloads a full NDK nothing consumes:
        #   fdroidserver routes ANDROID_NDK to the ndk: field's r28c, sherpa
        #   honors $ANDROID_NDK, and the app compiles no native code of its
        #   own. Verified in the v1.12.1 reference-build log: r27c was
        #   downloaded by this very line and every compile ran r28c.
        # - `zip` in the apt list: the build steps use wget/unzip/rm and
        #   zipalign.py is pure Python.
        # TASK-633: the inert-line list has one owner (shared with the
        # finalize script's normalize_recipe); a divergence there breaks the
        # bot-first comparison silently.
        for _, pattern in INERT_LINE_PATTERNS:
            nb = re.sub(pattern, "", nb, flags=re.M)
        for _, old_lit, new_lit in INERT_LITERAL_REPLACES:
            nb = nb.replace(old_lit, new_lit)
        new_blocks.append(nb)

    # One canonical blank line between blocks AND before the tail: body already
    # ends with "\n\n" (one blank line), so the tail joins with NO extra "\n".
    # The extra newline here made the fork CI's `fdroid rewritemeta` job red on
    # 1.11.1 (double blank at the block->tail junction, the one spot the join
    # did not cover; earlier releases' blobs record no such artifact, and the
    # 1.11.0 red was the NDK pin). Canonical form = upstream master's, one
    # blank line; check-fdroid-release.sh rejects consecutive blanks outright.
    canonical = [block.rstrip("\n") for _, block in blocks] + [nb.rstrip("\n") for nb in new_blocks]
    body = "\n\n".join(canonical) + "\n\n"
    # tail: bump CurrentVersion/CurrentVersionCode, preserving everything else once
    new_tail = re.sub(r"CurrentVersion: \S+", f"CurrentVersion: {version}", tail, count=1)
    new_tail = re.sub(r"CurrentVersionCode: \d+", f"CurrentVersionCode: {base * 10 + max(abi_codes)}", new_tail, count=1)
    # VercodeOperation is fdroid's OWN vercode derivation for future
    # auto-updates: it is a stale-prone copy of the ABI set no app-repo gate
    # reads, so it must be rewritten from the same gradle map (otherwise a
    # gradle ABI change ships green while fdroid offers wrong codes).
    vop = "VercodeOperation:\n" + "".join(
        f"  - '%c * 10 + {c}'\n" for c in sorted(abi_codes))
    new_tail, vop_n = re.subn(
        r"VercodeOperation:\n(?:[ ]+- '%c \* 10 \+ \d+'\n)+", vop, new_tail, count=1)
    if vop_n != 1:
        fail("VercodeOperation list not found or not rewritten in the tail "
             "(fdroid's vercode derivation would go stale)")
    out = header + body + new_tail

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
    print(f"    build blocks: {len(blocks)} -> {len(blocks) + len(abi_codes)}; CurrentVersionCode -> {base * 10 + max(abi_codes)}")
    if args.write:
        open(args.recipe, "w").write(out)
        print(f"    written to {args.recipe}")
    else:
        print("    dry run (pass --write to apply)")
        print(out[out.index(f"  - versionName: {version}"):][:600])


if __name__ == "__main__":
    main()
