#!/usr/bin/env bash
# Local checks for the logcat crash/ANR gate, run with mawk and gawk.
set -euo pipefail

root=$(cd "$(dirname "$0")/../.." && pwd)
awk_file="$root/.github/scripts/logcat-crash-gate.awk"
failed=0

run_fatal() {
    local engine="$1"
    local label="$2"
    local expect="$3"
    local log="$4"
    set +e
    "$engine" -f "$awk_file" "$log"
    local status=$?
    set -e
    if [[ "$status" -eq "$expect" ]]; then
        echo "ok   $engine FATAL $label (exit $status)"
    else
        echo "FAIL $engine FATAL $label (exit $status, expected $expect)" >&2
        failed=1
    fi
}

# Same pattern as emulator-tests.sh. grep -q exits 0 when an app ANR is present.
ANR_PATTERN='ANR in me[.]zhanghai[.]android[.]files($|[^A-Za-z0-9_.])'

run_anr() {
    local label="$1"
    local expect="$2"
    local log="$3"
    set +e
    grep -Eq "$ANR_PATTERN" "$log"
    local status=$?
    set -e
    if [[ "$status" -eq "$expect" ]]; then
        echo "ok   grep ANR   $label (exit $status)"
    else
        echo "FAIL grep ANR   $label (exit $status, expected $expect)" >&2
        failed=1
    fi
}

write_log() {
    cat
}

tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT

# App FATAL block, as logcat-v threadtime prints it.
write_log > "$tmp/app-fatal.txt" <<'LOG'
11-27 13:00:00.000  1234  1234 E AndroidRuntime: FATAL EXCEPTION: main
11-27 13:00:00.000  1234  1234 E AndroidRuntime: Process: me.zhanghai.android.files
11-27 13:00:00.000  1234  1234 E AndroidRuntime: java.lang.RuntimeException: boom
LOG

# Comma after the process name (some printers use "Process: pkg, PID: n").
write_log > "$tmp/app-fatal-comma.txt" <<'LOG'
11-27 13:00:00.000  1234  1234 E AndroidRuntime: FATAL EXCEPTION: main
11-27 13:00:00.000  1234  1234 E AndroidRuntime: Process: me.zhanghai.android.files, PID: 1234
11-27 13:00:00.000  1234  1234 E AndroidRuntime: java.lang.RuntimeException: boom
LOG

# Test package must not trip the gate.
write_log > "$tmp/test-fatal.txt" <<'LOG'
11-27 13:00:00.000  1234  1234 E AndroidRuntime: FATAL EXCEPTION: main
11-27 13:00:00.000  1234  1234 E AndroidRuntime: Process: me.zhanghai.android.files.test
11-27 13:00:00.000  1234  1234 E AndroidRuntime: java.lang.RuntimeException: boom
LOG

# Unrelated process after a FATAL, plus a FATAL without Process: (uiautomator).
write_log > "$tmp/foreign-and-uiautomator.txt" <<'LOG'
11-27 13:00:00.000  1111  1111 E AndroidRuntime: FATAL EXCEPTION: main
11-27 13:00:00.000  1111  1111 E AndroidRuntime: java.lang.IllegalStateException: UiAutomationService already registered!
11-27 13:00:01.000  2222  2222 E AndroidRuntime: FATAL EXCEPTION: main
11-27 13:00:01.000  2222  2222 E AndroidRuntime: Process: com.google.android.apps.nexuslauncher
11-27 13:00:01.000  2222  2222 E AndroidRuntime: java.lang.RuntimeException: boom
LOG

# Many foreign FATALs, then one app crash (the pipefail case).
{
    for i in $(seq 1 200); do
        printf '11-27 13:00:00.000  %s  %s E AndroidRuntime: FATAL EXCEPTION: main\n' "$i" "$i"
        printf '11-27 13:00:00.000  %s  %s E AndroidRuntime: Process: com.example.app%s\n' "$i" "$i" "$i"
        printf '11-27 13:00:00.000  %s  %s E AndroidRuntime: java.lang.RuntimeException: boom\n' "$i" "$i"
    done
    cat "$tmp/app-fatal.txt"
} > "$tmp/many-then-app.txt"

write_log > "$tmp/app-anr.txt" <<'LOG'
11-27 12:44:38.177   521  2585 E ActivityManager: ANR in me.zhanghai.android.files (me.zhanghai.android.files/.filelist.FileListActivity)
11-27 12:44:38.177   521  2585 E ActivityManager: PID: 1234
LOG

write_log > "$tmp/launcher-anr.txt" <<'LOG'
11-27 12:44:38.177   521  2585 E ActivityManager: ANR in com.google.android.apps.nexuslauncher (com.google.android.apps.nexuslauncher/.NexusLauncherActivity)
11-27 12:44:38.177   521  2585 E ActivityManager: PID: 1234
LOG

write_log > "$tmp/test-anr.txt" <<'LOG'
11-27 12:44:38.177   521  2585 E ActivityManager: ANR in me.zhanghai.android.files.test
LOG

for engine in mawk gawk; do
    run_fatal "$engine" "app process" 1 "$tmp/app-fatal.txt"
    run_fatal "$engine" "app process with comma" 1 "$tmp/app-fatal-comma.txt"
    run_fatal "$engine" "test package" 0 "$tmp/test-fatal.txt"
    run_fatal "$engine" "foreign and uiautomator" 0 "$tmp/foreign-and-uiautomator.txt"
    run_fatal "$engine" "many foreign then app" 1 "$tmp/many-then-app.txt"
done

run_anr "app process" 0 "$tmp/app-anr.txt"
run_anr "launcher" 1 "$tmp/launcher-anr.txt"
run_anr "test package" 1 "$tmp/test-anr.txt"

if [[ "$failed" -ne 0 ]]; then
    echo "test-logcat-crash-gate: FAILED" >&2
    exit 1
fi
echo "test-logcat-crash-gate: all passed"
