#!/bin/bash
set -e
cd "$(dirname "$0")"

for dpi in {m,h,xh,xxh,xxxh}dpi; do
    cp "launcher_icon-${dpi}.png" "../app/src/main/res/mipmap-${dpi}/launcher_icon.png"
    cp "launcher_icon_round-${dpi}.png" "../app/src/main/res/mipmap-${dpi}/launcher_icon_round.png"
done

cp launcher_icon-play.png ../fastlane/metadata/android/en-US/images/icon.png

for shortcut in directory downloads file ftp_server; do
    for dpi in {m,h,xh,xxh,xxxh}dpi; do
        cp "${shortcut}_shortcut_icon-${dpi}.png" "../app/src/main/res/mipmap-${dpi}/${shortcut}_shortcut_icon.png"
    done
done

cp banner-xhdpi.png ../app/src/main/res/drawable-xhdpi/banner.png
cp banner-play.png ../fastlane/metadata/android/en-US/images/tvBanner.png
