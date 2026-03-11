#!/usr/bin/env python3
"""
Screenshot Processing Script for Play Store
Removes status bar (top) and navigation bar (bottom) from screenshots.

Usage: python3 scripts/process_screenshots.py
"""

from pathlib import Path
from PIL import Image
import sys

# Configuration
SOURCE_DIR = Path("docs/screenshots")
OUTPUT_DIR = Path("docs/play-store/screenshots")
STATUS_BAR_HEIGHT = 85  # Pixels to remove from top
NAV_BAR_HEIGHT = 60      # Pixels to remove from bottom

# Minimum dimension requirement (Play Store)
MIN_DIMENSION = 320

def process_screenshot(input_path: Path, output_path: Path) -> tuple[int, int]:
    """Process a single screenshot by cropping status and navigation bars."""
    img = Image.open(input_path)
    width, height = img.size

    # Calculate crop box: (left, top, right, bottom)
    new_height = height - STATUS_BAR_HEIGHT - NAV_BAR_HEIGHT
    crop_box = (0, STATUS_BAR_HEIGHT, width, height - NAV_BAR_HEIGHT)

    # Crop
    cropped = img.crop(crop_box)

    # Save
    cropped.save(output_path, "PNG", optimize=True)

    return cropped.size

def main():
    print("🖼️  Processing screenshots for Play Store...")
    print(f"   Source: {SOURCE_DIR}")
    print(f"   Output: {OUTPUT_DIR}")
    print()

    # Create output directory
    OUTPUT_DIR.mkdir(parents=True, exist_ok=True)

    # Find all PNG files
    png_files = list(SOURCE_DIR.glob("*.png"))

    if not png_files:
        print(f"❌ No PNG files found in {SOURCE_DIR}")
        sys.exit(1)

    print(f"Found {len(png_files)} screenshot(s) to process\n")

    # Process each screenshot
    results = []
    for input_file in png_files:
        output_file = OUTPUT_DIR / input_file.name
        print(f"Processing {input_file.name}...")

        try:
            width, height = process_screenshot(input_file, output_file)

            # Verify minimum dimension
            if width < MIN_DIMENSION or height < MIN_DIMENSION:
                print(f"  ⚠️  Warning: Image smaller than {MIN_DIMENSION}px ({width}x{height})")
            else:
                print(f"  ✅ {width}x{height}")

            results.append((output_file.name, width, height, output_file.stat().st_size))

        except Exception as e:
            print(f"  ❌ Error: {e}")

    # Summary
    print()
    print("✅ Processing complete!")
    print(f"📁 Processed {len(results)} screenshot(s) to: {OUTPUT_DIR}")
    print()
    print("Output files:")
    for name, width, height, size in sorted(results):
        size_mb = size / (1024 * 1024)
        print(f"   {name:30s} {width:4d}x{height:4d} ({size_mb:.2f} MB)")

if __name__ == "__main__":
    # Check if PIL is available
    try:
        import PIL
    except ImportError:
        print("❌ Error: Pillow (PIL) not found")
        print("   Install with: pip install Pillow")
        sys.exit(1)

    main()
