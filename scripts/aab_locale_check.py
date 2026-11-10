"""Pure logic for the AAB locale gate (TASK-976): the parts tested in
scripts/tests, kept free of any subprocess or network.

The gate exists because Play's translation validation started rejecting
AABs that carry languages it cannot translate (v1.16.0's first publish
died on "languages are not supported for translation: te": AndroidX and
friends ship dozens of values-* folders and every one of them rides the
bundle). The gradle side now derives localeFilters from the res tree; this
check verifies the ARTIFACT, not the config, so a config regression or a
new library locale fails HERE, minutes after the bundle build, instead of
at the store upload.
"""

from __future__ import annotations

import re
from pathlib import Path

# Locale qualifiers as they appear in res folder names and in bundletool's
# resource dump. App folders use the Android res notation (pt-rBR, iw);
# bundletool normalizes to BCP-47-ish (pt-BR) and Hebrew reports as both
# iw (legacy) and he in different tool versions, so the comparison maps
# both spellings onto one key.
RES_LOCALE = re.compile(r"^values-([a-z]{2,3}(?:-r[A-Z]{2})?|b\+[A-Za-z]+(?:\+[A-Za-z]+)*)$")
DUMP_LOCALE = re.compile(r'^\s*locale:\s*"([^"]+)"', re.M)


def res_locales(res_dir: str | Path) -> list[str]:
    """The locale qualifiers of a res tree, values-* folders only.

    Non-locale values-* folders (values-night, values-v26) are NOT locales
    and are skipped here (the gradle filter's own build-time assert is the
    loud guard for those; this list must never feed a filter).
    """
    out = []
    for entry in sorted(Path(res_dir).iterdir()):
        m = RES_LOCALE.match(entry.name)
        if entry.is_dir() and m:
            out.append(m.group(1))
    return out


def normalize_locale(tag: str) -> str:
    """One spelling per locale across the two notations.

    pt-rBR == pt-BR, iw == he (legacy vs modern Hebrew); the plain
    two-letter form passes through. Region suffixes keep a dash.
    """
    tag = tag.strip()
    # Grandfathered BCP-47 folder form (values-b+sr+Latn): the language
    # follows the b+ marker.
    if tag.startswith("b+"):
        return tag[2:].replace("+", "-")
    tag = tag.replace("+", "-")
    if "-r" in tag:
        lang, region = tag.split("-r", 1)
        tag = f"{lang}-{region}"
    if tag == "iw":
        return "he"
    if tag == "in":
        return "id"
    if tag == "ji":
        return "yi"
    return tag


def allowed_locales(res_dir: str | Path) -> set[str]:
    """The locale set an AAB built from this res tree may carry: every app
    locale plus the English default, all normalized."""
    return {normalize_locale(loc) for loc in res_locales(res_dir)} | {"en"}


def aab_locales(dump_text: str) -> set[str]:
    """The locale set from `bundletool dump resources` output."""
    return {normalize_locale(loc) for loc in DUMP_LOCALE.findall(dump_text)}


def unexpected_locales(dump_text: str, res_dir: str | Path) -> list[str]:
    """Locales present in the AAB but not allowed by the res tree, sorted.

    Parent-language leakage is EXPECTED and fine (a values-pt-rBR app keeps
    generic AndroidX values-pt strings; pt is a language Play can
    translate), so the comparison is per-LANGUAGE, not per-locale: the
    language part of every dumped locale must be covered by the app's
    language parts or English.
    """
    allowed_langs = {loc.split("-")[0] for loc in allowed_locales(res_dir)}
    offenders = sorted(
        {
            loc
            for loc in aab_locales(dump_text)
            if loc.split("-")[0] not in allowed_langs
        }
    )
    return offenders


def _main() -> int:
    import sys

    args = sys.argv[1:]
    if len(args) != 3 or args[0] != "--check":
        print("usage: aab_locale_check.py --check <bundletool-dump.txt> <res-dir>", file=sys.stderr)
        return 2
    dump_path, res_dir = args[1], args[2]
    offenders = unexpected_locales(Path(dump_path).read_text(), res_dir)
    if offenders:
        print(
            "FAIL: the AAB carries languages outside the app's locale set "
            "(Play's translation validation will reject them): "
            + ", ".join(offenders),
            file=sys.stderr,
        )
        return 1
    print("== AAB locale set OK: every language in the bundle is one the app ships")
    return 0


if __name__ == "__main__":
    raise SystemExit(_main())
