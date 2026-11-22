/*
 * Host test for the NFS write pipeline in nfs-write-pipe.h, against a fake
 * server. Run by the Gradle task testPipeDepth, which testDebugUnitTest and
 * check depend on:
 *   ./gradlew :app:testPipeDepth
 * or directly:
 *   cc -Wall -Werror -I app/src/main/jni -o /tmp/write_pipe_test \
 *       app/src/test/c/write_pipe_test.c && /tmp/write_pipe_test
 */
#include <stdio.h>

#define WPIPE_TEST 1
#include "nfs-write-pipe.h"

#define KIB 1024
#define MIB (1024 * 1024)
#define FILE_MAX (8 * MIB)
#define QUEUE_MAX 64
/* Round trip of one WRITE on the fake link. */
#define LATENCY_MS 10

struct fake_file {
    unsigned char data[FILE_MAX];
    size_t size;
};

struct nfsfh {
    struct fake_file *file;
};

struct fake_write {
    struct nfsfh *fh;
    const unsigned char *buf;
    size_t count;
    uint64_t offset;
    wpipe_cb_fn cb;
    void *private_data;
    int result;
    int64_t arrives;
};

struct nfs_context {
    struct fake_write queue[QUEUE_MAX];
    int queued;
    int lifo;
    int dead;
    int issued;
    int sync_writes;
    int fail_issue_at;      /* issue index that queues nothing, or -1 */
    int fail_issue_from;    /* fail every issue index >= this, or -1 */
    int fail_reply_at;      /* issue index answered with fail_reply_rc, or -1 */
    int fail_reply_rc;
    int short_reply_at;     /* issue index answered with half its bytes, or -1 */
    int max_in_flight;
    int64_t clock;
    char error[256];
    /* Order the WRITEs reached the fake disk, by issue index. */
    uint64_t landed_offset[256];
    int landed;
};

static struct nfs_context ctx;
static struct fake_file file_a;
static struct fake_file file_b;
static struct nfsfh fh_a;
static struct nfsfh fh_a2;
static struct nfsfh fh_b;
static int failures;

static void expect_int(const char *name, long long actual, long long expected) {
    if (actual != expected) {
        printf("FAIL %s: expected %lld, got %lld\n", name, expected, actual);
        failures++;
    } else {
        printf("ok   %s\n", name);
    }
}

static void expect_true(const char *name, int value) {
    expect_int(name, value != 0, 1);
}

static void land(struct fake_write *w, size_t count) {
    struct fake_file *file = w->fh->file;

    memcpy(file->data + w->offset, w->buf, count);
    if (w->offset + count > file->size) {
        file->size = w->offset + count;
    }
    if (ctx.landed < 256) {
        ctx.landed_offset[ctx.landed++] = w->offset;
    }
}

static int fake_issue(struct nfs_context *nfs, struct nfsfh *fh, const void *buf, size_t count,
                      uint64_t offset, wpipe_cb_fn cb, void *private_data) {
    int index = nfs->issued++;
    struct fake_write *w;

    if (index == nfs->fail_issue_at ||
        (nfs->fail_issue_from >= 0 && index >= nfs->fail_issue_from)) {
        snprintf(nfs->error, sizeof(nfs->error), "RPC error: Failed to send WRITE call");
        return -1;
    }
    if (nfs->queued >= QUEUE_MAX) {
        return -1;
    }
    w = &nfs->queue[nfs->queued++];
    w->fh = fh;
    w->buf = buf;
    w->count = count;
    w->offset = offset;
    w->cb = cb;
    w->private_data = private_data;
    w->result = (int) count;
    w->arrives = nfs->clock + LATENCY_MS;
    if (index == nfs->fail_reply_at) {
        w->result = nfs->fail_reply_rc;
    } else if (index == nfs->short_reply_at) {
        w->result = (int) (count / 2);
    }
    if (nfs->queued > nfs->max_in_flight) {
        nfs->max_in_flight = nfs->queued;
    }
    return 0;
}

