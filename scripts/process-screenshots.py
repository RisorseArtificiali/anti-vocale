#!/usr/bin/env python3
"""Process raw Android screenshots for Play Store -- crop status/nav bars."""

from __future__ import annotations

import argparse
import shutil
import subprocess
import sys
from dataclasses import dataclass, field
from pathlib import Path
from typing import Sequence

# ---------------------------------------------------------------------------
# Constants
# ---------------------------------------------------------------------------

PLAY_STORE_MIN_DIM = 320
PLAY_STORE_MAX_DIM = 3840
PLAY_STORE_MAX_FILE_SIZE = 8 * 1024 * 1024  # 8 MB (Play Store screenshot limit)

DEFAULT_SOURCE = "docs/screenshots/raw"
DEFAULT_OUTPUT = "docs/play-store/screenshots"
SUPPORTED_LANGS = ("en", "it")

# Variance threshold: rows with luminance standard deviation below this value
# are considered "uniform" (status bar or nav bar).  App content typically has
# std >> 20 even for dark-themed apps because icons, text, and UI elements
# create luminance variation across the width.
_ROW_STD_THRESHOLD = 15.0

# Minimum number of consecutive uniform rows at the edge to be considered a
# status/nav bar.  This prevents false positives from thin divider lines.
_MIN_BAR_ROWS = 10

# ---------------------------------------------------------------------------
# Data structures
# ---------------------------------------------------------------------------


@dataclass
class CropRegion:
    """Pixel offsets to remove from top (status bar) and bottom (nav bar)."""

    top: int = 0
    bottom: int = 0


@dataclass
class ProcessResult:
    """Outcome of processing a single screenshot."""

    source: Path
    dest: Path
    lang: str
    orig_size: tuple[int, int] = (0, 0)
    crop: CropRegion = field(default_factory=CropRegion)
    out_size: tuple[int, int] = (0, 0)
    orig_file_size: int = 0
    out_file_size: int = 0
    skipped: bool = False
    skip_reason: str = ""
    warnings: list[str] = field(default_factory=list)
    error: str = ""


# ---------------------------------------------------------------------------
# Image backend abstraction
# ---------------------------------------------------------------------------


def _has_pillow() -> bool:
    try:
        import PIL  # type: ignore[unused-ignore]

        return True
    except ImportError:
        return False


def _has_imagemagick() -> bool:
    return shutil.which("convert") is not None and shutil.which("identify") is not None


class ImageBackend:
    """Unified interface for image operations (Pillow or ImageMagick)."""

    def open_size(self, path: Path) -> tuple[int, int]:
        raise NotImplementedError

    def row_luminance_std(self, path: Path, y: int, width: int) -> float:
        """Return the standard deviation of luminance across row *y*."""
        raise NotImplementedError

    def crop(self, path: Path, region: CropRegion, out: Path) -> tuple[int, int]:
        """Crop image and save to *out*.  Returns (width, height) of result."""
        raise NotImplementedError


