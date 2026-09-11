#!/usr/bin/env python3
"""Regenerates the derei launcher-icon foreground WebPs (GH #61, TASK-473).

Source: the six concept tiles attached to GH #61, cropped as
/tmp/issue61/v1..v6.png from the original attachment (re-fetch from the
issue if lost). Requires Pillow + scipy: pip install pillow scipy

Maintainer decision 2026-09-11: faithful raster re-export from the proposal
pixels, reversing TASK-473's earlier vector redraws (the redraws were "not
really exactly those that had been shared"). The foreground layers are
glyph-only: the adaptive background drawable supplies the gradient, the
monochrome layer reuses the foreground's alpha.

Glyph extraction is color classification, not background subtraction: the
artwork uses exactly two colors (cream fills, the shared maroon cut tone);
everything else, including the tile's own gradient, goes transparent. A
median filter strips the attachment's compression noise before the 1px
feather, which is what keeps the lossless WebPs small.

Usage: python3 scripts/export-derei-icons.py <dir-with-v1..v6.png> <res-dir>
"""
import sys
from pathlib import Path

import numpy as np
from PIL import Image, ImageFilter
from scipy import ndimage  # noqa: F401  (kept for future mask work)

SLUGS = [('v1.png', 'wavecut'), ('v2.png', 'crossed'), ('v3.png', 'textblock'),
         ('v4.png', 'monogram'), ('v5.png', 'capsule'), ('v6.png', 'mutebar')]
DENSITIES = {'mdpi': 108, 'hdpi': 162, 'xhdpi': 216, 'xxhdpi': 324, 'xxxhdpi': 432}
GLYPH_SPAN = 0.65  # of the 108dp canvas: ~99% of the 72dp launcher-visible circle


def extract_glyph(im: Image.Image) -> Image.Image:
    a = np.asarray(im.convert('RGB')).astype(float)
    r, g, b = a[:, :, 0], a[:, :, 1], a[:, :, 2]
    lum = a.mean(axis=2)
    cream = (lum > 190) & (np.abs(r - b) < 60)
    maroon = (r > 100) & (r < 190) & (g > 25) & (g < 95) & (b < 70) & (r - b > 70)
    m = Image.fromarray(((cream | maroon) * 255).astype(np.uint8), 'L')
    alpha = m.filter(ImageFilter.MedianFilter(3)).filter(ImageFilter.GaussianBlur(1.0))
    return Image.fromarray(np.dstack([a, np.asarray(alpha, dtype=float)]).astype(np.uint8), 'RGBA')


def main(src_dir: str, res_dir: str) -> None:
    src = Path(src_dir)
    res = Path(res_dir)
    for name, slug in SLUGS:
        tight = extract_glyph(Image.open(src / name))
        tight = tight.crop(tight.getchannel('A').getbbox())
        for dens, size in DENSITIES.items():
            canvas = Image.new('RGBA', (size, size), (0, 0, 0, 0))
            target = round(size * GLYPH_SPAN)
            g = tight.resize((target, target), Image.LANCZOS)
            canvas.paste(g, ((size - target) // 2, (size - target) // 2), g)
            canvas.save(res / f'mipmap-{dens}' / f'ic_launcher_fg_derei_{slug}.webp',
                        'WEBP', lossless=True)
    print(f'regenerated {len(SLUGS) * len(DENSITIES)} foreground WebPs under {res}')


if __name__ == '__main__':
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    main(sys.argv[1], sys.argv[2])
