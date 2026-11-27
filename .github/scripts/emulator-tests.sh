#!/usr/bin/env bash
# Runs the instrumented tests on the booted emulator and collects logcat and
# screenshots into app/build/ci-artifacts/. Started by
# reactivecircus/android-emulator-runner after stabilize-emulator.sh.
set -uo pipefail

out=app/build/ci-artifacts
mkdir -p "$out"

# stabilize-emulator.sh turns this on so a Pixel Launcher ANR dialog cannot
# steal focus. Restore it even if the tests fail. The emulator-runner has
# already shut the AVD down by the time a later always() step would run.
restore_error_dialogs() {
    adb shell settings put global hide_error_dialogs 0 >/dev/null 2>&1 || true
}
trap restore_error_dialogs EXIT

adb logcat -c
adb logcat -v threadtime > "$out/logcat.txt" &
logcat_pid=$!

./gradlew connectedDebugAndroidTest --stacktrace
status=$?

# Optional workflow_dispatch switch: crash the app after the Gradle run so
# the logcat gate can be verified without changing application code. The
# process may already have been closed by ActivityScenario.
if [[ "${INJECT_APP_CRASH:-}" == "true" ]]; then
    echo "emulator-tests: injecting a FATAL EXCEPTION in me.zhanghai.android.files to verify the logcat gate" >&2
    adb shell monkey -p me.zhanghai.android.files -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1 || true
    sleep 2
    adb shell am crash me.zhanghai.android.files || true
    # Let logcat flush the FATAL block before we stop the capture.
    sleep 2
fi

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
    # Fail only when a FATAL EXCEPTION belongs to this app. The program lives
    # in logcat-crash-gate.awk so END { exit found } cannot be overwritten.
    if ! awk -f .github/scripts/logcat-crash-gate.awk "$out/logcat.txt"; then
        echo "emulator-tests: FATAL EXCEPTION in me.zhanghai.android.files (see logcat.txt)" >&2
        status=1
    fi
    if grep -Eq 'ANR in me[.]zhanghai[.]android[.]files($|[^A-Za-z0-9_.])' "$out/logcat.txt"; then
        echo "emulator-tests: ANR in me.zhanghai.android.files (see logcat.txt)" >&2
        status=1
    fi
fi

exit "$status"