class PillowBackend(ImageBackend):
    def open_size(self, path: Path) -> tuple[int, int]:
        from PIL import Image

        with Image.open(path) as img:
            return img.size

    def row_luminance_std(self, path: Path, y: int, width: int) -> float:
        from PIL import Image

        step = max(1, width // 100)  # sample ~100 pixels across the row
        with Image.open(path) as img:
            rgb = img.convert("RGB")
            lums = []
            for x in range(0, width, step):
                pixel = rgb.getpixel((x, y))
                if isinstance(pixel, (list, tuple)):
                    lum = int(pixel[0]) + int(pixel[1]) + int(pixel[2])
                else:
                    lum = 0
                lums.append(lum)
            if len(lums) < 2:
                return 0.0
            mean = sum(lums) / len(lums)
            variance = sum((v - mean) ** 2 for v in lums) / len(lums)
            return variance ** 0.5

    def crop(self, path: Path, region: CropRegion, out: Path) -> tuple[int, int]:
        from PIL import Image

        with Image.open(path) as img:
            width, height = img.size
            new_bottom = height - region.bottom
            if new_bottom <= region.top:
                raise ValueError(
                    f"Crop region invalid: top={region.top}, bottom={region.bottom}, "
                    f"image height={height}"
                )
            cropped = img.crop((0, region.top, width, new_bottom))
            cropped.save(out, "PNG", optimize=True)
            return cropped.size


class ImageMagickBackend(ImageBackend):
    def open_size(self, path: Path) -> tuple[int, int]:
        out = subprocess.check_output(
            ["identify", "-format", "%w %h", str(path)],
            text=True,
        ).strip()
        w, h = out.split()
        return int(w), int(h)

    def row_luminance_std(self, path: Path, y: int, width: int) -> float:
        # Extract a 1-pixel-tall strip across the full width at row y,
        # convert to txt format, and compute luminance std from the output.
        out = subprocess.check_output(
            [
                "convert",
                str(path),
                "-crop",
                f"{width}x1+0+{y}",
                "+repage",
                "-depth",
                "8",
                "txt:-",
            ],
            text=True,
        )
        lums = []
        for line in out.splitlines():
            # Lines look like: "123,0: (R, G, B)  #RRGGBB  srgb(R,G,B)"
            if ": (" in line:
                try:
                    inside = line.split("(")[1].split(")")[0]
                    parts = [int(p.strip()) for p in inside.split(",")]
                    lums.append(parts[0] + parts[1] + parts[2])
                except (IndexError, ValueError):
                    continue
        if len(lums) < 2:
            return 0.0
        # Subsample for efficiency (take every Nth pixel)
        step = max(1, len(lums) // 100)
        lums = lums[::step]
        mean = sum(lums) / len(lums)
        variance = sum((v - mean) ** 2 for v in lums) / len(lums)
        return variance ** 0.5

    def crop(self, path: Path, region: CropRegion, out: Path) -> tuple[int, int]:
        width, height = self.open_size(path)
        new_height = height - region.top - region.bottom
        if new_height <= 0:
            raise ValueError(
                f"Crop region invalid: top={region.top}, bottom={region.bottom}, "
                f"image height={height}"
            )
        subprocess.check_call(
            [
                "convert",
                str(path),
                "-crop",
                f"{width}x{new_height}+0+{region.top}",
                "+repage",
                str(out),
            ]
        )
        return width, new_height


def _get_backend() -> ImageBackend:
    if _has_pillow():
        return PillowBackend()
    if _has_imagemagick():
        return ImageMagickBackend()
    print(
        "Error: Neither Pillow nor ImageMagick is available.\n"
        "  Install Pillow:  pip install Pillow\n"
        "  Install ImageMagick:  sudo apt install imagemagick",
        file=sys.stderr,
    )
    sys.exit(1)


# ---------------------------------------------------------------------------
# Auto-detection helpers
# ---------------------------------------------------------------------------


def _is_uniform_row(backend: ImageBackend, path: Path, y: int, width: int) -> bool:
    """Return True if row y has low luminance variance (uniform bar region)."""
    return backend.row_luminance_std(path, y, width) < _ROW_STD_THRESHOLD


def detect_status_bar_height(
    backend: ImageBackend,
    path: Path,
    width: int,
    height: int,
    max_scan: int = 200,
) -> int:
    """Return the estimated status bar height in pixels.

    Strategy: scan rows from the top.  Status bar rows have near-zero
    luminance variance (uniform background).  App content rows have high
    variance due to icons, text, and UI elements.  We look for the first
    run of uniform rows at the top, then find where they end.

    Handles rounded-corner transitions: if there is a brief non-uniform
    gap followed by more uniform rows, we skip the gap and keep scanning.
    """
    limit = min(max_scan, height)
    uniform_runs: list[tuple[int, int]] = []  # (start, end_exclusive)
    in_run = False
    run_start = 0

    for y in range(limit):
        if _is_uniform_row(backend, path, y, width):
            if not in_run:
                run_start = y
                in_run = True
        else:
            if in_run:
                uniform_runs.append((run_start, y))
                in_run = False

    # Close last run if still open
    if in_run:
        uniform_runs.append((run_start, limit))

    if not uniform_runs:
        return 0

    # The status bar must start at or very near row 0.
    # Take the first run that starts at row 0.
    first_run = uniform_runs[0]
    if first_run[0] > 2:
        # No uniform rows at the very top -- no status bar.
        return 0

    status_bar_end = first_run[1]

    # Android 15+ sometimes has a thin "transition" zone (rounded corners,
    # privacy dots) that breaks the uniform region for a few rows before
    # the actual app content starts.  Check if there is another uniform run
    # right after a small gap (< 20 rows).
    for run_start, run_end in uniform_runs[1:]:
        gap = run_start - status_bar_end
        if gap < 20 and run_end - run_start >= _MIN_BAR_ROWS:
            # Extend the status bar region through the gap and this next run.
            status_bar_end = run_end
        else:
            break

    return status_bar_end


def detect_nav_bar_height(
    backend: ImageBackend,
    path: Path,
    width: int,
    height: int,
    max_scan: int = 200,
) -> int:
    """Return the estimated navigation bar height in pixels.

    Strategy: scan rows from the bottom upward.  Nav bar rows have near-zero
    luminance variance.  Find the first contiguous block of uniform rows
    starting from the bottom edge.
    """
    limit = min(max_scan, height)

    # Scan bottom-up to find the boundary between nav bar and app content.
    # The nav bar is the contiguous block of uniform rows at the very bottom.
    nav_bar_start = height  # first non-uniform row from bottom (inclusive)
    consecutive_uniform = 0

    for offset in range(limit):
        y = height - 1 - offset
        if _is_uniform_row(backend, path, y, width):
            consecutive_uniform += 1
            nav_bar_start = y
        else:
            # If we have accumulated enough uniform rows, this non-uniform
            # row marks the end of the nav bar.
            if consecutive_uniform >= _MIN_BAR_ROWS:
                break
            # Reset -- the uniform rows were not part of a real nav bar.
            consecutive_uniform = 0
            nav_bar_start = height

    if consecutive_uniform < _MIN_BAR_ROWS:
        return 0

    return height - nav_bar_start


def _image_already_cropped(
    backend: ImageBackend,
    path: Path,
    width: int,
    height: int,
) -> bool:
    """Heuristic: check if the image has already been cropped.

    If the top few rows have high luminance variance (they contain app
    content), the status bar was already removed.
    """
    for y in range(min(5, height)):
        if not _is_uniform_row(backend, path, y, width):
            return True  # Non-uniform rows at the top means no status bar.
    return False


# ---------------------------------------------------------------------------
# Core processing
# ---------------------------------------------------------------------------


def _validate_play_store(result: ProcessResult) -> list[str]:
    """Check Play Store compliance and return warning strings."""
    warnings: list[str] = []
    w, h = result.out_size

    if min(w, h) < PLAY_STORE_MIN_DIM:
        warnings.append(
            f"Shortest side {min(w, h)}px is below Play Store minimum ({PLAY_STORE_MIN_DIM}px)"
        )
    if max(w, h) > PLAY_STORE_MAX_DIM:
        warnings.append(
            f"Longest side {max(w, h)}px exceeds Play Store maximum ({PLAY_STORE_MAX_DIM}px)"
        )
    if result.dest.suffix.lower() != ".png":
        warnings.append("Not PNG format")
    if result.out_file_size > PLAY_STORE_MAX_FILE_SIZE:
        size_mb = result.out_file_size / (1024 * 1024)
        warnings.append(f"File size {size_mb:.2f} MB exceeds 2 MB limit")

    return warnings


def process_file(
    backend: ImageBackend,
    source: Path,
    dest: Path,
    lang: str,
    status_bar_override: int | None,
    nav_bar_override: int | None,
    force: bool,
    dry_run: bool,
) -> ProcessResult:
    """Process a single screenshot file."""
    result = ProcessResult(source=source, dest=dest, lang=lang)

    try:
        result.orig_file_size = source.stat().st_size
        width, height = backend.open_size(source)
        result.orig_size = (width, height)

        # Check if already cropped (unless force)
        if not force and _image_already_cropped(backend, source, width, height):
            result.skipped = True
            result.skip_reason = "already cropped (top rows are non-uniform)"
            return result

        # Determine crop region
        if status_bar_override is not None:
            sb_height = status_bar_override
        else:
            sb_height = detect_status_bar_height(backend, source, width, height)

        if nav_bar_override is not None:
            nb_height = nav_bar_override
        else:
            nb_height = detect_nav_bar_height(backend, source, width, height)

        result.crop = CropRegion(top=sb_height, bottom=nb_height)

        if sb_height == 0 and nb_height == 0:
            result.skipped = True
            result.skip_reason = "no status/nav bar detected"
            return result

        if dry_run:
            new_w = width
            new_h = height - sb_height - nb_height
            result.out_size = (new_w, new_h)
            result.warnings = _validate_play_store(result)
            return result

        # Perform the crop
        out_size = backend.crop(source, result.crop, dest)
        result.out_size = out_size
        result.out_file_size = dest.stat().st_size
        result.warnings = _validate_play_store(result)

    except Exception as exc:
        result.error = str(exc)

    return result


# ---------------------------------------------------------------------------
# Discovery
# ---------------------------------------------------------------------------


def discover_languages(source: Path, lang_filter: str) -> list[str]:
    """Return language subdirectories to process.

    Returns ["."] as a sentinel when no language subdirectories are found,
    meaning files are located directly in *source*.
    """
    if lang_filter == "all":
        langs: list[str] = []
        if source.is_dir():
            for child in sorted(source.iterdir()):
                if child.is_dir() and child.name in SUPPORTED_LANGS:
                    langs.append(child.name)
        # Fallback: if no lang dirs found, treat source itself as the dir (flat layout)
        if not langs:
            langs = ["."]
        return langs

    if lang_filter in SUPPORTED_LANGS:
        return [lang_filter]

    print(
        f"Error: unsupported language '{lang_filter}'. "
        f"Choose from: {', '.join(SUPPORTED_LANGS)}",
        file=sys.stderr,
    )
    sys.exit(1)


def collect_pngs(directory: Path) -> list[Path]:
    """Gather all .png files in *directory*, sorted."""
    if not directory.is_dir():
        return []
    return sorted(
        p for p in directory.iterdir() if p.is_file() and p.suffix.lower() == ".png"
    )


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="Process raw Android screenshots for Play Store -- crop status/nav bars.",
    )
    parser.add_argument(
        "--source",
        type=Path,
        default=Path(DEFAULT_SOURCE),
        help=f"Source directory (default: {DEFAULT_SOURCE})",
    )
    parser.add_argument(
        "--output",
        type=Path,
        default=Path(DEFAULT_OUTPUT),
        help=f"Output directory (default: {DEFAULT_OUTPUT})",
    )
    parser.add_argument(
        "--status-bar",
        type=int,
        default=None,
        metavar="N",
        help="Override auto-detected status bar height in pixels",
    )
    parser.add_argument(
        "--nav-bar",
        type=int,
        default=None,
        metavar="N",
        help="Override auto-detected navigation bar height in pixels",
    )
    parser.add_argument(
        "--dry-run",
        action="store_true",
        help="Show what would be done without processing",
    )
    parser.add_argument(
        "--lang",
        choices=["en", "it", "all"],
        default="all",
        help="Process specific language only (default: all)",
    )
    parser.add_argument(
        "--force",
        action="store_true",
        help="Re-process even if output already exists or image appears already cropped",
    )
    return parser


# ---------------------------------------------------------------------------
# Output formatting
# ---------------------------------------------------------------------------


def _fmt_size(px: tuple[int, int]) -> str:
    return f"{px[0]}x{px[1]}"


def _fmt_bytes(num_bytes: int) -> str:
    if num_bytes < 1024:
        return f"{num_bytes} B"
    if num_bytes < 1024 * 1024:
        return f"{num_bytes / 1024:.1f} KB"
    return f"{num_bytes / (1024 * 1024):.2f} MB"


def print_results(results: Sequence[ProcessResult]) -> None:
    """Print a summary table of all results."""
    if not results:
        print("\nNo files found to process.")
        return

    name_w = max(len(r.source.name) for r in results) + 2
    name_w = max(name_w, 10)

    print()
    hdr = (
        f"{'File':<{name_w}} {'Lang':<5} {'Input':>12} {'Output':>12}"
        f" {'Crop':>14} {'Size':>10} {'Store':>6}"
    )
    print(hdr)
    print("-" * len(hdr))

    total_processed = 0
    total_skipped = 0
    total_warnings = 0
    total_errors = 0

    for r in results:
        name = r.source.name
        lang = r.lang if r.lang != "." else "  --"

        if r.error:
            total_errors += 1
            crop_str = "--"
            size_str = "--"
            store_str = "!!"
        elif r.skipped:
            total_skipped += 1
            crop_str = "--"
            size_str = _fmt_size(r.orig_size)
            store_str = "--"
        else:
            total_processed += 1
            crop_str = f"T{r.crop.top} B{r.crop.bottom}"
            size_str = _fmt_size(r.out_size)
            if r.warnings:
                store_str = "NO"
                total_warnings += len(r.warnings)
            else:
                store_str = "YES"

        input_str = _fmt_size(r.orig_size)

        print(
            f"{name:<{name_w}} {lang:<5} {input_str:>12} {size_str:>12}"
            f" {crop_str:>14} {_fmt_bytes(r.out_file_size):>10} {store_str:>6}"
        )

        for w in r.warnings:
            print(f"  WARNING: {w}")
        if r.error:
            print(f"  ERROR:   {r.error}")
        if r.skipped:
            print(f"  SKIP:    {r.skip_reason}")

    # Summary
    print()
    print("Summary:")
    print(f"  Processed: {total_processed}")
    print(f"  Skipped:   {total_skipped}")
    print(f"  Warnings:  {total_warnings}")
    print(f"  Errors:    {total_errors}")


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------


def main() -> None:
    args = build_parser().parse_args()
    backend = _get_backend()

    source_root: Path = args.source
    output_root: Path = args.output
    dry_run: bool = args.dry_run
    force: bool = args.force
    status_bar: int | None = args.status_bar
    nav_bar: int | None = args.nav_bar

    langs = discover_languages(source_root, args.lang)

    all_results: list[ProcessResult] = []

    for lang in langs:
        # lang == "." means flat layout (no language subdirs)
        if lang == ".":
            src_dir = source_root
            dst_dir = output_root
        else:
            src_dir = source_root / lang
            dst_dir = output_root / lang

        pngs = collect_pngs(src_dir)
        if not pngs:
            print(f"No PNG files found in {src_dir}")
            continue

        if not dry_run:
            dst_dir.mkdir(parents=True, exist_ok=True)

        for png in pngs:
            dest = dst_dir / png.name

            # Skip if output exists and not forced
            if not force and dest.exists() and dest.is_file():
                all_results.append(
                    ProcessResult(
                        source=png,
                        dest=dest,
                        lang=lang if lang != "." else "--",
                        orig_size=backend.open_size(png),
                        orig_file_size=png.stat().st_size,
                        skipped=True,
                        skip_reason="output already exists (use --force to overwrite)",
                    )
                )
                continue

            all_results.append(
                process_file(
                    backend=backend,
                    source=png,
                    dest=dest,
                    lang=lang if lang != "." else "--",
                    status_bar_override=status_bar,
                    nav_bar_override=nav_bar,
                    force=force,
                    dry_run=dry_run,
                )
            )

    print_results(all_results)

    # Exit with error code if any file had an error
    if any(r.error for r in all_results):
        sys.exit(1)


if __name__ == "__main__":
    main()
