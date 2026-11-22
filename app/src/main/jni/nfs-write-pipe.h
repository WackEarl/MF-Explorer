#ifndef MATERIALFILES_NFS_WRITE_PIPE_H
#define MATERIALFILES_NFS_WRITE_PIPE_H

/*
 * Write pipeline: keeps several WRITE RPCs for bulk writes in flight on one
 * connection instead of one synchronous WRITE per round trip.
 *
 * A pipelined write copies the caller's bytes into a slot buffer, sends the
 * WRITE (libnfs sends them UNSTABLE; nfs_close and nfs_fsync COMMIT) and
 * returns the full length. The slot buffer stays ours until the callback,
 * because libnfs sends from it without a copy and resends it on reconnect.
 *
 * A failed slot is not lost. Its error sticks to the file handle that wrote
 * it and comes back from every later write, fsync and close on that handle.
 * Writes that overlap a slot in flight for the same file wait for it, so the
 * server never sees two overlapping WRITEs at once and the later bytes win.
 *
 * Nothing here takes a lock. The caller serializes every call on one
 * nfs_context (the mount lock), and callbacks only record a status.
 *
 * libnfs calls go through struct wpipe_ops so the logic runs in a host test
 * (app/src/test/c/write_pipe_test.c) against a fake server.
 */

#include <errno.h>
#include <stddef.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "nfs-pipe-depth.h"

#ifdef WPIPE_TEST
/* Host tests set this to force wpipe_fault_add to run its OOM path. */
static int wpipe_fail_fault_alloc;
#endif

struct nfs_context;
struct nfsfh;

/* Smaller writes stay synchronous: no copy, no deferred error. */
#define WPIPE_MIN (256 * 1024)
#define WPIPE_ERR_LEN 192

typedef void (*wpipe_cb_fn)(int status, struct nfs_context *nfs, void *data,
                            void *private_data);

struct wpipe_ops {
    /* nfs_pwrite_async. 0 when the WRITE was queued. */
    int (*issue)(struct nfs_context *nfs, struct nfsfh *fh, const void *buf, size_t count,
                 uint64_t offset, wpipe_cb_fn cb, void *private_data);
    /* nfs_pwrite. Bytes written or a negative errno. */
    int (*write_sync)(struct nfs_context *nfs, struct nfsfh *fh, const void *buf, size_t count,
                      uint64_t offset);
    /* One poll and nfs_service. -1: no session, 0: nothing to do, 1: serviced. */
    int (*service)(struct nfs_context *nfs, int timeout_ms);
    /* Non-zero while libnfs has bytes queued for the socket. */
    int (*wants_write)(struct nfs_context *nfs);
    int (*timeout_ms)(struct nfs_context *nfs);
    int (*fh_equal)(struct nfsfh *a, struct nfsfh *b);
    const char *(*get_error)(struct nfs_context *nfs);
    void (*set_error)(struct nfs_context *nfs, const char *message);
    int64_t (*now_ms)(void);
};

struct wpipe;

struct wpipe_slot {
    /* 0: free. 1: WRITE in flight, libnfs owns buf. 2: callback ran. */
    int state;
    int result;
    struct nfsfh *fh;
    uint64_t offset;
    size_t count;
    char *buf;
    struct wpipe *owner;
    char error[WPIPE_ERR_LEN];
};

/* A write error that the handle has not reported at close yet. */
struct wpipe_fault {
    struct wpipe_fault *next;
    struct nfsfh *fh;
    int rc;
    char error[WPIPE_ERR_LEN];
};

struct wpipe {
    const struct wpipe_ops *ops;
    struct nfs_context *nfs;
    struct wpipe_slot slot[PIPE_SLOTS];
    struct wpipe_fault *faults;
    /* Set when calloc in wpipe_fault_add fails, so the error is not lost. */
    int lost_fault_rc;
    char lost_fault_error[WPIPE_ERR_LEN];
};

static inline void wpipe_init(struct wpipe *wp, const struct wpipe_ops *ops,
                              struct nfs_context *nfs) {
    memset(wp, 0, sizeof(*wp));
    wp->ops = ops;
    wp->nfs = nfs;
}

static void wpipe_copy_error(char *out, const char *message) {
    if (message == NULL || message[0] == '\0') {
        message = "NFS write failed";
    }
    snprintf(out, WPIPE_ERR_LEN, "%s", message);
}

