#!/usr/bin/env bash
# Make the emulator usable for UI tests: hide system error / ANR dialogs, turn
# animations off, wait for idle after boot, and dismiss a leftover
# "isn't responding" dialog if one is already on screen. The Pixel 6 AVD has
# shown a "Pixel Launcher isn't responding" dialog that steals focus from
# Espresso (RootViewWithoutFocusException). Called before the test run and
# when a cold AVD snapshot is generated, so the cached snapshot is clean too.
set -uo pipefail

adb wait-for-device

for _ in $(seq 1 60); do
    completed=$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')
    if [[ "$completed" == "1" ]]; then
        break
    fi
    sleep 1
done

adb shell settings put global hide_error_dialogs 1 || true
adb shell settings put global window_animation_scale 0 || true
adb shell settings put global transition_animation_scale 0 || true
adb shell settings put global animator_duration_scale 0 || true

# Let the launcher finish its first draw before we snapshot or start tests.
adb shell am wait-for-broadcast-idle || true
sleep 2
adb shell am wait-for-broadcast-idle || true

adb shell am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS || true

dump_ui() {
    local dest="$1"
    adb shell uiautomator dump /sdcard/ui-dump.xml >/dev/null 2>&1 || return 1
    adb pull /sdcard/ui-dump.xml "$dest" >/dev/null 2>&1 || return 1
    return 0
}

has_anr_dialog() {
    local dump="$1"
    [[ -f "$dump" ]] || return 1
    grep -Eqi "isn('|&\#39;|’)t responding|isn.t responding" "$dump"
}

# Print "x y" for the center of the first node whose text matches $2.
tap_point_for_text() {
    local dump="$1"
    local label="$2"
    python3 - "$dump" "$label" <<'PYCODE'
import re, sys
path, label = sys.argv[1], sys.argv[2]
text = open(path, encoding="utf-8", errors="replace").read()
# text may appear before or after bounds on the same node.
patterns = [
    r'text="%s"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"' % re.escape(label),
    r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"[^>]*text="%s"' % re.escape(label),
]
for pat in patterns:
    match = re.search(pat, text)
    if match:
        x1, y1, x2, y2 = map(int, match.groups())
        print((x1 + x2) // 2, (y1 + y2) // 2)
        sys.exit(0)
sys.exit(1)
PYCODE
}

dismiss_anr_if_present() {
    local dump
    dump=$(mktemp /tmp/ui-dump.XXXXXX.xml)
    if ! dump_ui "$dump"; then
        rm -f "$dump"
        return 0
    fi
    if ! has_anr_dialog "$dump"; then
        rm -f "$dump"
        return 0
    fi
    echo "stabilize-emulator: ANR / not-responding dialog is on screen; dismissing" >&2
    # Prefer Wait so the launcher is not killed; fall back to Close app.
    local label point
    for label in "Wait" "Close app" "Close" "OK"; do
        if point=$(tap_point_for_text "$dump" "$label"); then
            # shellcheck disable=SC2086
            adb shell input tap $point || true
            sleep 1
            adb shell am wait-for-broadcast-idle || true
            if dump_ui "$dump" && ! has_anr_dialog "$dump"; then
                rm -f "$dump"
                return 0
            fi
        fi
    done
    adb shell am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS || true
    adb shell input keyevent KEYCODE_HOME || true
    adb shell am wait-for-broadcast-idle || true
    rm -f "$dump"
}

dismiss_anr_if_present
adb shell am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS || true
