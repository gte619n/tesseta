#!/usr/bin/env python3
"""Generate raster launcher-icon PNG fallbacks for the Android app.

The app ships an adaptive (vector) launcher icon under
``mipmap-anydpi-v26/`` which API 26+ devices render natively. But the
adaptive-icon XML is NOT a raster, and tools that extract an app's icon from
the built APK by reading ``android:icon`` -- notably Firebase App Distribution,
which renders the icon in its console and notification emails -- cannot
rasterize an adaptive vector. With no PNG fallback present they show a blank
placeholder, which is why distribution emails looked iconless.

This script renders PNG fallbacks for every density bucket so the icon
resolves to a real bitmap everywhere. Run it whenever the brand mark in
``docs/logo/app-icon-light.svg`` changes:

    pip install Pillow
    python3 infra/scripts/gen-android-icons.py

Geometry mirrors docs/logo/app-icon-light.svg (120x120 viewBox).
"""

from __future__ import annotations

import os

from PIL import Image, ImageDraw

# Brand palette (docs/logo/LOGO-SPEC.md).
OATMEAL = (240, 235, 224, 255)  # #F0EBE0 background field
OLIVE = (92, 122, 46, 255)      # #5C7A2E tiles

# The 8-tile Tesseta mark (center omitted), in the 120x120 logo viewBox.
VIEWBOX = 120.0
TILE = 16.5
TILE_RADIUS = 2.5
BG_RADIUS = 26.0  # rounded-square corner radius in the 120 viewBox
TILE_ORIGINS = [
    (34.0, 34.0), (51.75, 34.0), (69.5, 34.0),
    (34.0, 51.75),               (69.5, 51.75),
    (34.0, 69.5), (51.75, 69.5), (69.5, 69.5),
]

# Standard launcher-icon edge length per density bucket.
DENSITIES = {
    "mdpi": 48,
    "hdpi": 72,
    "xhdpi": 96,
    "xxhdpi": 144,
    "xxxhdpi": 192,
}

# Supersample factor for crisp antialiased edges, then downscale.
SS = 8


def _render(size: int, round_icon: bool) -> Image.Image:
    s = size * SS
    scale = s / VIEWBOX
    img = Image.new("RGBA", (s, s), (0, 0, 0, 0))
    draw = ImageDraw.Draw(img)

    if round_icon:
        draw.ellipse([0, 0, s - 1, s - 1], fill=OATMEAL)
    else:
        draw.rounded_rectangle(
            [0, 0, s - 1, s - 1], radius=BG_RADIUS * scale, fill=OATMEAL
        )

    for x, y in TILE_ORIGINS:
        x0, y0 = x * scale, y * scale
        x1, y1 = (x + TILE) * scale, (y + TILE) * scale
        draw.rounded_rectangle(
            [x0, y0, x1, y1], radius=TILE_RADIUS * scale, fill=OLIVE
        )

    return img.resize((size, size), Image.LANCZOS)


def main() -> None:
    here = os.path.dirname(os.path.abspath(__file__))
    res_dir = os.path.normpath(
        os.path.join(here, "..", "..", "android", "app", "src", "main", "res")
    )
    for bucket, size in DENSITIES.items():
        out_dir = os.path.join(res_dir, f"mipmap-{bucket}")
        os.makedirs(out_dir, exist_ok=True)
        _render(size, round_icon=False).save(
            os.path.join(out_dir, "ic_launcher.png")
        )
        _render(size, round_icon=True).save(
            os.path.join(out_dir, "ic_launcher_round.png")
        )
        print(f"wrote mipmap-{bucket}/ic_launcher{{,_round}}.png ({size}px)")


if __name__ == "__main__":
    main()
