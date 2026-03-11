# Screenshot Processing Guide

Play Store requires screenshots that are clean and professional. Current screenshots need status bars removed.

## Current Screenshots Location
`docs/screenshots/`

## Screenshots to Process (7 total)

1. `log_tab.png` - Transcription history
2. `model_tab_top.png` - Model selection (top)
3. `model_tab_bottom.png` - Model selection (bottom)
4. `settings_tab_top.png` - Settings (top)
5. `settings_tab_mid.png` - Settings (middle)
6. `settings_tab_bottom.png` - Settings (bottom)
7. `notification.png` - Notification result

## Processing Requirements

### What to Remove
- **Status bar** (top ~70-100 pixels): time, battery, signal icons
- **Navigation bar** (bottom ~50-80 pixels): back/home/recents buttons
- Keep the app content only

### Target Specifications
- **Aspect Ratio:** 16:9 or 9:16
- **Min size:** 320px on shortest side
- **Max size:** 3840px on longest side
- **Format:** PNG or JPG (24-bit color)
- **Language:** Ensure English locale is visible

## Processing Method 1: ImageMagick (Recommended for Batch)

```bash
# Install ImageMagick if needed
sudo apt install imagemagick  # Linux
brew install imagemagick        # macOS

# Process all screenshots - remove status bar (top 85px) and navbar (bottom 60px)
mkdir -p docs/play-store/screenshots

for file in docs/screenshots/*.png; do
    filename=$(basename "$file")
    echo "Processing $filename..."

    # Get image dimensions
    width=$(identify -format "%w" "$file")
    height=$(identify -format "%h" "$file")

    # Crop: remove top 85px and bottom 60px
    convert "$file" -crop ${width}x$((height - 145))+0+85 "docs/play-store/screenshots/$filename"
done

echo "Screenshots processed in docs/play-store/screenshots/"
```

## Processing Method 2: Python with PIL

```python
#!/usr/bin/env python3
"""Process screenshots for Play Store - remove status bar and navigation bar."""

from PIL import Image
import os
from pathlib import Path

# Configuration
SOURCE_DIR = Path("docs/screenshots")
OUTPUT_DIR = Path("docs/play-store/screenshots")
STATUS_BAR_HEIGHT = 85  # Pixels to remove from top
NAV_BAR_HEIGHT = 60      # Pixels to remove from bottom

# Create output directory
OUTPUT_DIR.mkdir(parents=True, exist_ok=True)

# Process each screenshot
for screenshot_file in SOURCE_DIR.glob("*.png"):
    print(f"Processing {screenshot_file.name}...")

    # Open image
    img = Image.open(screenshot_file)
    width, height = img.size

    # Calculate crop box: (left, top, right, bottom)
    crop_box = (
        0,
        STATUS_BAR_HEIGHT,
        width,
        height - NAV_BAR_HEIGHT
    )

    # Crop and save
    cropped = img.crop(crop_box)

    # Ensure output dimensions meet Play Store requirements
    # Minimum 320px on shortest side
    if min(cropped.size) < 320:
        print(f"  ⚠️  Warning: {screenshot_file.name} is smaller than 320px")

    # Save to output
    output_path = OUTPUT_DIR / screenshot_file.name
    cropped.save(output_path, "PNG")
    print(f"  ✅ Saved: {output_path.name} ({cropped.size[0]}x{cropped.size[1]})")

print("\n✅ All screenshots processed!")
print(f"Output location: {OUTPUT_DIR.absolute()}")
```

Save as `scripts/process_screenshots.py` and run with `python3 scripts/process_screenshots.py`

## Processing Method 3: Manual (GIMP/Photoshop)

### GIMP Instructions:
1. Open screenshot
2. Select → Rectangle Select
3. Set Fixed: Aspect Ratio = 16:9 or 9:16
4. Draw selection excluding status bar and navigation bar
5. Image → Crop to Selection
6. File → Export As → PNG

### Photoshop Instructions:
1. Open screenshot
2. Select Crop Tool (C)
3. Set aspect ratio to 16:9 or 9:16 in options bar
4. Drag to exclude status bar and navbar
5. Press Enter to crop
6. File → Export → Save for Web (Legacy)

## Verification Checklist

After processing, verify each screenshot:

- [ ] Status bar completely removed (no time/battery visible)
- [ ] Navigation bar removed (no back/home buttons visible)
- [ ] App content is fully visible and not cut off
- [ ] English locale text is visible (not Italian)
- [ ] Minimum 320px on shortest side
- [ ] Maximum 3840px on longest side
- [ ] File size reasonable (under 2MB per screenshot)
- [ ] PNG format with good quality

## Final Output Structure

```
docs/play-store/
├── screenshots/
│   ├── log_tab.png              (transcription history)
│   ├── model_tab_top.png        (model selection top)
│   ├── model_tab_bottom.png     (model selection bottom)
│   ├── settings_tab_top.png     (settings top)
│   ├── settings_tab_mid.png     (settings middle)
│   ├── settings_tab_bottom.png  (settings bottom)
│   └── notification.png         (notification result)
├── feature-graphic.png          (1024x500)
├── store-icon-512.png           (512x512)
└── store-listing.md
```

## Quick Batch Script (Bash)

```bash
#!/bin/bash
# Quick screenshot processing for Play Store

set -e

SOURCE_DIR="docs/screenshots"
OUTPUT_DIR="docs/play-store/screenshots"

# Create output directory
mkdir -p "$OUTPUT_DIR"

# Process each PNG file
for file in "$SOURCE_DIR"/*.png; do
    if [ -f "$file" ]; then
        filename=$(basename "$file")
        echo "Processing $filename..."

        # Using ImageMagick
        # Remove top 85px (status bar) and bottom 60px (navigation bar)
        convert "$file" -gravity North -chop 0x85 -gravity South -chop 0x60 \
            "$OUTPUT_DIR/$filename"

        echo "  ✅ Saved to $OUTPUT_DIR/$filename"
    fi
done

echo ""
echo "✅ Processing complete!"
echo "📁 Output directory: $OUTPUT_DIR"
echo ""
echo "Verifying dimensions..."
for file in "$OUTPUT_DIR"/*.png; do
    dims=$(identify -format "%wx%h" "$file")
    echo "  $(basename "$file"): $dims"
done
```

Save as `scripts/process_screenshots.sh` and run with `bash scripts/process_screenshots.sh`

## Notes

- **Status bar height varies** by device: 72-100px typical
- **Navigation bar height varies:** 48-84px typical
- **Measure your screenshots first** to determine exact crop values
- For the Realme RMX3853, the status bar appears to be ~85px
- Always keep a backup of original screenshots before processing