static void wpipe_cb(int status, struct nfs_context *nfs, void *data, void *private_data) {
    struct wpipe_slot *slot = private_data;
    struct wpipe *wp = slot->owner;

    (void) data;
    slot->result = status;
    if (status < 0) {
        wpipe_copy_error(slot->error, wp->ops->get_error(nfs));
    } else if ((size_t) status < slot->count) {
        /* libnfs already resent the tail of a short reply. A short total
         * means that resend failed after the prefix landed. */
        snprintf(slot->error, WPIPE_ERR_LEN, "Short NFS write: %d of %zu bytes", status,
                 slot->count);
    }
    slot->state = 2;
}

static struct wpipe_fault *wpipe_fault_for(struct wpipe *wp, struct nfsfh *fh) {
    struct wpipe_fault *fault;

    for (fault = wp->faults; fault != NULL; fault = fault->next) {
        if (fault->fh == fh) {
            return fault;
        }
    }
    return NULL;
}

/* Keeps the first error of a handle. A later one is usually its consequence. */
static void wpipe_fault_add(struct wpipe *wp, struct nfsfh *fh, int rc, const char *error) {
    struct wpipe_fault *fault;

    if (fh == NULL || wpipe_fault_for(wp, fh) != NULL) {
        return;
    }
#ifdef WPIPE_TEST
    if (wpipe_fail_fault_alloc) {
        fault = NULL;
    } else {
        fault = calloc(1, sizeof(*fault));
    }
#else
    fault = calloc(1, sizeof(*fault));
#endif
    if (fault == NULL) {
        /* Keep a sticky fallback so fsync and close still report the error. */
        if (wp->lost_fault_rc == 0) {
            wp->lost_fault_rc = rc < 0 ? rc : -EIO;
            wpipe_copy_error(wp->lost_fault_error, error);
        }
        return;
    }
    fault->fh = fh;
    fault->rc = rc;
    wpipe_copy_error(fault->error, error);
    fault->next = wp->faults;
    wp->faults = fault;
}

static void wpipe_fault_remove(struct wpipe *wp, struct nfsfh *fh) {
    struct wpipe_fault **link = &wp->faults;

    while (*link != NULL) {
        struct wpipe_fault *fault = *link;

        if (fault->fh == fh) {
            *link = fault->next;
            free(fault);
            continue;
        }
        link = &fault->next;
    }
}

/* 0, or the handle's sticky error. Sets the context error text for it. */
static int wpipe_fault_rc(struct wpipe *wp, struct nfsfh *fh) {
    struct wpipe_fault *fault = wpipe_fault_for(wp, fh);

    if (fault != NULL) {
        wp->ops->set_error(wp->nfs, fault->error);
        return fault->rc;
    }
    if (wp->lost_fault_rc < 0) {
        wp->ops->set_error(wp->nfs, wp->lost_fault_error);
        return wp->lost_fault_rc;
    }
    return 0;
}

static void wpipe_slot_free(struct wpipe_slot *slot) {
    free(slot->buf);
    slot->buf = NULL;
    slot->state = 0;
    slot->result = 0;
    slot->fh = NULL;
    slot->offset = 0;
    slot->count = 0;
    slot->error[0] = '\0';
}

/* Moves a finished slot's outcome to its handle and frees the slot. */
static void wpipe_retire(struct wpipe *wp, struct wpipe_slot *slot) {
    if (slot->result < 0) {
        wpipe_fault_add(wp, slot->fh, slot->result, slot->error);
    } else if ((size_t) slot->result < slot->count) {
        wpipe_fault_add(wp, slot->fh, -EIO, slot->error);
    }
    wpipe_slot_free(slot);
}

static void wpipe_retire_done(struct wpipe *wp) {
    int i;

    for (i = 0; i < PIPE_SLOTS; i++) {
        if (wp->slot[i].state == 2) {
            wpipe_retire(wp, &wp->slot[i]);
        }
    }
}

/*
 * Services the connection until slot's callback ran. libnfs times the RPC
 * out itself after timeout_ms; the grace lets that happen first, so a slot
 * normally finishes with libnfs's own error.
 */
static int wpipe_wait(struct wpipe *wp, struct wpipe_slot *slot) {
    int timeout = wp->ops->timeout_ms(wp->nfs);
    int64_t deadline;

    if (timeout <= 0) {
        timeout = 30000;
    }
    deadline = wp->ops->now_ms() + timeout + 5000;
    while (slot->state == 1) {
        int64_t now = wp->ops->now_ms();
        int slice;

        if (now >= deadline) {
            wp->ops->set_error(wp->nfs, "NFS write: command timed out");
            return -ETIMEDOUT;
        }
        slice = (int) (deadline - now);
        if (slice > 250) {
            slice = 250;
        }
        if (wp->ops->service(wp->nfs, slice) < 0 && slot->state == 1) {
            /* Client.isTransportFailure() matches this text. */
            wp->ops->set_error(wp->nfs, "NFS write: nfs_service failed");
            return -EIO;
        }
    }
    return 0;
}