static int fake_write_sync(struct nfs_context *nfs, struct nfsfh *fh, const void *buf,
                           size_t count, uint64_t offset) {
    struct fake_write w;

    nfs->sync_writes++;
    if (nfs->dead) {
        return -EIO;
    }
    memset(&w, 0, sizeof(w));
    w.fh = fh;
    w.buf = buf;
    w.count = count;
    w.offset = offset;
    land(&w, count);
    return (int) count;
}

/*
 * Like poll and nfs_service: waits up to timeout_ms for a reply, then answers
 * one that has arrived, the oldest or (lifo) the newest.
 */
static int fake_service(struct nfs_context *nfs, int timeout_ms) {
    struct fake_write w;
    int64_t first = INT64_MAX;
    int pick = -1;
    int i;

    if (nfs->dead) {
        nfs->clock += timeout_ms;
        return -1;
    }
    for (i = 0; i < nfs->queued; i++) {
        if (nfs->queue[i].arrives < first) {
            first = nfs->queue[i].arrives;
        }
    }
    if (first > nfs->clock) {
        if (first > nfs->clock + timeout_ms) {
            nfs->clock += timeout_ms;
            return 0;
        }
        nfs->clock = first;
    }
    for (i = 0; i < nfs->queued; i++) {
        if (nfs->queue[i].arrives <= nfs->clock) {
            pick = i;
            if (!nfs->lifo) {
                break;
            }
        }
    }
    w = nfs->queue[pick];
    memmove(&nfs->queue[pick], &nfs->queue[pick + 1],
            (size_t) (nfs->queued - pick - 1) * sizeof(w));
    nfs->queued--;
    if (w.result > 0) {
        land(&w, (size_t) w.result);
    }
    if (w.result < 0) {
        snprintf(nfs->error, sizeof(nfs->error), "NFS: Write failed with NFS3ERR_NOSPC(-28)");
    }
    w.cb(w.result, nfs, NULL, w.private_data);
    return 1;
}

/* rpc_abort_pending: every queued WRITE ends as cancelled. */
static void fake_abort(struct nfs_context *nfs) {
    while (nfs->queued > 0) {
        struct fake_write w = nfs->queue[0];

        memmove(&nfs->queue[0], &nfs->queue[1], (size_t) (nfs->queued - 1) * sizeof(w));
        nfs->queued--;
        snprintf(nfs->error, sizeof(nfs->error), "nfs write dropped");
        w.cb(-EINTR, nfs, NULL, w.private_data);
    }
}

static int fake_wants_write(struct nfs_context *nfs) {
    (void) nfs;
    return 0;
}

static int fake_timeout_ms(struct nfs_context *nfs) {
    (void) nfs;
    return 1000;
}

static int fake_fh_equal(struct nfsfh *a, struct nfsfh *b) {
    return a != NULL && b != NULL && a->file == b->file;
}

static const char *fake_get_error(struct nfs_context *nfs) {
    return nfs->error;
}

static void fake_set_error(struct nfs_context *nfs, const char *message) {
    snprintf(nfs->error, sizeof(nfs->error), "%s", message);
}

static int64_t fake_now_ms(void) {
    return ctx.clock;
}

static const struct wpipe_ops fake_ops = {
    .issue = fake_issue,
    .write_sync = fake_write_sync,
    .service = fake_service,
    .wants_write = fake_wants_write,
    .timeout_ms = fake_timeout_ms,
    .fh_equal = fake_fh_equal,
    .get_error = fake_get_error,
    .set_error = fake_set_error,
    .now_ms = fake_now_ms,
};

static unsigned char source[FILE_MAX];

static void reset(struct wpipe *wp) {
    memset(&ctx, 0, sizeof(ctx));
    ctx.fail_issue_at = -1;
    ctx.fail_issue_from = -1;
    ctx.fail_reply_at = -1;
    ctx.short_reply_at = -1;
    wpipe_fail_fault_alloc = 0;
    memset(&file_a, 0, sizeof(file_a));
    memset(&file_b, 0, sizeof(file_b));
    fh_a.file = &file_a;
    fh_a2.file = &file_a;
    fh_b.file = &file_b;
    wpipe_init(wp, &fake_ops, &ctx);
}

