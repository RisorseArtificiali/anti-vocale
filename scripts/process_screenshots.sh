#!/bin/bash
# Screenshot Processing Script for Play Store
# Removes status bar (top) and navigation bar (bottom) from screenshots

set -e

# Source and output directories
SOURCE_DIR="docs/screenshots"
OUTPUT_DIR="docs/play-store/screenshots"

# Crop dimensions (adjust based on your device)
STATUS_BAR_HEIGHT=85   # Pixels to remove from top
NAV_BAR_HEIGHT=60      # Pixels to remove from bottom

echo "🖼️  Processing screenshots for Play Store..."
echo "   Source: $SOURCE_DIR"
echo "   Output: $OUTPUT_DIR"
echo ""

# Create output directory
mkdir -p "$OUTPUT_DIR"

# Check if ImageMagick is available
if ! command -v convert &> /dev/null; then
    echo "❌ Error: ImageMagick not found"
    echo "   Install with: sudo apt install imagemagick"
    exit 1
fi

# Process each PNG file
count=0
for file in "$SOURCE_DIR"/*.png; do
    if [ -f "$file" ]; then
        filename=$(basename "$file")
        echo "Processing $filename..."

        # Get original dimensions
        orig_width=$(identify -format "%w" "$file")
        orig_height=$(identify -format "%h" "$file")

        # Calculate new height
        new_height=$((orig_height - STATUS_BAR_HEIGHT - NAV_BAR_HEIGHT))

        # Crop using ImageMagick
        convert "$file" -crop ${orig_width}x${new_height}+0+${STATUS_BAR_HEIGHT} \
            "$OUTPUT_DIR/$filename"

        # Verify output
        out_width=$(identify -format "%w" "$OUTPUT_DIR/$filename")
        out_height=$(identify -format "%h" "$OUTPUT_DIR/$filename")

        # Check minimum size requirement
        if [ $out_width -lt 320 ] || [ $out_height -lt 320 ]; then
            echo "  ⚠️  Warning: Image smaller than 320px (${out_width}x${out_height})"
        else
            echo "  ✅ ${out_width}x${out_height}"
        fi

        count=$((count + 1))
    fi
done

echo ""
echo "✅ Processing complete!"
echo "📁 Processed $count screenshots to: $OUTPUT_DIR"
echo ""
echo "Output files:"
ls -lh "$OUTPUT_DIR"/*.png | awk '{print "   " $9 " (" $5 ")"}'
