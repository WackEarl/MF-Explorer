#!/usr/bin/env python3
"""Render launcher-icon PNGs from the SVG sources in this directory.

Requires rsvg-convert and Pillow. The “MF” lettering in the SVGs is Outfit
SemiBold converted to paths (SIL Open Font License v1.1,
https://github.com/Outfitio/Outfit-Fonts).
"""
from __future__ import annotations

import math
import subprocess
import tempfile
from pathlib import Path

from PIL import Image, ImageChops, ImageDraw, ImageFilter

ART = Path(__file__).resolve().parent
DENSITIES = {
    "mdpi": 1,
    "hdpi": 1.5,
    "xhdpi": 2,
    "xxhdpi": 3,
    "xxxhdpi": 4,
}


def rsvg(svg: Path, png: Path, size: int) -> None:
    subprocess.run(
        ["rsvg-convert", "-w", str(size), "-h", str(size), str(svg), "-o", str(png)],
        check=True,
    )


def squircle_points(size: int, n: float = 5.0):
    cx = cy = (size - 1) / 2.0
    a = b = size / 2.0
    steps = max(360, size)
    pts = []
    for i in range(steps):
        t = 2 * math.pi * i / steps
        ct, st = math.cos(t), math.sin(t)
        x = cx + a * math.copysign(abs(ct) ** (2 / n), ct)
        y = cy + b * math.copysign(abs(st) ** (2 / n), st)
        pts.append((x, y))
    return pts


def apply_mask(img: Image.Image, shape: str) -> Image.Image:
    w, h = img.size
    mask = Image.new("L", (w, h), 0)
    draw = ImageDraw.Draw(mask)
    if shape == "circle":
        draw.ellipse((0, 0, w - 1, h - 1), fill=255)
    else:
        draw.polygon(squircle_points(w, n=5.0), fill=255)
    mask = mask.filter(ImageFilter.GaussianBlur(radius=max(0.6, w / 720)))
    out = img.convert("RGBA")
    _r, _g, _b, a = out.split()
    out.putalpha(ImageChops.multiply(a, mask))
    return out


def crop_visible(full: Image.Image) -> Image.Image:
    # 72 dp of the 108 dp adaptive-icon canvas (inset 18 dp).
    vis = int(round(full.size[0] * 72 / 108))
    pad = (full.size[0] - vis) // 2
    return full.crop((pad, pad, pad + vis, pad + vis))


def visible_icon(full: Image.Image, shape: str, out_size: int) -> Image.Image:
    cropped = apply_mask(crop_visible(full), shape)
    return cropped.resize((out_size, out_size), Image.Resampling.LANCZOS)


def visible_square(full: Image.Image, out_size: int) -> Image.Image:
    return crop_visible(full).resize((out_size, out_size), Image.Resampling.LANCZOS)


def main() -> None:
    with tempfile.TemporaryDirectory() as tmpdir:
        tmp = Path(tmpdir)
        rsvg(ART / "launcher_icon.svg", tmp / "full.png", 1080)
        rsvg(ART / "launcher_icon_foreground.svg", tmp / "fg.png", 1080)
        full = Image.open(tmp / "full.png").convert("RGBA")
        fg = Image.open(tmp / "fg.png").convert("RGBA")

    for name, scale in DENSITIES.items():
        size = int(48 * scale)
        visible_icon(full, "squircle", size).save(ART / f"launcher_icon-{name}.png")
        visible_icon(full, "circle", size).save(ART / f"launcher_icon_round-{name}.png")
        fg_size = int(108 * scale)
        fg.resize((fg_size, fg_size), Image.Resampling.LANCZOS).save(
            ART / f"launcher_icon_foreground-{name}.png"
        )
        print(f"{name}: {size}px icon, {fg_size}px foreground")

    visible_square(full, 512).save(ART / "launcher_icon-play.png")
    visible_icon(full, "circle", 512).save(ART / "launcher_icon-web.png")
    print("play and web 512px written")


if __name__ == "__main__":
    main()