static int slots_used(struct wpipe *wp) {
    int i;
    int used = 0;

    for (i = 0; i < PIPE_SLOTS; i++) {
        if (wp->slot[i].state != 0) {
            used++;
        }
    }
    return used;
}

static void fill_source(unsigned char seed) {
    size_t i;

    for (i = 0; i < FILE_MAX; i++) {
        source[i] = (unsigned char) (i * 31 + seed + (i >> 12));
    }
}

/* Writes size bytes of source in steps of block, as FileByteChannel does. */
static int write_all(struct wpipe *wp, struct nfsfh *fh, size_t size, size_t block, size_t chunk,
                     int depth) {
    size_t at = 0;

    while (at < size) {
        size_t n = size - at < block ? size - at : block;
        int rc = wpipe_write(wp, fh, (const char *) source + at, (int) n, at, chunk, depth);

        if (rc < 0) {
            return rc;
        }
        at += (size_t) rc;
    }
    return 0;
}

/*
 * Same contract as Client.writeAt: retry a short success under the same
 * "lock", and on error return how many bytes were already queued.
 */
static int write_at_like(struct wpipe *wp, struct nfsfh *fh, const char *buf, int length,
                         uint64_t offset, size_t chunk, int depth) {
    int done = 0;

    while (done < length) {
        int rc = wpipe_write(wp, fh, buf + done, length - done, offset + (uint64_t) done,
                             chunk, depth);

        if (rc < 0) {
            return done > 0 ? done : rc;
        }
        if (rc == 0) {
            return done > 0 ? done : -EIO;
        }
        if (rc > length - done) {
            rc = length - done;
        }
        done += rc;
    }
    return done;
}

static void test_sequential_copy(void) {
    struct wpipe wp;
    int stuck = 0;
    int rc;

    reset(&wp);
    fill_source(1);
    rc = write_all(&wp, &fh_a, 8 * MIB, MIB, MIB, 4);
    expect_int("copy: writes queued", rc, 0);
    expect_true("copy: WRITEs still in flight before close", slots_used(&wp) > 0);
    expect_int("copy: no more than depth in flight", ctx.max_in_flight, 4);
    expect_int("copy: no synchronous writes", ctx.sync_writes, 0);
    rc = wpipe_close(&wp, &fh_a, &stuck);
    expect_int("copy: close ok", rc, 0);
    expect_int("copy: close not stuck", stuck, 0);
    expect_int("copy: close waited for every slot", slots_used(&wp), 0);
    expect_int("copy: one WRITE per MiB", ctx.issued, 8);
    expect_int("copy: size", (long long) file_a.size, 8 * MIB);
    expect_true("copy: bytes", memcmp(file_a.data, source, 8 * MIB) == 0);
}

static void test_out_of_order_replies(void) {
    struct wpipe wp;
    int stuck = 0;

    reset(&wp);
    ctx.lifo = 1;
    fill_source(2);
    expect_int("lifo: writes queued", write_all(&wp, &fh_a, 6 * MIB + 123, MIB, MIB, 4), 0);
    expect_int("lifo: close ok", wpipe_close(&wp, &fh_a, &stuck), 0);
    expect_int("lifo: size", (long long) file_a.size, 6 * MIB + 123);
    expect_true("lifo: bytes", memcmp(file_a.data, source, 6 * MIB + 123) == 0);
    expect_true("lifo: replies really came out of order",
                ctx.landed >= 2 && ctx.landed_offset[0] > ctx.landed_offset[1]);
}

