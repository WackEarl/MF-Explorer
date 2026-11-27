#!/usr/bin/env bash
# Runs the instrumented tests on the booted emulator and collects logcat and
# screenshots into app/build/ci-artifacts/. Started by
# reactivecircus/android-emulator-runner after stabilize-emulator.sh.
set -uo pipefail

out=app/build/ci-artifacts
mkdir -p "$out"

adb logcat -c
adb logcat -v threadtime > "$out/logcat.txt" &
logcat_pid=$!

./gradlew connectedDebugAndroidTest --stacktrace
status=$?

# Final screen state after the Gradle run. ActivityScenario has already closed
# the app, so this pair usually shows the launcher, not the last test.
# Per-failure screenshots and UI dumps are written by FailureArtifactsRule
# while the activity is still open (see useCapturing) and copied by AGP to
# app/build/outputs/connected_android_test_additional_output/.
adb exec-out screencap -p > "$out/screenshot-final.png" || true
adb shell dumpsys activity activities > "$out/activities.txt" || true

# Fallback pull when additionalTestOutputDir was not used.
adb pull /sdcard/Android/data/me.zhanghai.android.files/files/ui-smoke-failures \
    "$out/failures" 2>/dev/null || true

kill "$logcat_pid" 2>/dev/null || true
wait "$logcat_pid" 2>/dev/null || true

if [[ -f "$out/logcat.txt" ]]; then
    # Fail only when a FATAL EXCEPTION belongs to this app. awk avoids a
    # grep pipeline that can SIGPIPE under pipefail when many FATAL blocks exist.
    if ! awk '
        /FATAL EXCEPTION/ { fatal = 1; next }
        fatal && /Process: me[.]zhanghai[.]android[.]files($|[^A-Za-z0-9_.])/ { exit 1 }
        fatal && /Process:/ { fatal = 0 }
        END { exit 0 }
    ' "$out/logcat.txt"; then
        echo "emulator-tests: FATAL EXCEPTION in me.zhanghai.android.files (see logcat.txt)" >&2
        status=1
    fi
    if grep -q "ANR in me.zhanghai.android.files" "$out/logcat.txt"; then
        echo "emulator-tests: ANR in me.zhanghai.android.files (see logcat.txt)" >&2
        status=1
    fi
fi

exit "$status"
