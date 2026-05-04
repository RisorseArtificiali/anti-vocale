#!/usr/bin/env python3
"""Extract latest release notes per locale from pseudo-XML into Play Store files.

Parses docs/play-store/release-notes.xml, extracts the LATEST version's notes
from each locale section (<en-US>, <it-IT>, etc.), and writes whatsnew-<locale>
files suitable for the r0adkll/upload-google-play GitHub Action.

Usage:
    python3 scripts/extract-release-notes.py
    python3 scripts/extract-release-notes.py --output-dir build/whats-new
    python3 scripts/extract-release-notes.py --fallback "Bug fixes and improvements"
"""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

MAX_LENGTH = 500


def parse_locale_sections(xml: str) -> dict[str, str]:
    """Extract locale tag content from pseudo-XML.

    Returns {locale: raw_content} for each <locale>...</locale> block.
    """
    pattern = re.compile(r"<([a-z]{2}-[A-Z]{2})>\n?(.*?)\n?</\1>", re.DOTALL)
    return {m.group(1): m.group(2).strip() for m in pattern.finditer(xml)}


def extract_latest_version(notes: str) -> str:
    """Extract only the first (latest) version section from multi-version notes."""
    version_heading = re.compile(
        r"^(?:What's new in|Novità della versione|Novità dalla versione)\s",
        re.MULTILINE,
    )
    headings = list(version_heading.finditer(notes))
    if not headings:
        return notes
    start = headings[0].start()
    end = headings[1].start() if len(headings) > 1 else len(notes)
    return notes[start:end].strip()


def truncate(text: str, max_len: int = MAX_LENGTH) -> str:
    """Truncate text to max_len, preferring to cut at the last newline."""
    if len(text) <= max_len:
        return text
    cut = text.rfind("\n", 0, max_len)
    if cut > max_len * 0.5:
        return text[:cut]
    return text[:max_len]


def extract_notes(
    xml_path: str,
    output_dir: str,
    fallback: str | None = None,
) -> int:
    """Main extraction logic. Returns exit code."""
    path = Path(xml_path)
    if not path.is_file():
        print(f"Error: XML file not found: {xml_path}", file=sys.stderr)
        return 1

    xml = path.read_text(encoding="utf-8")
    sections = parse_locale_sections(xml)
    if not sections:
        print("Error: No locale sections found in XML", file=sys.stderr)
        return 1

    out = Path(output_dir)
    out.mkdir(parents=True, exist_ok=True)

    for locale, content in sorted(sections.items()):
        latest = extract_latest_version(content)
        if not latest and locale == "en-US" and fallback:
            latest = fallback
        elif not latest:
            print(f"Warning: No content for locale {locale}", file=sys.stderr)
        latest = truncate(latest)
        (out / f"whatsnew-{locale}").write_text(latest, encoding="utf-8")

    return 0


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="Extract latest release notes per locale into Play Store files.",
    )
    parser.add_argument(
        "--xml-path",
        default="docs/play-store/release-notes.xml",
        help="Path to release-notes XML (default: docs/play-store/release-notes.xml)",
    )
    parser.add_argument(
        "--output-dir",
        default="whats-new",
        help="Output directory for whatsnew-<locale> files (default: whats-new)",
    )
    parser.add_argument(
        "--fallback",
        default=None,
        help="Fallback string for en-US when XML has no content",
    )
    return parser


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    return extract_notes(args.xml_path, args.output_dir, args.fallback)


if __name__ == "__main__":
    raise SystemExit(main())