static void test_small_writes_stay_sync(void) {
    struct wpipe wp;
    int stuck = 0;
    int rc;

    reset(&wp);
    fill_source(3);
    rc = wpipe_write(&wp, &fh_a, (const char *) source, 4 * KIB, 0, MIB, 4);
    expect_int("small: full count", rc, 4 * KIB);
    expect_int("small: synchronous", ctx.sync_writes, 1);
    expect_int("small: no async WRITE", ctx.issued, 0);
    expect_int("small: no slot", slots_used(&wp), 0);
    rc = wpipe_write(&wp, &fh_a, (const char *) source, WPIPE_MIN - 1, 0, MIB, 4);
    expect_int("below WPIPE_MIN: synchronous", ctx.sync_writes, 2);
    rc = wpipe_write(&wp, &fh_a, (const char *) source, MIB, 0, MIB, 1);
    expect_int("depth 1: synchronous", ctx.sync_writes, 3);
    expect_int("small: close ok", wpipe_close(&wp, &fh_a, &stuck), 0);
}

static void test_chunks_and_offsets(void) {
    struct wpipe wp;
    int stuck = 0;
    int rc;
    int i;
    int ordered = 1;

    reset(&wp);
    fill_source(4);
    /* wtmax 256 KiB: one 1 MiB call becomes four WRITEs at the right offsets. */
    rc = wpipe_write(&wp, &fh_a, (const char *) source + 3 * MIB, MIB, 3 * MIB, 256 * KIB, 16);
    expect_int("chunks: full count", rc, MIB);
    expect_int("chunks: four WRITEs", ctx.issued, 4);
    for (i = 0; i < ctx.queued; i++) {
        if (ctx.queue[i].offset != (uint64_t) (3 * MIB + i * 256 * KIB) ||
            ctx.queue[i].count != 256 * KIB) {
            ordered = 0;
        }
    }
    expect_true("chunks: offsets and counts", ordered);
    expect_int("chunks: close ok", wpipe_close(&wp, &fh_a, &stuck), 0);
    expect_true("chunks: bytes", memcmp(file_a.data + 3 * MIB, source + 3 * MIB, MIB) == 0);
    expect_int("chunks: size", (long long) file_a.size, 4 * MIB);
}

static void test_overlap_waits(void) {
    struct wpipe wp;
    int stuck = 0;
    unsigned char second[MIB];

    reset(&wp);
    ctx.lifo = 1;
    fill_source(5);
    memset(second, 0xab, sizeof(second));
    expect_int("overlap: first", wpipe_write(&wp, &fh_a, (const char *) source, MIB, 0, MIB, 4),
               MIB);
    expect_int("overlap: first in flight", ctx.queued, 1);
    /* Same file through a second handle, overlapping the second half. */
    expect_int("overlap: second",
               wpipe_write(&wp, &fh_a2, (const char *) second, MIB, MIB / 2, MIB, 4), MIB);
    expect_int("overlap: first answered before second was sent", ctx.landed, 1);
    expect_int("overlap: close a", wpipe_close(&wp, &fh_a, &stuck), 0);
    expect_int("overlap: close a2", wpipe_close(&wp, &fh_a2, &stuck), 0);
    expect_true("overlap: first half from first write",
                memcmp(file_a.data, source, MIB / 2) == 0);
    expect_true("overlap: later write wins", memcmp(file_a.data + MIB / 2, second, MIB) == 0);
}

static void test_error_comes_back_on_next_write(void) {
    struct wpipe wp;
    int stuck = 0;
    int rc;
    size_t at;

    reset(&wp);
    fill_source(6);
    ctx.fail_reply_at = 1;
    ctx.fail_reply_rc = -ENOSPC;
    rc = 0;
    for (at = 0; at < 8 * MIB && rc >= 0; at += MIB) {
        rc = wpipe_write(&wp, &fh_a, (const char *) source + at, MIB, at, MIB, 4);
    }
    expect_int("error: a later write fails", rc, -ENOSPC);
    expect_true("error: before the copy finished", at < 8 * MIB);
    expect_true("error: text kept", strstr(ctx.error, "NOSPC") != NULL);
    snprintf(ctx.error, sizeof(ctx.error), "something else");
    expect_int("error: fsync reports it", wpipe_flush(&wp, &fh_a), -ENOSPC);
    expect_true("error: fsync restores its text", strstr(ctx.error, "NOSPC") != NULL);
    expect_int("error: still sticky for the next write",
               wpipe_write(&wp, &fh_a, (const char *) source, 4 * KIB, 0, MIB, 4), -ENOSPC);
    expect_int("error: close reports it", wpipe_close(&wp, &fh_a, &stuck), -ENOSPC);
    expect_int("error: close not stuck", stuck, 0);
    expect_int("error: no slot left", slots_used(&wp), 0);
    expect_true("error: forgotten after close", wp.faults == NULL);
}

