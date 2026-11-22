#ifndef MATERIALFILES_NFS_PIPE_DEPTH_H
#define MATERIALFILES_NFS_PIPE_DEPTH_H

#include <stddef.h>

#define PIPE_SLOTS 16

/*
 * Depth used when the socket buffer is smaller than two reads. Android
 * clamps SO_RCVBUF to net.core.rmem_max, which AOSP sets to 256 KiB, so a
 * depth taken from the buffer alone stays at 1 and the pipeline never
 * starts. Replies past the window wait in the server's send queue and TCP
 * flow control paces them; the reader drains them in nfs_service. Four
 * 1 MiB reads keep one round trip and the server's per-READ latency
 * covered without parking much data on the server or delaying a listing
 * that queues behind them by more than four reads.
 */
#define PIPE_MIN_DEPTH 4

/*
 * Read-ahead depth for reads of iosize bytes. got is SO_RCVBUF as returned
 * by getsockopt (Linux reports twice the bytes the socket holds), or <= 0
 * when it could not be read. A larger buffer still raises the depth up to
 * PIPE_SLOTS.
 */
static inline int pipe_depth_from_rcvbuf(int got, size_t iosize) {
    size_t usable;
    size_t depth;

    if (iosize == 0) {
        iosize = 1024 * 1024;
    }
    if (got <= 0) {
        return PIPE_MIN_DEPTH;
    }
    usable = (size_t) got / 2;
    depth = usable / iosize;
    if (depth < PIPE_MIN_DEPTH) {
        depth = PIPE_MIN_DEPTH;
    }
    if (depth > PIPE_SLOTS) {
        depth = PIPE_SLOTS;
    }
    return (int) depth;
}

#endif
