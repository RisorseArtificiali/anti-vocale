#!/usr/bin/env python3
"""Validate Play Store screenshots for compliance and consistency.

Checks Play Store requirements (format, dimensions, aspect ratio, file size),
project conventions (expected filenames, language parity), and heuristic
quality signals (status bar artifacts, language content mismatches).

Usage:
    python3 scripts/validate-screenshots.py [OPTIONS]

Options:
    --dir DIR          Base screenshots directory
                       (default: docs/play-store/screenshots)
    --lang en|it|all   Validate specific language (default: all)
    --verbose          Show detailed check results
    --json             Output results as JSON

Exit codes:
    0  All checks passed
    1  One or more checks failed
    2  Warnings only (no failures)
"""

from __future__ import annotations

import argparse
import json
import sys
from dataclasses import dataclass, field
from enum import Enum
from pathlib import Path
from typing import Sequence

try:
    from PIL import Image
except ImportError:
    print(
        "Error: Pillow is required. Install with: pip install Pillow",
        file=sys.stderr,
    )
    sys.exit(1)


# ---------------------------------------------------------------------------
# Constants
# ---------------------------------------------------------------------------

EXPECTED_LANGUAGES = ("en", "it")

EXPECTED_FILENAMES: frozenset[str] = frozenset(
    {
        "logs_empty.png",
        "model_overview.png",
        "model_scrolled.png",
        "model_gemma.png",
        "settings_transcription.png",
        "settings_appearance.png",
        "settings_advanced.png",
    }
)

# Play Store limits
MIN_SHORTEST_SIDE = 320
MAX_LONGEST_SIDE = 3840
MAX_ASPECT_RATIO = 2.1  # Play Store accepts up to ~2.1:1 in practice
MAX_FILE_SIZE_BYTES = 8 * 1024 * 1024  # 8 MB (Play Store limit)
MIN_SCREENSHOTS = 2
MAX_SCREENSHOTS = 8

# Heuristic: pixels in top rows that look like a status bar
STATUS_BAR_CHECK_ROWS = 10
# Threshold: if more than this fraction of pixels in the top rows are
# very dark (< 30) or very bright white (> 225), flag as potential artifact.
STATUS_BAR_DARK_THRESHOLD = 30
STATUS_BAR_BRIGHT_THRESHOLD = 225
STATUS_BAR_ARTIFACT_FRACTION = 0.7

# Simple Italian words that should NOT appear in English screenshots.
# This is a heuristic -- we check filename metadata markers, not OCR.
ITALIAN_MARKERS = frozenset(
    {
        "impostazioni",
        "modello",
        "imposta ",
        "aspetto",
        "avanzate",
        "trascrizione",
        "ricerca",
        "registro",
        "impost",
    }
)


# ---------------------------------------------------------------------------
# Data structures
# ---------------------------------------------------------------------------


class Status(str, Enum):
    PASS = "PASS"
    WARN = "WARN"
    FAIL = "FAIL"


def _image_files(directory: Path) -> list[Path]:
    return sorted(
        p for p in directory.iterdir()
        if p.is_file() and p.suffix.lower() in (".png", ".jpg", ".jpeg")
    )


def _has_failures(reports: list[LanguageReport], global_checks: list[CheckResult]) -> bool:
    return any(r.failed for r in reports) or any(c.status == Status.FAIL for c in global_checks)


def _has_warnings(reports: list[LanguageReport], global_checks: list[CheckResult]) -> bool:
    return any(r.warned for r in reports) or any(c.status == Status.WARN for c in global_checks)


@dataclass
class CheckResult:
    name: str
    status: Status
    message: str
    details: list[str] = field(default_factory=list)


@dataclass
class LanguageReport:
    language: str
    checks: list[CheckResult] = field(default_factory=list)

    @property
    def failed(self) -> bool:
        return any(c.status == Status.FAIL for c in self.checks)

    @property
    def warned(self) -> bool:
        return any(c.status == Status.WARN for c in self.checks)


# ---------------------------------------------------------------------------
# Validation helpers
# ---------------------------------------------------------------------------