/* Sends what libnfs queued so the WRITEs travel while the caller works. */
static void wpipe_push(struct wpipe *wp) {
    int i;

    for (i = 0; i < 32; i++) {
        if (!wp->ops->wants_write(wp->nfs)) {
            return;
        }
        if (wp->ops->service(wp->nfs, 0) < 0) {
            return;
        }
    }
}

/* Replies that arrived since the last call, without blocking. */
static void wpipe_harvest(struct wpipe *wp) {
    int i;
    int busy = 0;

    for (i = 0; i < PIPE_SLOTS; i++) {
        if (wp->slot[i].state == 1) {
            busy = 1;
            break;
        }
    }
    if (busy) {
        for (i = 0; i < 64; i++) {
            if (wp->ops->service(wp->nfs, 0) <= 0) {
                break;
            }
        }
    }
    wpipe_retire_done(wp);
}

static int wpipe_same_file(struct wpipe *wp, struct wpipe_slot *slot, struct nfsfh *fh) {
    return slot->fh == fh || (slot->fh != NULL && wp->ops->fh_equal(slot->fh, fh));
}

/*
 * Waits for every slot in flight for this file (any handle on it), or for
 * every slot when fh is NULL. 0, or the error that ended the wait; slots it
 * could not wait for stay in flight.
 */
static int wpipe_drain(struct wpipe *wp, struct nfsfh *fh) {
    int i;

    for (i = 0; i < PIPE_SLOTS; i++) {
        struct wpipe_slot *slot = &wp->slot[i];
        int rc;

        if (slot->state == 0 || (fh != NULL && !wpipe_same_file(wp, slot, fh))) {
            continue;
        }
        rc = wpipe_wait(wp, slot);
        if (rc < 0) {
            return rc;
        }
        wpipe_retire(wp, slot);
    }
    return 0;
}

static int wpipe_busy(struct wpipe *wp, struct nfsfh *fh) {
    int i;

    for (i = 0; i < PIPE_SLOTS; i++) {
        struct wpipe_slot *slot = &wp->slot[i];

        if (slot->state != 0 && (fh == NULL || wpipe_same_file(wp, slot, fh))) {
            return 1;
        }
    }
    return 0;
}

static int wpipe_overlaps(struct wpipe_slot *slot, uint64_t offset, size_t length) {
    return slot->offset < offset + length && offset < slot->offset + slot->count;
}

/* Waits for slots of this file that overlap [offset, offset + length). */
static int wpipe_settle_range(struct wpipe *wp, struct nfsfh *fh, uint64_t offset,
                              size_t length) {
    int i;

    for (i = 0; i < PIPE_SLOTS; i++) {
        struct wpipe_slot *slot = &wp->slot[i];
        int rc;

        if (slot->state == 0 || !wpipe_same_file(wp, slot, fh) ||
            !wpipe_overlaps(slot, offset, length)) {
            continue;
        }
        rc = wpipe_wait(wp, slot);
        if (rc < 0) {
            return rc;
        }
        wpipe_retire(wp, slot);
    }
    return 0;
}

/*
 * A free slot, once this handle has fewer than depth in flight. Waits for
 * the oldest-issued slot of the handle, or for any slot when every slot is
 * taken by other files. NULL with *rc set when a wait failed.
 */
static struct wpipe_slot *wpipe_claim(struct wpipe *wp, struct nfsfh *fh, int depth,
                                      int *rc) {
    for (;;) {
        struct wpipe_slot *free_slot = NULL;
        struct wpipe_slot *mine = NULL;
        struct wpipe_slot *any = NULL;
        int in_flight = 0;
        int i;

        for (i = 0; i < PIPE_SLOTS; i++) {
            struct wpipe_slot *slot = &wp->slot[i];

            if (slot->state == 2) {
                wpipe_retire(wp, slot);
            }
            if (slot->state == 0) {
                if (free_slot == NULL) {
                    free_slot = slot;
                }
                continue;
            }
            if (any == NULL) {
                any = slot;
            }
            if (slot->fh == fh) {
                in_flight++;
                if (mine == NULL || slot->offset < mine->offset) {
                    mine = slot;
                }
            }
        }
        if (free_slot != NULL && in_flight < depth) {
            return free_slot;
        }
        {
            struct wpipe_slot *wait_for = in_flight >= depth && mine != NULL ? mine : any;

            if (wait_for == NULL) {
                *rc = -EIO;
                return NULL;
            }
            *rc = wpipe_wait(wp, wait_for);
            if (*rc < 0) {
                return NULL;
            }
            wpipe_retire(wp, wait_for);
        }
    }
}