static void test_error_only_at_close(void) {
    struct wpipe wp;
    int stuck = 0;

    reset(&wp);
    fill_source(7);
    ctx.fail_reply_at = 2;
    ctx.fail_reply_rc = -EDQUOT;
    expect_int("late error: writes queued", write_all(&wp, &fh_a, 3 * MIB, MIB, MIB, 4), 0);
    expect_int("late error: close reports it", wpipe_close(&wp, &fh_a, &stuck), -EDQUOT);
}

static void test_short_reply_is_an_error(void) {
    struct wpipe wp;
    int stuck = 0;

    reset(&wp);
    fill_source(8);
    ctx.short_reply_at = 0;
    expect_int("short: queued", write_all(&wp, &fh_a, 2 * MIB, MIB, MIB, 4), 0);
    expect_int("short: no WRITE answered yet", ctx.landed, 0);
    expect_int("short: close fails with EIO", wpipe_close(&wp, &fh_a, &stuck), -EIO);
    expect_true("short: says so", strstr(ctx.error, "Short NFS write") != NULL);
}

static void test_errors_stay_with_their_handle(void) {
    struct wpipe wp;
    int stuck = 0;

    reset(&wp);
    fill_source(9);
    ctx.fail_reply_at = 0;
    ctx.fail_reply_rc = -EACCES;
    expect_int("per handle: a queued", wpipe_write(&wp, &fh_a, (const char *) source, MIB, 0,
                                                   MIB, 4), MIB);
    expect_int("per handle: b queued", wpipe_write(&wp, &fh_b, (const char *) source, MIB, 0,
                                                   MIB, 4), MIB);
    expect_int("per handle: b closes fine", wpipe_close(&wp, &fh_b, &stuck), 0);
    expect_int("per handle: a fails", wpipe_close(&wp, &fh_a, &stuck), -EACCES);
    expect_true("per handle: b bytes", memcmp(file_b.data, source, MIB) == 0);
}

static void test_issue_failure(void) {
    struct wpipe wp;
    int stuck = 0;

    reset(&wp);
    fill_source(10);
    ctx.fail_issue_at = 1;
    expect_int("issue fail: first ok", wpipe_write(&wp, &fh_a, (const char *) source, MIB, 0,
                                                   MIB, 4), MIB);
    expect_int("issue fail: second fails", wpipe_write(&wp, &fh_a, (const char *) source + MIB,
                                                       MIB, MIB, MIB, 4), -EIO);
    expect_int("issue fail: its slot is free", slots_used(&wp), 1);
    expect_int("issue fail: close still drains", wpipe_close(&wp, &fh_a, &stuck), 0);
    expect_int("issue fail: nothing left", slots_used(&wp), 0);
}

static void test_dead_session_on_close(void) {
    struct wpipe wp;
    int stuck = 0;
    int rc;

    reset(&wp);
    fill_source(11);
    expect_int("dead: queued", write_all(&wp, &fh_a, 3 * MIB, MIB, MIB, 4), 0);
    ctx.dead = 1;
    rc = wpipe_close(&wp, &fh_a, &stuck);
    expect_int("dead: close fails", rc, -EIO);
    expect_int("dead: close is stuck", stuck, 1);
    expect_true("dead: transport text", strstr(ctx.error, "nfs_service failed") != NULL);
    /* What closeFile does next: abort pending RPCs, then forget the handle. */
    fake_abort(&ctx);
    expect_int("dead: nothing detached after abort", wpipe_forget(&wp, &fh_a), 0);
    expect_int("dead: slots freed", slots_used(&wp), 0);
    expect_true("dead: no fault left", wp.faults == NULL);
}

