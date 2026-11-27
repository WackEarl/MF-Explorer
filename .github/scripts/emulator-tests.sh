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

# Final screen state; helps to see what the emulator showed when a test failed.
adb exec-out screencap -p > "$out/screenshot-final.png" || true
adb shell dumpsys activity activities > "$out/activities.txt" || true

kill "$logcat_pid" 2>/dev/null || true
exit "$status"