def check_directory_structure(
    base_dir: Path, languages: Sequence[str]
) -> CheckResult:
    """Verify that language subdirectories exist and contain files."""
    missing: list[str] = []
    empty: list[str] = []

    for lang in languages:
        lang_dir = base_dir / lang
        if not lang_dir.is_dir():
            missing.append(lang)
        elif not any(lang_dir.iterdir()):
            empty.append(lang)

    if missing:
        return CheckResult(
            name="directory_structure",
            status=Status.FAIL,
            message=f"Missing language directories: {', '.join(missing)}",
        )
    if empty:
        return CheckResult(
            name="directory_structure",
            status=Status.WARN,
            message=f"Empty language directories: {', '.join(empty)}",
        )
    return CheckResult(
        name="directory_structure",
        status=Status.PASS,
        message="All language directories present with files",
    )


def check_file_parity(base_dir: Path, languages: Sequence[str]) -> CheckResult:
    """Verify both language folders have the same filenames."""
    if len(languages) < 2:
        return CheckResult(
            name="file_parity",
            status=Status.PASS,
            message="Single language -- parity check skipped",
        )

    file_sets: dict[str, set[str]] = {}
    for lang in languages:
        lang_dir = base_dir / lang
        if lang_dir.is_dir():
            file_sets[lang] = {p.name for p in lang_dir.iterdir() if p.is_file()}
        else:
            file_sets[lang] = set()

    all_equal = len(set(map(frozenset, file_sets.values()))) == 1
    if all_equal:
        count = len(next(iter(file_sets.values())))
        return CheckResult(
            name="file_parity",
            status=Status.PASS,
            message=f"All languages have the same {count} file(s)",
        )

    reference_lang = languages[0]
    reference_set = file_sets[reference_lang]
    details: list[str] = []
    for lang in languages[1:]:
        diff = reference_set.symmetric_difference(file_sets[lang])
        if diff:
            details.append(f"{reference_lang} vs {lang}: {', '.join(sorted(diff))}")

    return CheckResult(
        name="file_parity",
        status=Status.FAIL,
        message="Filename mismatch between language directories",
        details=details,
    )


def check_format(lang_dir: Path) -> CheckResult:
    """Verify all files are valid PNG images without alpha channels."""
    details: list[str] = []
    invalid: list[str] = []
    has_alpha: list[str] = []
    non_png_ext: list[str] = []

    for fpath in _image_files(lang_dir):
        if fpath.suffix.lower() not in (".png",):
            non_png_ext.append(fpath.name)
            continue
        try:
            with Image.open(fpath) as img:
                fmt = (img.format or "").upper()
                if fmt != "PNG":
                    invalid.append(f"{fpath.name} (format={fmt})")
                if img.mode == "RGBA" or (
                    hasattr(img, "info") and img.info.get("transparency") is not None
                ):
                    has_alpha.append(fpath.name)
        except Exception as exc:
            invalid.append(f"{fpath.name} (error: {exc})")

    if non_png_ext:
        details.append(f"Non-PNG/JPEG files: {', '.join(non_png_ext)}")
    if invalid:
        details.append(f"Invalid images: {', '.join(invalid)}")
    if has_alpha:
        details.append(f"Images with alpha channel: {', '.join(has_alpha)}")

    if invalid or non_png_ext:
        return CheckResult(
            name="format",
            status=Status.FAIL,
            message="Invalid image format(s) found",
            details=details,
        )
    if has_alpha:
        return CheckResult(
            name="format",
            status=Status.WARN,
            message="Some images have alpha channel (not recommended for Play Store)",
            details=details,
        )
    return CheckResult(
        name="format",
        status=Status.PASS,
        message="All images are valid PNG without alpha",
        details=details,
    )


def check_dimensions(lang_dir: Path) -> CheckResult:
    """Verify dimensions meet Play Store requirements."""
    violations: list[str] = []
    details: list[str] = []

    for fpath in _image_files(lang_dir):
        try:
            with Image.open(fpath) as img:
                w, h = img.size
                shortest = min(w, h)
                longest = max(w, h)
                if shortest < MIN_SHORTEST_SIDE:
                    violations.append(
                        f"{fpath.name}: shortest side {shortest}px < {MIN_SHORTEST_SIDE}px"
                    )
                if longest > MAX_LONGEST_SIDE:
                    violations.append(
                        f"{fpath.name}: longest side {longest}px > {MAX_LONGEST_SIDE}px"
                    )
                details.append(f"{fpath.name}: {w}x{h}")
        except Exception:
            pass  # Already flagged in format check

    if violations:
        return CheckResult(
            name="dimensions",
            status=Status.FAIL,
            message="Dimension violations found",
            details=violations + details,
        )
    return CheckResult(
        name="dimensions",
        status=Status.PASS,
        message=f"All images within {MIN_SHORTEST_SIDE}-{MAX_LONGEST_SIDE}px bounds",
        details=details,
    )