/*
 * Writes length bytes at offset. Returns length once every chunk is queued
 * (or written), a short count from a synchronous write, or a negative errno.
 * chunk is the WRITE size (the server's wtmax), depth the slots one handle
 * may keep in flight.
 */
static int wpipe_write(struct wpipe *wp, struct nfsfh *fh, const char *buf, int length,
                       uint64_t offset, size_t chunk, int depth) {
    int rc;
    size_t done = 0;

    wpipe_harvest(wp);
    rc = wpipe_fault_rc(wp, fh);
    if (rc < 0) {
        return rc;
    }
    if (length <= 0) {
        return wp->ops->write_sync(wp->nfs, fh, buf, (size_t) (length < 0 ? 0 : length),
                                   offset);
    }
    rc = wpipe_settle_range(wp, fh, offset, (size_t) length);
    if (rc < 0) {
        return rc;
    }
    rc = wpipe_fault_rc(wp, fh);
    if (rc < 0) {
        return rc;
    }
    if (chunk == 0) {
        chunk = (size_t) length;
    }
    if ((size_t) length < WPIPE_MIN || depth < 2) {
        return wp->ops->write_sync(wp->nfs, fh, buf, (size_t) length, offset);
    }
    while (done < (size_t) length) {
        size_t count = (size_t) length - done;
        struct wpipe_slot *slot;

        if (count > chunk) {
            count = chunk;
        }
        slot = wpipe_claim(wp, fh, depth, &rc);
        if (slot == NULL) {
            return rc;
        }
        rc = wpipe_fault_rc(wp, fh);
        if (rc < 0) {
            return rc;
        }
        slot->buf = malloc(count);
        if (slot->buf == NULL) {
            wp->ops->set_error(wp->nfs, "Out of memory for an NFS write buffer");
            return -ENOMEM;
        }
        memcpy(slot->buf, buf + done, count);
        slot->owner = wp;
        slot->fh = fh;
        slot->offset = offset + done;
        slot->count = count;
        slot->result = 0;
        slot->error[0] = '\0';
        slot->state = 1;
        if (wp->ops->issue(wp->nfs, fh, slot->buf, count, slot->offset, wpipe_cb, slot) != 0) {
            wpipe_slot_free(slot);
            /* Prefix chunks are already in flight; the caller retries the tail. */
            if (done > 0) {
                wpipe_push(wp);
                return (int) done;
            }
            return -EIO;
        }
        done += count;
    }
    wpipe_push(wp);
    return length;
}

/* fsync and close: every WRITE of this handle answered, then its error. */
static int wpipe_flush(struct wpipe *wp, struct nfsfh *fh) {
    int rc = wpipe_drain(wp, fh);

    if (rc < 0) {
        return rc;
    }
    return wpipe_fault_rc(wp, fh);
}

/*
 * Before nfs_close frees fh. Returns the handle's write error, if any, and
 * forgets it. *stuck is set when a slot is still in flight: the caller must
 * abort pending RPCs and call wpipe_forget() before closing the handle.
 */
static int wpipe_close(struct wpipe *wp, struct nfsfh *fh, int *stuck) {
    int rc = wpipe_drain(wp, fh);
    int fault;

    *stuck = rc < 0 && wpipe_busy(wp, fh);
    fault = wpipe_fault_rc(wp, fh);
    if (fault < 0) {
        rc = fault;
    }
    if (!*stuck) {
        wpipe_fault_remove(wp, fh);
    }
    return rc;
}

/*
 * After the caller aborted pending RPCs: retires what finished and detaches
 * slots that are still in flight from fh (or from all handles when fh is
 * NULL), so nothing refers to a freed handle. A detached slot keeps its
 * buffer until its callback runs. Returns the number of detached slots.
 */
static int wpipe_forget(struct wpipe *wp, struct nfsfh *fh) {
    int i;
    int detached = 0;

    for (i = 0; i < PIPE_SLOTS; i++) {
        struct wpipe_slot *slot = &wp->slot[i];

        if (slot->state == 0 || (fh != NULL && slot->fh != fh)) {
            continue;
        }
        if (slot->state == 2) {
            wpipe_slot_free(slot);
            continue;
        }
        slot->fh = NULL;
        detached++;
    }
    if (fh != NULL) {
        wpipe_fault_remove(wp, fh);
    } else {
        while (wp->faults != NULL) {
            struct wpipe_fault *fault = wp->faults;

            wp->faults = fault->next;
            free(fault);
        }
        wp->lost_fault_rc = 0;
        wp->lost_fault_error[0] = '\0';
    }
    return detached;
}

#endif
