# Art

Launcher, shortcut, and banner artwork for the app.

The launcher icon is an adaptive icon: a folder with “MF” lettering sitting on
two server plates, on a navy background (`#1B1740`). The lettering is Outfit
SemiBold converted to paths (SIL Open Font License v1.1,
https://github.com/Outfitio/Outfit-Fonts). No font file is bundled with the
app.

To regenerate the launcher PNGs (legacy densities, round variant, Play Store
512 px, and web), run `./generate-png.sh` in this directory (requires
`rsvg-convert` and Python 3 with Pillow). Then run `./deploy-png.sh` to copy
them into `app/src/main/res` and Fastlane metadata. Shortcut and banner
regeneration still uses Inkscape when it is installed.