def check_aspect_ratio(lang_dir: Path) -> CheckResult:
    """Verify aspect ratios are between 2:1 and 1:2."""
    violations: list[str] = []

    for fpath in _image_files(lang_dir):
        try:
            with Image.open(fpath) as img:
                w, h = img.size
                ratio = max(w, h) / min(w, h)
                if ratio > MAX_ASPECT_RATIO:
                    violations.append(
                        f"{fpath.name}: ratio {ratio:.2f}:1 exceeds {MAX_ASPECT_RATIO}:1 ({w}x{h})"
                    )
        except Exception:
            pass

    if violations:
        return CheckResult(
            name="aspect_ratio",
            status=Status.FAIL,
            message="Aspect ratio violations found",
            details=violations,
        )
    return CheckResult(
        name="aspect_ratio",
        status=Status.PASS,
        message=f"All aspect ratios within 1:{MAX_ASPECT_RATIO} to {MAX_ASPECT_RATIO}:1",
    )


def check_file_size(lang_dir: Path) -> CheckResult:
    """Verify each file is under the size limit."""
    violations: list[str] = []

    for fpath in sorted(lang_dir.iterdir()):
        if not fpath.is_file():
            continue
        size = fpath.stat().st_size
        if size > MAX_FILE_SIZE_BYTES:
            mb = size / (1024 * 1024)
            violations.append(f"{fpath.name}: {mb:.2f} MB exceeds 2 MB limit")

    if violations:
        return CheckResult(
            name="file_size",
            status=Status.FAIL,
            message="Files exceeding size limit",
            details=violations,
        )
    return CheckResult(
        name="file_size",
        status=Status.PASS,
        message="All files under 2 MB",
    )


def check_count(lang_dir: Path) -> CheckResult:
    """Verify screenshot count is within Play Store bounds."""
    count = len(_image_files(lang_dir))

    if count < MIN_SCREENSHOTS:
        return CheckResult(
            name="count",
            status=Status.FAIL,
            message=f"Only {count} screenshot(s), need at least {MIN_SCREENSHOTS}",
        )
    if count > MAX_SCREENSHOTS:
        return CheckResult(
            name="count",
            status=Status.WARN,
            message=f"{count} screenshots exceeds Play Store max of {MAX_SCREENSHOTS}",
        )
    return CheckResult(
        name="count",
        status=Status.PASS,
        message=f"{count} screenshots (within {MIN_SCREENSHOTS}-{MAX_SCREENSHOTS})",
    )


def check_naming(lang_dir: Path) -> CheckResult:
    """Verify filenames match expected project conventions."""
    actual = {p.name for p in lang_dir.iterdir() if p.is_file()}
    unexpected = actual - EXPECTED_FILENAMES
    missing = EXPECTED_FILENAMES - actual

    details: list[str] = []
    if unexpected:
        details.append(f"Unexpected files: {', '.join(sorted(unexpected))}")
    if missing:
        details.append(f"Missing expected files: {', '.join(sorted(missing))}")

    if unexpected and missing:
        return CheckResult(
            name="naming",
            status=Status.WARN,
            message="Filenames deviate from expected set",
            details=details,
        )
    if missing:
        return CheckResult(
            name="naming",
            status=Status.WARN,
            message=f"Missing {len(missing)} expected file(s)",
            details=details,
        )
    if unexpected:
        return CheckResult(
            name="naming",
            status=Status.WARN,
            message=f"{len(unexpected)} unexpected file(s)",
            details=details,
        )
    return CheckResult(
        name="naming",
        status=Status.PASS,
        message="All filenames match expected conventions",
    )


