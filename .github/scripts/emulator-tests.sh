#!/usr/bin/env bash
# Runs the instrumented tests on the booted emulator and collects logcat and screenshots into
# app/build/ci-artifacts/. Started by reactivecircus/android-emulator-runner.
set -uo pipefail

out=app/build/ci-artifacts
mkdir -p "$out"

adb logcat -c
adb logcat -v threadtime > "$out/logcat.txt" &
logcat_pid=$!

./gradlew connectedDebugAndroidTest --stacktrace
status=$?

# Final screen state after the Gradle run. ActivityScenario has already closed the app, so
# this pair usually shows the launcher, not the last test. Per-failure screenshots and UI
# dumps are written by FailureArtifactsRule while the failing screen is still up.
adb exec-out screencap -p > "$out/screenshot-final.png" || true
adb shell dumpsys activity activities > "$out/activities.txt" || true

# Pull per-failure artifacts when additionalTestOutputDir was not used.
adb pull /sdcard/Android/data/me.zhanghai.android.files/files/ui-smoke-failures     "$out/failures" 2>/dev/null || true

kill "$logcat_pid" 2>/dev/null || true
wait "$logcat_pid" 2>/dev/null || true

if [[ -f "$out/logcat.txt" ]]; then
    if grep -A5 "FATAL EXCEPTION" "$out/logcat.txt" | grep -q "Process: me.zhanghai.android.files"; then
        echo "emulator-tests: FATAL EXCEPTION in me.zhanghai.android.files (see logcat.txt)" >&2
        status=1
    fi
    if grep -q "ANR in me.zhanghai.android.files" "$out/logcat.txt"; then
        echo "emulator-tests: ANR in me.zhanghai.android.files (see logcat.txt)" >&2
        status=1
    fi
fi

exit "$status"
