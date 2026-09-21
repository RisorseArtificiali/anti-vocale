#!/usr/bin/env python3
"""Release-version guard (born 2026-09-21, the 1.13.0 near-miss).

The 1.13.0 bump nearly shipped with versionCode 43, the code Play and
F-Droid already served as 1.12.1, because main's SNAPSHOT legitimately
keeps the released base code and nothing compared the two at release
time. This script is that comparison. Run it from the repo root, on the
release commit, BEFORE pushing the bump.

Checks, all against the LATEST vX.Y.Z tag:
  1. a tag exists to compare against (first release: pass --allow-no-tag)
  2. the working versionCode is STRICTLY greater than the tagged one
  3. a non-SNAPSHOT versionName has a fastlane changelog
     fastlane/metadata/android/<locale>/changelogs/<versionCode>.txt
     for every locale dir that exists
  4. a SNAPSHOT versionName never carries a changelog file for its code
Exit 0 = safe to bump; exit 1 with the failing check on stdout.
"""

import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent


def gradle_version_fields() -> tuple[int, str]:
    text = (ROOT / "app" / "build.gradle.kts").read_text(encoding="utf-8")
    code = re.search(r"versionCode = (\d+)", text)
    name = re.search(r'versionName = "([^"]+)"', text)
    if not code or not name:
        sys.exit("FAIL: cannot read versionCode/versionName from app/build.gradle.kts")
    return int(code.group(1)), name.group(1)


def latest_tag() -> str | None:
    out = subprocess.run(
        ["git", "tag", "--list", "v[0-9]*.[0-9]*.[0-9]*", "--sort=-v:refname"],
        capture_output=True, text=True, cwd=ROOT, check=True).stdout.splitlines()
    return out[0] if out else None


def tagged_version_code(tag: str) -> int | None:
    text = subprocess.run(
        ["git", "show", f"{tag}:app/build.gradle.kts"],
        capture_output=True, text=True, cwd=ROOT, check=True).stdout
    m = re.search(r"versionCode = (\d+)", text)
    return int(m.group(1)) if m else None


def main() -> None:
    allow_no_tag = "--allow-no-tag" in sys.argv
    code, name = gradle_version_fields()
    tag = latest_tag()
    failures: list[str] = []

    if tag is None:
        if not allow_no_tag:
            sys.exit("FAIL: no vX.Y.Z tag found; pass --allow-no-tag for a first release")
    else:
        tagged = tagged_version_code(tag)
        if tagged is None:
            failures.append(f"{tag} has no readable versionCode")
        elif code <= tagged:
            failures.append(
                f"versionCode {code} is not greater than {tag}'s {tagged}: "
                "Play and F-Droid already serve that code")

    snapshot = name.endswith("-SNAPSHOT")
    changelog = ROOT / f"fastlane/metadata/android/en-US/changelogs/{code}.txt"
    locales = sorted(p.name for p in (ROOT / "fastlane/metadata/android").iterdir() if p.is_dir())
    if snapshot:
        if changelog.exists():
            failures.append(f"{name} is a SNAPSHOT but changelog {changelog.name} exists")
    else:
        missing = [loc for loc in locales
                   if not (ROOT / f"fastlane/metadata/android/{loc}/changelogs/{code}.txt").exists()]
        if missing:
            failures.append(
                f"{name} is a release but changelogs/{code}.txt is missing for: {', '.join(missing)}")

    if failures:
        for f in failures:
            print(f"FAIL: {f}")
        sys.exit(1)
    tag_note = f" (last tag {tag} was {tagged_version_code(tag)})" if tag else ""
    print(f"OK: versionName {name}, versionCode {code}{tag_note}, "
          f"changelog {'present' if not snapshot else 'n/a (snapshot)'}")


if __name__ == "__main__":
    main()