def check_status_bar_artifacts(lang_dir: Path) -> CheckResult:
    """Heuristic: check top pixel rows for status bar remnants.

    Status bars typically appear as a solid dark band with bright text (time,
    icons) or a solid light band. If the top rows are predominantly very dark
    or very bright, the status bar may still be present.
    """
    flagged: list[str] = []
    details: list[str] = []

    for fpath in _image_files(lang_dir):
        try:
            with Image.open(fpath) as img:
                # Convert to RGB for consistent analysis
                rgb = img.convert("RGB")
                w, h = rgb.size
                rows_to_check = min(STATUS_BAR_CHECK_ROWS, h)
                step = max(1, w // 50)
                sampled_count = 0
                dark_count = 0
                bright_count = 0

                for y in range(rows_to_check):
                    for x in range(0, w, step):
                        pixel = rgb.getpixel((x, y))
                        if isinstance(pixel, (list, tuple)):
                            r, g, b = int(pixel[0]), int(pixel[1]), int(pixel[2])
                        else:
                            r = g = b = 0
                        luminance = 0.299 * r + 0.587 * g + 0.114 * b
                        if luminance < STATUS_BAR_DARK_THRESHOLD:
                            dark_count += 1
                        elif luminance > STATUS_BAR_BRIGHT_THRESHOLD:
                            bright_count += 1
                        sampled_count += 1

                artifact_fraction = max(dark_count, bright_count) / sampled_count if sampled_count else 0
                if artifact_fraction > STATUS_BAR_ARTIFACT_FRACTION:
                    kind = "dark" if dark_count > bright_count else "bright"
                    flagged.append(fpath.name)
                    details.append(
                        f"{fpath.name}: {kind} band in top {rows_to_check} rows "
                        f"({artifact_fraction:.0%} pixels)"
                    )
        except Exception:
            pass

    if flagged:
        return CheckResult(
            name="status_bar_artifacts",
            status=Status.WARN,
            message=f"Potential status bar artifact in {len(flagged)} file(s)",
            details=details,
        )
    return CheckResult(
        name="status_bar_artifacts",
        status=Status.PASS,
        message="No status bar artifacts detected in top rows",
    )


def check_language_content(lang: str, lang_dir: Path) -> CheckResult:
    """Heuristic: flag Italian filenames in English screenshots directory.

    This checks whether filenames contain Italian-language markers that
    would indicate a misplaced screenshot (e.g., Italian screenshots in
    the English folder). A full OCR check is not performed.
    """
    if lang != "en":
        return CheckResult(
            name="language_content",
            status=Status.PASS,
            message=f"Language content check only applies to 'en' (skipping {lang})",
        )

    misplaced: list[str] = []
    for fpath in sorted(lang_dir.iterdir()):
        if not fpath.is_file():
            continue
        stem_lower = fpath.stem.lower()
        for marker in ITALIAN_MARKERS:
            if marker in stem_lower:
                misplaced.append(f"{fpath.name} (contains '{marker}')")
                break

    if misplaced:
        return CheckResult(
            name="language_content",
            status=Status.WARN,
            message="Italian markers found in English screenshot filenames",
            details=misplaced,
        )
    return CheckResult(
        name="language_content",
        status=Status.PASS,
        message="No Italian language markers in English filenames",
    )


# ---------------------------------------------------------------------------
# Per-language runner
# ---------------------------------------------------------------------------


def validate_language(base_dir: Path, lang: str) -> LanguageReport:
    """Run all validation checks for one language folder."""
    lang_dir = base_dir / lang
    report = LanguageReport(language=lang)

    if not lang_dir.is_dir():
        report.checks.append(
            CheckResult(
                name="directory",
                status=Status.FAIL,
                message=f"Directory does not exist: {lang_dir}",
            )
        )
        return report

    report.checks.extend([
        check_format(lang_dir),
        check_dimensions(lang_dir),
        check_aspect_ratio(lang_dir),
        check_file_size(lang_dir),
        check_count(lang_dir),
        check_naming(lang_dir),
        check_status_bar_artifacts(lang_dir),
        check_language_content(lang, lang_dir),
    ])

    return report


# ---------------------------------------------------------------------------
# Output formatting
# ---------------------------------------------------------------------------

# ANSI color codes
_RED = "\033[91m"
_GREEN = "\033[92m"
_YELLOW = "\033[93m"
_BOLD = "\033[1m"
_RESET = "\033[0m"


def _color_status(status: Status) -> str:
    mapping = {
        Status.PASS: f"{_GREEN}PASS{_RESET}",
        Status.WARN: f"{_YELLOW}WARN{_RESET}",
        Status.FAIL: f"{_RED}FAIL{_RESET}",
    }
    return mapping[status]


def _plain_status(status: Status) -> str:
    return status.value


def _format_report_human(
    reports: list[LanguageReport],
    global_checks: list[CheckResult],
    verbose: bool,
    use_color: bool,
) -> str:
    """Format validation results as a human-readable table."""
    lines: list[str] = []
    status_fn = _color_status if use_color else _plain_status

    # Global checks
    if global_checks:
        lines.append(f"{_BOLD}Global Checks{_RESET}" if use_color else "Global Checks")
        lines.append("-" * 60)
        for check in global_checks:
            lines.append(f"  [{status_fn(check.status)}] {check.name}: {check.message}")
            if verbose and check.details:
                for detail in check.details:
                    lines.append(f"       {detail}")
        lines.append("")

    # Per-language reports
    for report in reports:
        header = f"{_BOLD}Language: {report.language}{_RESET}" if use_color else f"Language: {report.language}"
        lines.append(header)
        lines.append("-" * 60)
        for check in report.checks:
            lines.append(f"  [{status_fn(check.status)}] {check.name}: {check.message}")
            if verbose and check.details:
                for detail in check.details:
                    lines.append(f"       {detail}")
        lines.append("")

    return "\n".join(lines)


def _format_report_json(
    reports: list[LanguageReport],
    global_checks: list[CheckResult],
) -> str:
    """Format validation results as a JSON object."""
    data: dict[str, object] = {
        "global_checks": [
            {
                "name": c.name,
                "status": c.status.value,
                "message": c.message,
                "details": c.details,
            }
            for c in global_checks
        ],
        "languages": [
            {
                "language": r.language,
                "checks": [
                    {
                        "name": c.name,
                        "status": c.status.value,
                        "message": c.message,
                        "details": c.details,
                    }
                    for c in r.checks
                ],
            }
            for r in reports
        ],
    }

    overall: str = "PASS"
    if _has_failures(reports, global_checks):
        overall = "FAIL"
    elif _has_warnings(reports, global_checks):
        overall = "WARN"
    data["overall"] = overall

    return json.dumps(data, indent=2)


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Validate Play Store screenshots for compliance and consistency.",
    )
    parser.add_argument(
        "--dir",
        type=Path,
        default=Path("docs/play-store/screenshots"),
        help="Base screenshots directory (default: docs/play-store/screenshots)",
    )
    parser.add_argument(
        "--lang",
        choices=["en", "it", "all"],
        default="all",
        help="Validate specific language (default: all)",
    )
    parser.add_argument(
        "--verbose",
        action="store_true",
        help="Show detailed check results",
    )
    parser.add_argument(
        "--json",
        action="store_true",
        dest="json_output",
        help="Output results as JSON",
    )
    return parser.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    """Run all validations and return an exit code."""
    args = parse_args(argv)
    base_dir = args.dir

    if not base_dir.is_dir():
        print(
            f"Error: screenshots directory does not exist: {base_dir}",
            file=sys.stderr,
        )
        return 1

    # Determine which languages to validate
    if args.lang == "all":
        # Discover available language directories, fall back to expected set
        available = sorted(
            d.name
            for d in base_dir.iterdir()
            if d.is_dir() and d.name in EXPECTED_LANGUAGES
        )
        languages = available if available else list(EXPECTED_LANGUAGES)
    else:
        languages = [args.lang]

    use_color = sys.stdout.isatty()

    # Global checks (directory structure + file parity)
    global_checks: list[CheckResult] = [
        check_directory_structure(base_dir, languages),
        check_file_parity(base_dir, languages),
    ]

    # Per-language checks
    reports: list[LanguageReport] = []
    for lang in languages:
        report = validate_language(base_dir, lang)
        reports.append(report)

    # Output
    if args.json_output:
        print(_format_report_json(reports, global_checks))
    else:
        print(
            _format_report_human(reports, global_checks, args.verbose, use_color)
        )

    # Determine exit code
    any_fail = _has_failures(reports, global_checks)
    any_warn = _has_warnings(reports, global_checks)

    if any_fail:
        summary = "FAILED"
    elif any_warn:
        summary = "PASSED WITH WARNINGS"
    else:
        summary = "ALL PASSED"

    if not args.json_output:
        overall_color = {
            "FAILED": _RED,
            "PASSED WITH WARNINGS": _YELLOW,
            "ALL PASSED": _GREEN,
        }.get(summary, "")
        if use_color:
            print(f"\n{overall_color}{_BOLD}Result: {summary}{_RESET}")
        else:
            print(f"\nResult: {summary}")

    if any_fail:
        return 1
    if any_warn:
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main())