static void test_timeout(void) {
    struct wpipe wp;
    int stuck = 0;
    int rc;

    reset(&wp);
    fill_source(12);
    expect_int("timeout: queued", wpipe_write(&wp, &fh_a, (const char *) source, MIB, 0, MIB, 4),
               MIB);
    /* The server never answers: drop the reply from the queue. */
    ctx.queued = 0;
    rc = wpipe_close(&wp, &fh_a, &stuck);
    expect_int("timeout: close times out", rc, -ETIMEDOUT);
    expect_int("timeout: stuck", stuck, 1);
    expect_true("timeout: waited past libnfs's timeout", ctx.clock >= 1000);
    expect_int("timeout: forget detaches the slot", wpipe_forget(&wp, &fh_a), 1);
    expect_true("timeout: detached slot has no handle", wp.slot[0].fh == NULL);
    /* The late callback lands in the detached slot; the next call frees it. */
    wpipe_cb(-EINTR, &ctx, NULL, &wp.slot[0]);
    wpipe_harvest(&wp);
    expect_int("timeout: late callback frees the slot", slots_used(&wp), 0);
    expect_true("timeout: late callback adds no fault", wp.faults == NULL);
}

static void test_two_handles_share_slots(void) {
    struct wpipe wp;
    int stuck = 0;
    int i;
    int rc = 0;

    reset(&wp);
    fill_source(13);
    for (i = 0; i < 8 && rc >= 0; i++) {
        rc = wpipe_write(&wp, &fh_a, (const char *) source + i * 256 * KIB, 256 * KIB,
                         (uint64_t) i * 256 * KIB, 256 * KIB, 16);
        if (rc >= 0) {
            rc = wpipe_write(&wp, &fh_b, (const char *) source + i * 256 * KIB, 256 * KIB,
                             (uint64_t) i * 256 * KIB, 256 * KIB, 16);
        }
    }
    expect_true("shared: all queued", rc >= 0);
    expect_true("shared: never more than PIPE_SLOTS", ctx.max_in_flight <= PIPE_SLOTS);
    expect_int("shared: close a", wpipe_close(&wp, &fh_a, &stuck), 0);
    expect_int("shared: close b", wpipe_close(&wp, &fh_b, &stuck), 0);
    expect_true("shared: a bytes", memcmp(file_a.data, source, 2 * MIB) == 0);
    expect_true("shared: b bytes", memcmp(file_b.data, source, 2 * MIB) == 0);
}

static void test_drain_for_read(void) {
    struct wpipe wp;

    reset(&wp);
    fill_source(14);
    expect_int("drain: queued", wpipe_write(&wp, &fh_a, (const char *) source, MIB, 0, MIB, 4),
               MIB);
    expect_int("drain: other file not busy", wpipe_busy(&wp, &fh_b), 0);
    expect_int("drain: same file via other handle busy", wpipe_busy(&wp, &fh_a2), 1);
    expect_int("drain: ok", wpipe_drain(&wp, &fh_a2), 0);
    expect_true("drain: bytes visible", memcmp(file_a.data, source, MIB) == 0);
    expect_int("drain: idle", wpipe_busy(&wp, NULL), 0);
}

static void test_fsync_waits(void) {
    struct wpipe wp;
    int stuck = 0;

    reset(&wp);
    fill_source(15);
    expect_int("fsync: queued", write_all(&wp, &fh_a, 2 * MIB, MIB, MIB, 4), 0);
    expect_int("fsync: nothing answered yet", ctx.landed, 0);
    expect_int("fsync: ok", wpipe_flush(&wp, &fh_a), 0);
    expect_int("fsync: every WRITE answered first", ctx.landed, 2);
    expect_int("fsync: no slot left", slots_used(&wp), 0);
    expect_int("fsync: close ok", wpipe_close(&wp, &fh_a, &stuck), 0);
}

