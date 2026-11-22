/*
 * Host test for pipe_depth_from_rcvbuf(). Run by the Gradle task
 * testPipeDepth, which testDebugUnitTest and check depend on:
 *   ./gradlew :app:testPipeDepth
 * or directly:
 *   cc -Wall -Werror -I app/src/main/jni -o /tmp/pipe_depth_test \
 *       app/src/test/c/pipe_depth_test.c && /tmp/pipe_depth_test
 */
#include <stdio.h>

#include "nfs-pipe-depth.h"

#define MIB (1024 * 1024)

static int failures;

static void expect(const char *name, int actual, int expected) {
    if (actual != expected) {
        printf("FAIL %s: expected %d, got %d\n", name, expected, actual);
        failures++;
    } else {
        printf("ok   %s\n", name);
    }
}

int main(void) {
    /* AOSP rmem_max 262144: the kernel reports 524288. */
    expect("aosp default rmem_max", pipe_depth_from_rcvbuf(524288, MIB), PIPE_MIN_DEPTH);
    expect("2 MiB rmem_max", pipe_depth_from_rcvbuf(4 * MIB, MIB), PIPE_MIN_DEPTH);
    expect("8 MiB rmem_max", pipe_depth_from_rcvbuf(16 * MIB, MIB), 8);
    expect("16 MiB granted", pipe_depth_from_rcvbuf(32 * MIB, MIB), PIPE_SLOTS);
    expect("huge buffer capped", pipe_depth_from_rcvbuf(0x7fffffff, MIB), PIPE_SLOTS);
    expect("unreadable buffer", pipe_depth_from_rcvbuf(0, MIB), PIPE_MIN_DEPTH);
    expect("negative buffer", pipe_depth_from_rcvbuf(-1, MIB), PIPE_MIN_DEPTH);
    expect("zero iosize means 1 MiB", pipe_depth_from_rcvbuf(16 * MIB, 0), 8);
    expect("small reads use the buffer", pipe_depth_from_rcvbuf(4 * MIB, 256 * 1024), 8);
    expect("at least two reads", PIPE_MIN_DEPTH >= 2, 1);
    if (failures != 0) {
        printf("%d failure(s)\n", failures);
        return 1;
    }
    printf("all passed\n");
    return 0;
}
