#!/bin/bash
# Render art PNGs from the SVG sources in this directory.
#
# The launcher icon’s “MF” lettering is Outfit SemiBold converted to paths
# (SIL Open Font License v1.1, https://github.com/Outfitio/Outfit-Fonts).
# Regenerating the launcher PNGs requires rsvg-convert and Python 3 with
# Pillow. Shortcut and banner PNGs still use Inkscape when it is available.
set -e
cd "$(dirname "$0")"

python3 generate_launcher_png.py

if ! command -v inkscape >/dev/null 2>&1; then
    echo "inkscape not found; skipping shortcut and banner PNG regeneration" >&2
    exit 0
fi

for shortcut in directory downloads file ftp_server; do
    inkscape -o "${shortcut}_shortcut_icon-mdpi.png" --export-area=15:15:93:93 -w 48 -h 48 "${shortcut}_shortcut_icon.svg"
    inkscape -o "${shortcut}_shortcut_icon-hdpi.png" --export-area=15:15:93:93 -w 72 -h 72 "${shortcut}_shortcut_icon.svg"
    inkscape -o "${shortcut}_shortcut_icon-xhdpi.png" --export-area=15:15:93:93 -w 96 -h 96 "${shortcut}_shortcut_icon.svg"
    inkscape -o "${shortcut}_shortcut_icon-xxhdpi.png" --export-area=15:15:93:93 -w 144 -h 144 "${shortcut}_shortcut_icon.svg"
    inkscape -o "${shortcut}_shortcut_icon-xxxhdpi.png" --export-area=15:15:93:93 -w 192 -h 192 "${shortcut}_shortcut_icon.svg"
done

inkscape -o banner-xhdpi.png --export-area=0:0:320:180 -w 320 -h 180 banner.svg
inkscape -o banner-play.png --export-area=0:0:320:180 -w 1280 -h 720 banner.svg
