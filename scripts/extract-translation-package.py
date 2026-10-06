#!/usr/bin/env python3
"""
TASK-771: extract the translatable-string package for a new UI locale.

Reads values/strings.xml and writes:
  docs/i18n/translation-package.csv   (key, source text, kind, notes; ONE
                                       shared package for every locale)
  docs/i18n/plurals-<tag>.md          (the plural keys and the quantity
                                       categories THAT locale must supply,
                                       plus the lint traps relevant to it)

The CSV is the hand-off for translation (human or assisted); the notes
column carries the exact format specifiers found (positional or not) and
markup warnings. translatable="false" strings are skipped. The plural
quantity sets per locale come from CLDR cardinal rules (tr35), cross-
checked against the lint failures recorded on TASK-741/755.

Usage: extract-translation-package.py <tag>   (tag in the table below)
"""
import csv
import re
import sys
import xml.etree.ElementTree as ET
from datetime import date
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
VALUES = REPO / "app/src/main/res/values/strings.xml"
OUT_DIR = REPO / "docs/i18n"

# CLDR cardinal plural categories (unicode.org/reports/tr35; checked
# against the real lint failures of the shipped locales).
CLDR_CARDINALS = {
    "zh": ["other"],
    "fi": ["one", "other"],
    "ar": ["zero", "one", "two", "few", "many", "other"],
}

# Lint traps that already bit us, keyed by the locales they can bite.
OUR_PLURAL_TRAPS = {
    "ar": [
        "ar demands ALL SIX categories; a missing one fails MissingQuantity",
        "ar (like iw) MUST provide a 'two' form or MissingQuantity fails",
    ],
}
# Traps for the one-matches-many family (fr/pt/hi/fa/ru/uk) live in the
# locale history; none of zh/fi/ar is in that family (CLDR: their 'one'
# matches exactly n=1), so no note is emitted for these three.

# Every format specifier we must preserve: positional (%1$s), plain
# (%d, %s), precision floats (%.1f), and the literal %%.
FORMAT_SPEC = re.compile(r"%\d+\$[sd]|%\.\d+f|%[sd]|%%")


def string_kind(text: str) -> str:
    return "formatted" if FORMAT_SPEC.search(text) else "plain"


def extract() -> tuple[list[list[str]], list[tuple[str, dict[str, str]]], list[str]]:
    """Returns (string rows, plural entries, warnings)."""
    root = ET.parse(VALUES).getroot()
    rows: list[list[str]] = []
    plurals: list[tuple[str, dict[str, str]]] = []
    warnings: list[str] = []
    for el in root:
        if el.tag == "string":
            if el.get("translatable") == "false":
                continue
            if len(el) > 0:
                # Inline markup (b/i/xliff:g) or CDATA: itertext would
                # silently drop the tags from the hand-off.
                warnings.append(f"{el.get('name')}: inline markup present; "
                                "the CSV text omits it, keep tags in the translation")
            text = "".join(el.itertext()).strip()
            specs = sorted(set(FORMAT_SPEC.findall(text)))
            rows.append([el.get("name"), text, string_kind(text),
                         f"keep verbatim: {specs}" if specs else ""])
        elif el.tag == "plurals":
            items = {i.get("quantity"): (i.text or "").strip() for i in el}
            plurals.append((el.get("name"), items))
    return rows, plurals, warnings


def main(tag: str) -> None:
    if tag not in CLDR_CARDINALS:
        print(f"unsupported locale tag {tag!r}; supported: {sorted(CLDR_CARDINALS)}")
        raise SystemExit(2)

    rows, plurals, warnings = extract()
    OUT_DIR.mkdir(parents=True, exist_ok=True)

    # ONE shared package: the CSV never depends on the tag.
    csv_path = OUT_DIR / "translation-package.csv"
    with open(csv_path, "w", newline="", encoding="utf-8") as f:
        w = csv.writer(f)
        w.writerow(["key", "english", "kind", "notes"])
        w.writerows(rows)
        for name, items in plurals:
            for qty, text in sorted(items.items()):
                w.writerow([f"{name} [{qty}]", text, "plural",
                            "supply only the quantities the locale table lists"])

    wanted = CLDR_CARDINALS[tag]
    md = OUT_DIR / f"plurals-{tag}.md"
    with open(md, "w", encoding="utf-8") as f:
        f.write(f"# Plural rules for values-{tag}\n\n")
        f.write(f"Extracted {date.today()} from values/strings.xml "
                f"({len(rows)} strings, {len(plurals)} plural keys).\n\n")
        f.write(f"CLDR cardinal categories for {tag}: **{', '.join(wanted)}**\n\n")
        f.write("Every plural key in the package must supply exactly these quantities:\n\n")
        for name, _ in plurals:
            f.write(f"- `{name}`: " + ", ".join(f"`{q}`" for q in wanted) + "\n")
        traps = OUR_PLURAL_TRAPS.get(tag, [])
        if traps:
            f.write("\nLint traps that can bite THIS locale:\n\n")
            for t in traps:
                f.write(f"- {t}\n")
        else:
            f.write("\nNo locale-specific plural lint traps apply.\n")
    for wn in warnings:
        print("WARN", wn)
    print(f"{tag}: {len(rows)} strings, {len(plurals)} plural keys -> "
          f"{csv_path.name} + {md.name}")


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "zh")