static void test_issue_fails_mid_split(void) {
    struct wpipe wp;
    int stuck = 0;
    int rc;

    reset(&wp);
    fill_source(16);
    ctx.fail_issue_from = 1;
    rc = wpipe_write(&wp, &fh_a, (const char *) source, 2 * MIB, 0, MIB, 4);
    expect_int("mid-split: prefix already queued is returned", rc, MIB);
    expect_int("mid-split: first WRITE still in flight", slots_used(&wp), 1);
    expect_int("mid-split: drain prefix of first case", wpipe_close(&wp, &fh_a, &stuck), 0);

    reset(&wp);
    fill_source(17);
    ctx.fail_issue_from = 1;
    rc = write_at_like(&wp, &fh_a, (const char *) source, 2 * MIB, 0, MIB, 4);
    expect_int("mid-split: writeAt keeps the prefix", rc, MIB);
    rc = write_at_like(&wp, &fh_a, (const char *) source + MIB, MIB, MIB, MIB, 4);
    expect_int("mid-split: tail then fails", rc, -EIO);
    expect_int("mid-split: close drains the prefix", wpipe_close(&wp, &fh_a, &stuck), 0);
    expect_int("mid-split: only the prefix landed", (long long) file_a.size, MIB);
    expect_true("mid-split: prefix bytes", memcmp(file_a.data, source, MIB) == 0);
}

static void test_fault_alloc_oom(void) {
    struct wpipe wp;
    int stuck = 0;

    reset(&wp);
    fill_source(18);
    ctx.fail_reply_at = 0;
    ctx.fail_reply_rc = -ENOSPC;
    wpipe_fail_fault_alloc = 1;
    expect_int("oom fault: queued", wpipe_write(&wp, &fh_a, (const char *) source, MIB, 0,
                                                MIB, 4), MIB);
    expect_int("oom fault: fsync reports it", wpipe_flush(&wp, &fh_a), -ENOSPC);
    expect_int("oom fault: later write reports it", wpipe_write(&wp, &fh_a,
                                                               (const char *) source + MIB, MIB,
                                                               MIB, MIB, 4), -ENOSPC);
    expect_int("oom fault: close reports it", wpipe_close(&wp, &fh_a, &stuck), -ENOSPC);
    expect_true("oom fault: error text kept", strstr(ctx.error, "NFS3ERR_NOSPC") != NULL);
}

static void test_write_depth(void) {
    expect_int("write depth: aosp wmem_max", pipe_write_depth(524288, MIB), PIPE_MIN_DEPTH);
    expect_int("write depth: big sndbuf", pipe_write_depth(32 * MIB, MIB), PIPE_SLOTS);
    expect_int("write depth: 256 KiB wtmax", pipe_write_depth(524288, 256 * KIB), 16);
    expect_int("write depth: 512 KiB wtmax", pipe_write_depth(524288, 512 * KIB), 8);
    expect_int("write depth: 32 KiB wtmax capped", pipe_write_depth(524288, 32 * KIB),
               PIPE_SLOTS);
    expect_int("write depth: zero chunk means 1 MiB", pipe_write_depth(0, 0), PIPE_MIN_DEPTH);
}

int main(void) {
    test_sequential_copy();
    test_out_of_order_replies();
    test_small_writes_stay_sync();
    test_chunks_and_offsets();
    test_overlap_waits();
    test_error_comes_back_on_next_write();
    test_error_only_at_close();
    test_short_reply_is_an_error();
    test_errors_stay_with_their_handle();
    test_issue_failure();
    test_dead_session_on_close();
    test_timeout();
    test_two_handles_share_slots();
    test_drain_for_read();
    test_fsync_waits();
    test_issue_fails_mid_split();
    test_fault_alloc_oom();
    test_write_depth();
    if (failures != 0) {
        printf("%d failure(s)\n", failures);
        return 1;
    }
    printf("all passed\n");
    return 0;
}
