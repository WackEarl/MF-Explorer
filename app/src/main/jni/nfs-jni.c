#include <jni.h>

#include <errno.h>
#include <poll.h>
#include <pthread.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <time.h>

#include <nfsc/libnfs.h>
#include <nfsc/libnfs-raw.h>
#include <fcntl.h>

/* libnfs-raw-nfs.h defines NFS_V3. The public header only names it. */
#define NFS_VERSION_3 3

static __thread int tls_errno;

static int fail(int rc) {
    if (rc == -1) {
        tls_errno = EIO;
    } else if (rc < 0) {
        tls_errno = -rc;
    } else {
        tls_errno = 0;
    }
    return rc;
}

static struct nfs_context *context_from(jlong context) {
    return (struct nfs_context *) (intptr_t) context;
}

static struct nfsfh *file_from(jlong file) {
    return (struct nfsfh *) (intptr_t) file;
}

static char *bytes_dup(JNIEnv *env, jbyteArray array) {
    jsize length;
    char *copy;

    if (array == NULL) {
        return NULL;
    }
    length = (*env)->GetArrayLength(env, array);
    copy = malloc((size_t) length + 1);
    if (copy == NULL) {
        return NULL;
    }
    if (length > 0) {
        (*env)->GetByteArrayRegion(env, array, 0, length, (jbyte *) copy);
    }
    copy[length] = '\0';
    return copy;
}

static int is_dot_or_dotdot(const char *name) {
    return name[0] == '.' && (name[1] == '\0' || (name[1] == '.' && name[2] == '\0'));
}

JNIEXPORT jint JNICALL
Java_me_zhanghai_android_files_provider_nfs_client_NfsNative_takeErrno(JNIEnv *env, jclass clazz) {
    int value = tls_errno;
    (void) env;
    (void) clazz;
    tls_errno = 0;
    return value;
}

JNIEXPORT jstring JNICALL
Java_me_zhanghai_android_files_provider_nfs_client_NfsNative_getError(JNIEnv *env, jclass clazz,
                                                                     jlong context) {
    const char *error = nfs_get_error(context_from(context));
    (void) clazz;
    if (error == NULL) {
        error = "";
    }
    return (*env)->NewStringUTF(env, error);
}

JNIEXPORT jboolean JNICALL
Java_me_zhanghai_android_files_provider_nfs_client_NfsNative_sessionUp(JNIEnv *env, jclass clazz,
                                                                      jlong context) {
    (void) env;
    (void) clazz;
    if (context == 0) {
        return JNI_FALSE;
    }
    /* fd == -1 after a reconnect gave up. The next RPC would sit in the
     * outqueue until the timeout, because nothing is connected to write to. */
    return nfs_get_fd(context_from(context)) >= 0 ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jlong JNICALL
Java_me_zhanghai_android_files_provider_nfs_client_NfsNative_create(JNIEnv *env, jclass clazz,
                                                                   jint uid, jint gid,
                                                                   jint timeout_ms) {
    struct nfs_context *nfs = nfs_init_context();
    (void) env;
    (void) clazz;
    if (nfs == NULL) {
        tls_errno = ENOMEM;
        return 0;
    }
    if (nfs_set_version(nfs, NFS_VERSION_3) != 0) {
        tls_errno = EINVAL;
        nfs_destroy_context(nfs);
        return 0;
    }
    nfs_set_uid(nfs, uid);
    nfs_set_gid(nfs, gid);
    /*
     * -1 is libnfs's hard mount and retries forever. One reconnect covers a
     * dropped TCP session (Wi-Fi sleep, Unraid RST). retrans stays 0: with
     * retrans > 0 libnfs rearms the reconnect counter after every handshake
     * that completes, so a peer that accepts TCP and then stays silent never
     * fails the call. An unanswered RPC now ends at the timeout. tcp_syncnt 3
     * bounds a reconnect the host never answers (about fifteen seconds).
     */
    nfs_set_autoreconnect(nfs, 1);
    nfs_set_retrans(nfs, 0);
    nfs_set_tcp_syncnt(nfs, 3);
    /* 1 MiB. Overridden again after mount; FSINFO would otherwise clamp
     * this back down to dtpref before we get a chance. */
    nfs_set_readdir_max_buffer_size(nfs, 1024 * 1024, 1024 * 1024);
    if (timeout_ms > 0) {
        nfs_set_timeout(nfs, timeout_ms);
    }
    tls_errno = 0;
    return (jlong) (intptr_t) nfs;
}

static void pipe_drop(struct nfs_context *nfs, struct nfsfh *fh);
static void pipe_invalidate(struct nfs_context *nfs, struct nfsfh *fh);

JNIEXPORT void JNICALL
Java_me_zhanghai_android_files_provider_nfs_client_NfsNative_destroy(JNIEnv *env, jclass clazz,
                                                                    jlong context) {
    (void) env;
    (void) clazz;
    if (context != 0) {
        pipe_drop(context_from(context), NULL);
        nfs_destroy_context(context_from(context));
    }
}

JNIEXPORT jint JNICALL
Java_me_zhanghai_android_files_provider_nfs_client_NfsNative_mount(JNIEnv *env, jclass clazz,
                                                                  jlong context, jstring host,
                                                                  jbyteArray export_path,
                                                                  jint nfs_port, jint mount_port) {
    const char *host_chars;
    char *export_chars;
    int rc;
    (void) clazz;
    /* A configured port skips the portmapper for that protocol. The same value
     * is passed for both when the UI only has one port field. */
    if (nfs_port > 0) {
        nfs_set_nfsport(context_from(context), nfs_port);
    }
    if (mount_port > 0) {
        nfs_set_mountport(context_from(context), mount_port);
    }
    host_chars = (*env)->GetStringUTFChars(env, host, NULL);
    export_chars = bytes_dup(env, export_path);
    if (host_chars == NULL || export_chars == NULL) {
        if (host_chars != NULL) {
            (*env)->ReleaseStringUTFChars(env, host, host_chars);
        }
        free(export_chars);
        tls_errno = ENOMEM;
        return -ENOMEM;
    }
    rc = nfs_mount(context_from(context), host_chars, export_chars);
    /* FSINFO clamps rsize, wsize and readdir to the advertised maxima.
     * Ask for 1 MiB afterwards anyway: a short reply is legal, and the
     * kernel mount that fills the link uses that size. */
    if (rc == 0) {
        nfs_set_readmax(context_from(context), 1024 * 1024);
        nfs_set_writemax(context_from(context), 1024 * 1024);
        nfs_set_readdir_max_buffer_size(context_from(context), 1024 * 1024, 1024 * 1024);
    }
    (*env)->ReleaseStringUTFChars(env, host, host_chars);
    free(export_chars);
    return fail(rc);
}

JNIEXPORT jlong JNICALL
Java_me_zhanghai_android_files_provider_nfs_client_NfsNative_open(JNIEnv *env, jclass clazz,
                                                                 jlong context, jbyteArray path,
                                                                 jint flags, jint mode) {
    char *path_chars = bytes_dup(env, path);
    struct nfsfh *file = NULL;
    int rc;
    (void) clazz;
    if (path_chars == NULL) {
        tls_errno = ENOMEM;
        return 0;
    }
    rc = nfs_open2(context_from(context), path_chars, flags, mode, &file);
    free(path_chars);
    fail(rc);
    if (rc < 0) {
        return 0;
    }
    if ((flags & O_TRUNC) != 0) {
        pipe_invalidate(context_from(context), file);
    }
    return (jlong) (intptr_t) file;
}

JNIEXPORT jint JNICALL
Java_me_zhanghai_android_files_provider_nfs_client_NfsNative_closeFile(JNIEnv *env, jclass clazz,
                                                                      jlong context, jlong file) {
    (void) env;
    (void) clazz;
    if (context != 0 && file != 0) {
        pipe_drop(context_from(context), file_from(file));
    }
    return fail(nfs_close(context_from(context), file_from(file)));
}

/*
 * Sequential bulk reads keep several READ RPCs in flight on this one
 * connection. Slot buffers are ours. The Java array is only filled after
 * the callback, so returning cannot free memory libnfs is still reading into.
 *
 * The window stays shut until this file has returned 8 MiB of contiguous
 * full reads. A listing never comes through here. One nfs_pread_async per
 * slot: a single larger call is split and waited inside libnfs.
 *
 * Callbacks only record status. They take no lock and issue no RPC.
 * The mount lock already keeps nfs_service on one thread.
 */
#define PIPE_SLOTS 16
#define PIPE_GATE (8 * 1024 * 1024)
#define PIPE_MIN (256 * 1024)

struct pipe_slot {
    int state;
    int epoch;
    int result;
    /* Set while libnfs still owns buf. Cover must not return it, and the
     * buffer must not be freed until the callback moves the slot to state 2. */
    int discard;
    struct nfsfh *fh;
    uint64_t offset;
    size_t count;
    char *buf;
};

struct pipe_req {
    struct pipe_slot *slot;
    int epoch;
};

struct pipe {
    struct pipe *next;
    struct nfs_context *nfs;
    struct nfsfh *fh;
    uint64_t seq;
    uint64_t expect;
    size_t iosize;
    int depth;
    int primed;
    int have_expect;
    struct pipe_slot slot[PIPE_SLOTS];
};

static struct pipe *pipes;
static pthread_mutex_t pipe_lock = PTHREAD_MUTEX_INITIALIZER;

void rpc_abort_pending(struct rpc_context *rpc, const char *error, int reconnect);

static void pipe_cb(int status, struct nfs_context *nfs, void *data, void *private_data) {
    struct pipe_req *req = private_data;
    struct pipe_slot *slot = req->slot;

    (void) nfs;
    (void) data;
    if (slot->epoch == req->epoch && slot->state == 1) {
        slot->result = status;
        slot->state = 2;
    }
    free(req);
}

static struct pipe *pipe_find(struct nfs_context *nfs, int create) {
    struct pipe *pipe;

    pthread_mutex_lock(&pipe_lock);
    for (pipe = pipes; pipe != NULL; pipe = pipe->next) {
        if (pipe->nfs == nfs) {
            pthread_mutex_unlock(&pipe_lock);
            return pipe;
        }
    }
    if (!create) {
        pthread_mutex_unlock(&pipe_lock);
        return NULL;
    }
    pipe = calloc(1, sizeof(*pipe));
    if (pipe == NULL) {
        pthread_mutex_unlock(&pipe_lock);
        return NULL;
    }
    pipe->nfs = nfs;
    pipe->next = pipes;
    pipes = pipe;
    pthread_mutex_unlock(&pipe_lock);
    return pipe;
}

static void pipe_unlink(struct pipe *pipe) {
    struct pipe **link;

    pthread_mutex_lock(&pipe_lock);
    link = &pipes;
    while (*link != NULL) {
        if (*link == pipe) {
            *link = pipe->next;
            break;
        }
        link = &(*link)->next;
    }
    pthread_mutex_unlock(&pipe_lock);
    pipe->next = NULL;
}

static int64_t pipe_now_ms(void) {
    struct timespec ts;

    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (int64_t) ts.tv_sec * 1000 + ts.tv_nsec / 1000000;
}

static int pipe_service(struct nfs_context *nfs, int timeout_ms) {
    struct pollfd pfd;
    int rc;

    pfd.fd = nfs_get_fd(nfs);
    pfd.events = nfs_which_events(nfs);
    pfd.revents = 0;
    if (pfd.fd < 0) {
        return -1;
    }
    rc = poll(&pfd, 1, timeout_ms);
    if (rc < 0) {
        if (errno == EINTR) {
            return 0;
        }
        return -1;
    }
    if (rc == 0) {
        return 0;
    }
    if (nfs_service(nfs, pfd.revents) < 0) {
        return -1;
    }
    return 1;
}

/* Replies that arrived while this thread was outside nfs_service. */
static void pipe_harvest(struct nfs_context *nfs) {
    int i;

    for (i = 0; i < 64; i++) {
        if (pipe_service(nfs, 0) <= 0) {
            return;
        }
    }
}

static int pipe_wait(struct nfs_context *nfs, struct pipe_slot *slot) {
    int timeout = nfs_get_timeout(nfs);
    int64_t deadline;

    if (timeout <= 0) {
        timeout = 30000;
    }
    deadline = pipe_now_ms() + timeout;
    while (slot->state == 1) {
        int64_t now = pipe_now_ms();
        int slice;

        if (now >= deadline) {
            return -ETIMEDOUT;
        }
        slice = (int) (deadline - now);
        if (slice > 250) {
            slice = 250;
        }
        if (slice < 1) {
            slice = 1;
        }
        if (pipe_service(nfs, slice) < 0 && slot->state == 1) {
            return -EIO;
        }
    }
    return 0;
}

static void pipe_slot_free(struct pipe_slot *slot) {
    slot->epoch++;
    slot->state = 0;
    slot->result = 0;
    slot->discard = 0;
    slot->fh = NULL;
    slot->offset = 0;
    slot->count = 0;
    free(slot->buf);
    slot->buf = NULL;
}

/* libnfs dropped the PDU: RPC timeout/cancel is -EINTR, RPC error is -EFAULT.
 * The bytes are not a file result and the buffer is no longer in a readv. */
static int pipe_lost(int rc) {
    return rc == -EINTR || rc == -EFAULT;
}

static void pipe_sweep(struct pipe *pipe) {
    int i;

    if (pipe == NULL) {
        return;
    }
    for (i = 0; i < PIPE_SLOTS; i++) {
        struct pipe_slot *slot = &pipe->slot[i];

        if (slot->state == 2 && (slot->discard || pipe_lost(slot->result))) {
            pipe_slot_free(slot);
        }
    }
}

static void pipe_flush(struct nfs_context *nfs) {
    int i;

    for (i = 0; i < 32; i++) {
        if ((nfs_which_events(nfs) & POLLOUT) == 0) {
            return;
        }
        if (pipe_service(nfs, 0) < 0) {
            return;
        }
    }
}

/*
 * Linux returns SO_RCVBUF as twice the bytes the socket will hold.
 * Depth follows that, so a clamped set does not issue more than fits.
 */
static int pipe_depth_for(struct nfs_context *nfs, size_t iosize) {
    int fd = nfs_get_fd(nfs);
    int got = 0;
    socklen_t len = sizeof(got);
    int usable;
    int depth;

    if (iosize == 0) {
        iosize = 1024 * 1024;
    }
    if (fd < 0 || getsockopt(fd, SOL_SOCKET, SO_RCVBUF, &got, &len) != 0 || got <= 0) {
        return 1;
    }
    usable = got / 2;
    depth = usable / (int) iosize;
    if (depth < 1) {
        depth = 1;
    }
    if (depth > PIPE_SLOTS) {
        depth = PIPE_SLOTS;
    }
    return depth;
}

static struct pipe_slot *pipe_cover(struct pipe *pipe, struct nfsfh *fh, uint64_t offset,
                                    size_t length) {
    int i;

    for (i = 0; i < PIPE_SLOTS; i++) {
        struct pipe_slot *slot = &pipe->slot[i];

        if (slot->state == 0 || slot->discard || slot->fh != fh) {
            continue;
        }
        if (slot->state == 2 && pipe_lost(slot->result)) {
            continue;
        }
        if (slot->offset == offset && slot->count == length) {
            return slot;
        }
    }
    return NULL;
}

static void pipe_reclaim_done(struct pipe *pipe) {
    int i;

    if (pipe == NULL) {
        return;
    }
    for (i = 0; i < PIPE_SLOTS; i++) {
        struct pipe_slot *slot = &pipe->slot[i];

        if (slot->state != 2) {
            continue;
        }
        /* Drop lost and already-consumed slots. A finished read that is
         * still ahead of expect belongs to a later call, including one
         * that was in flight for another file. */
        if (slot->discard || pipe_lost(slot->result) ||
            (slot->fh == pipe->fh && slot->offset + slot->count <= pipe->expect)) {
            pipe_slot_free(slot);
        }
    }
}

static struct pipe_slot *pipe_unused(struct pipe *pipe, struct nfsfh *fh, uint64_t floor) {
    int i;

    for (i = 0; i < PIPE_SLOTS; i++) {
        if (pipe->slot[i].state == 0) {
            return &pipe->slot[i];
        }
    }
    for (i = 0; i < PIPE_SLOTS; i++) {
        struct pipe_slot *slot = &pipe->slot[i];

        /* Never recycle a finished read at or after floor. That is data
         * this call, or a fill started for this call, still has to return. */
        if (slot->state == 2 &&
            (slot->discard || pipe_lost(slot->result) ||
             slot->fh != fh || slot->offset + slot->count <= floor)) {
            pipe_slot_free(slot);
            return slot;
        }
    }
    return NULL;
}

static int pipe_behind(struct pipe_slot *slot, struct nfsfh *fh, uint64_t floor) {
    return slot->fh != fh || slot->offset + slot->count <= floor;
}

static struct pipe_slot *pipe_issue(struct pipe *pipe, struct nfsfh *fh, uint64_t offset,
                                    size_t count, uint64_t floor) {
    struct pipe_slot *slot;
    struct pipe_req *req;
    int i;

    for (i = 0; i < PIPE_SLOTS; i++) {
        slot = &pipe->slot[i];
        if (slot->state == 0) {
            continue;
        }
        if (slot->discard || (slot->state == 2 && pipe_lost(slot->result))) {
            if (slot->state == 2) {
                pipe_slot_free(slot);
            }
            continue;
        }
        if (slot->fh == fh && slot->offset == offset && slot->count == count) {
            return slot;
        }
    }
    slot = pipe_unused(pipe, fh, floor);
    if (slot == NULL) {
        for (i = 0; i < PIPE_SLOTS; i++) {
            if (pipe->slot[i].state != 1) {
                continue;
            }
            if (pipe_wait(pipe->nfs, &pipe->slot[i]) < 0) {
                return NULL;
            }
            if (pipe->slot[i].state == 2 &&
                (pipe->slot[i].discard || pipe_lost(pipe->slot[i].result) ||
                 pipe_behind(&pipe->slot[i], fh, floor))) {
                pipe_slot_free(&pipe->slot[i]);
            }
            break;
        }
        slot = pipe_unused(pipe, fh, floor);
        if (slot == NULL) {
            return NULL;
        }
    }
    slot->buf = malloc(count > 0 ? count : 1);
    req = malloc(sizeof(*req));
    if (slot->buf == NULL || req == NULL) {
        free(slot->buf);
        free(req);
        slot->buf = NULL;
        return NULL;
    }
    slot->epoch++;
    slot->fh = fh;
    slot->offset = offset;
    slot->count = count;
    slot->result = 0;
    slot->discard = 0;
    slot->state = 1;
    req->slot = slot;
    req->epoch = slot->epoch;
    if (nfs_pread_async(pipe->nfs, fh, slot->buf, count, offset, pipe_cb, req) != 0) {
        free(req);
        free(slot->buf);
        slot->buf = NULL;
        slot->state = 0;
        slot->discard = 0;
        slot->fh = NULL;
        slot->count = 0;
        return NULL;
    }
    return slot;
}

static void pipe_fill(struct pipe *pipe, struct nfsfh *fh, uint64_t offset) {
    int i;
    uint64_t at = offset;

    for (i = 0; i < pipe->depth; i++) {
        struct pipe_slot *slot = pipe_issue(pipe, fh, at, pipe->iosize, offset);

        if (slot == NULL) {
            break;
        }
        at += pipe->iosize;
        if (slot->state == 2 && slot->result >= 0 &&
            (size_t) slot->result < slot->count) {
            break;
        }
    }
    pipe_flush(pipe->nfs);
}

static void pipe_refill(struct pipe *pipe, struct nfsfh *fh) {
    int have = 0;
    int i;
    uint64_t horizon;
    uint64_t ceiling = UINT64_MAX;

    if (!pipe->primed || pipe->fh != fh || pipe->iosize == 0 || pipe->depth < 2) {
        return;
    }
    pipe_sweep(pipe);
    horizon = pipe->expect;
    for (i = 0; i < PIPE_SLOTS; i++) {
        struct pipe_slot *slot = &pipe->slot[i];

        if (slot->state == 0 || slot->discard || slot->fh != fh) {
            continue;
        }
        if (slot->state == 2 && pipe_lost(slot->result)) {
            pipe_slot_free(slot);
            continue;
        }
        if (slot->state == 2 && slot->result >= 0 &&
            (size_t) slot->result < slot->count) {
            if (slot->offset < ceiling) {
                ceiling = slot->offset;
            }
            have++;
            continue;
        }
        if (slot->state == 2 && slot->offset + slot->count <= pipe->expect) {
            pipe_slot_free(slot);
            continue;
        }
        have++;
        if (slot->offset + slot->count > horizon) {
            horizon = slot->offset + slot->count;
        }
    }
    while (have < pipe->depth && horizon < ceiling) {
        if (pipe_issue(pipe, fh, horizon, pipe->iosize, pipe->expect) == NULL) {
            break;
        }
        horizon += pipe->iosize;
        have++;
    }
    pipe_flush(pipe->nfs);
}

static void pipe_note(struct pipe *pipe, struct nfsfh *fh, uint64_t offset, int rc, int length) {
    size_t readmax = nfs_get_readmax(pipe->nfs);

    if (pipe->fh != fh || (pipe->have_expect && pipe->expect != offset)) {
        pipe->seq = 0;
        pipe->primed = 0;
        pipe->have_expect = 0;
    }
    if (!pipe->have_expect) {
        pipe->fh = fh;
        pipe->expect = offset;
        pipe->have_expect = 1;
        pipe->seq = 0;
        pipe->primed = 0;
    }
    if (rc == length && offset == pipe->expect &&
        (readmax == 0 || (size_t) length <= readmax)) {
        pipe->expect = offset + (uint64_t) rc;
        if (length >= PIPE_MIN) {
            pipe->seq += (uint64_t) rc;
            if (!pipe->primed && pipe->seq >= (uint64_t) PIPE_GATE) {
                int depth = pipe_depth_for(pipe->nfs, (size_t) length);

                if (depth >= 2) {
                    pipe->primed = 1;
                    pipe->iosize = (size_t) length;
                    pipe->depth = depth;
                }
            }
        }
    } else {
        pipe->seq = 0;
        pipe->primed = 0;
        if (rc > 0) {
            pipe->expect = offset + (uint64_t) rc;
        }
    }
}

static int pipe_take(struct nfs_context *nfs, struct pipe_slot *slot, char *buf, int length) {
    int got;

    if (slot->state == 1) {
        int rc = pipe_wait(nfs, slot);

        if (rc < 0) {
            return rc;
        }
    }
    if (slot->state != 2) {
        return -EIO;
    }
    got = slot->result;
    if (got < 0) {
        pipe_slot_free(slot);
        return got;
    }
    if (got > length) {
        got = length;
    }
    if (got > 0) {
        memcpy(buf, slot->buf, (size_t) got);
    }
    pipe_slot_free(slot);
    return got;
}

static void pipe_settle(struct nfs_context *nfs) {
    int64_t deadline = pipe_now_ms() + 15000;

    while (pipe_now_ms() < deadline) {
        int fd = nfs_get_fd(nfs);
        int events;

        if (fd < 0) {
            return;
        }
        events = nfs_which_events(nfs);
        if ((events & POLLIN) != 0) {
            return;
        }
        if (pipe_service(nfs, 250) < 0 && nfs_get_fd(nfs) < 0) {
            return;
        }
    }
}

static void pipe_invalidate(struct nfs_context *nfs, struct nfsfh *fh) {
    struct pipe *pipe;
    int i;

    if (fh == NULL) {
        return;
    }
    pipe = pipe_find(nfs, 0);
    if (pipe == NULL) {
        return;
    }
    for (i = 0; i < PIPE_SLOTS; i++) {
        struct pipe_slot *slot = &pipe->slot[i];

        if (slot->state == 0 || !nfs_fh_equal(slot->fh, fh)) {
            continue;
        }
        if (slot->state == 2) {
            pipe_slot_free(slot);
        } else {
            slot->discard = 1;
        }
    }
    if (nfs_fh_equal(pipe->fh, fh)) {
        pipe->primed = 0;
        pipe->seq = 0;
        pipe->have_expect = 0;
        pipe->fh = NULL;
        pipe->expect = 0;
        pipe->iosize = 0;
        pipe->depth = 0;
    }
    pipe_sweep(pipe);
}

static void pipe_drop(struct nfs_context *nfs, struct nfsfh *fh) {
    struct pipe *pipe;
    int i;
    int stuck = 0;

    pipe = pipe_find(nfs, 0);
    if (pipe == NULL) {
        return;
    }
    for (i = 0; i < PIPE_SLOTS; i++) {
        struct pipe_slot *slot = &pipe->slot[i];

        if (slot->state != 1) {
            continue;
        }
        if (fh != NULL && slot->fh != fh) {
            continue;
        }
        if (pipe_wait(nfs, slot) < 0) {
            stuck = 1;
            break;
        }
    }
    if (stuck) {
        struct rpc_context *rpc = nfs_get_rpc_context(nfs);

        /*
         * Destroy closes the fd itself. Closing one file may reconnect,
         * so a half-read record is not the next reply.
         */
        if (rpc != NULL) {
            int reconnect = fh == NULL ? 0 : 1;

            rpc_abort_pending(rpc, "nfs read dropped", reconnect);
            if (reconnect) {
                pipe_settle(nfs);
            }
        }
    }
    for (i = 0; i < PIPE_SLOTS; i++) {
        struct pipe_slot *slot = &pipe->slot[i];

        if (slot->state == 0) {
            continue;
        }
        if (fh != NULL && slot->fh != fh) {
            continue;
        }
        if (slot->state == 1) {
            if (fh != NULL) {
                /* nfs_close is about to free this handle. The callback
                 * still owns the buffer, so the slot stays until it runs. */
                slot->discard = 1;
                slot->fh = NULL;
            }
            continue;
        }
        pipe_slot_free(slot);
    }
    if (fh == NULL || pipe->fh == fh) {
        pipe->primed = 0;
        pipe->seq = 0;
        pipe->have_expect = 0;
        pipe->fh = NULL;
        pipe->expect = 0;
    }
    if (fh != NULL) {
        return;
    }
    for (i = 0; i < PIPE_SLOTS; i++) {
        if (pipe->slot[i].state == 1) {
            /*
             * A callback can still write this slot. Unlink the pipe and
             * leak it. Freeing it here would be a use-after-free.
             */
            pipe_unlink(pipe);
            pipe->nfs = NULL;
            return;
        }
    }
    pipe_unlink(pipe);
    free(pipe);
}

static int pipe_after(struct pipe *pipe, struct nfsfh *fh, uint64_t offset, int rc, int length) {
    pipe_note(pipe, fh, offset, rc, length);
    if (pipe->primed && rc == length) {
        pipe_refill(pipe, fh);
    } else {
        pipe_reclaim_done(pipe);
    }
    return rc;
}

/* 1: *out is the final result. 0: this offset must be read again. */
static int pipe_use(struct nfs_context *nfs, struct pipe *pipe, struct pipe_slot *slot,
                    struct nfsfh *fh, char *buf, int length, uint64_t offset, int *out) {
    int rc;

    if (slot->state == 1) {
        rc = pipe_wait(nfs, slot);
        if (rc < 0) {
            *out = rc;
            return 1;
        }
    }
    if (slot->state != 2) {
        *out = -EIO;
        return 1;
    }
    /*
     * A short or zero reply is not a cached answer. A cached 0 is EOF, and
     * the file may have grown since that READ. A full slot is the caller's.
     */
    if (slot->result >= 0 && (size_t) slot->result < slot->count) {
        pipe_slot_free(slot);
        return 0;
    }
    rc = pipe_take(nfs, slot, buf, length);
    if (pipe_lost(rc)) {
        return 0;
    }
    *out = pipe_after(pipe, fh, offset, rc, length);
    return 1;
}

static int pipe_pread(struct nfs_context *nfs, struct nfsfh *fh, char *buf, int length,
                      uint64_t offset) {
    struct pipe *pipe;
    struct pipe_slot *slot;
    int rc;
    int reread = 0;

    if (length <= 0) {
        return nfs_pread(nfs, fh, buf, (size_t) length, offset);
    }
    pipe = pipe_find(nfs, 1);
    if (pipe == NULL) {
        return nfs_pread(nfs, fh, buf, (size_t) length, offset);
    }
    pipe_harvest(nfs);
    pipe_sweep(pipe);
    slot = pipe_cover(pipe, fh, offset, (size_t) length);
    if (slot != NULL) {
        if (pipe_use(nfs, pipe, slot, fh, buf, length, offset, &rc)) {
            return rc;
        }
        reread = 1;
    }
    if (!reread && pipe->primed && pipe->fh == fh && pipe->have_expect &&
        pipe->expect == offset && pipe->iosize == (size_t) length && pipe->depth >= 2) {
        pipe_fill(pipe, fh, offset);
        slot = pipe_cover(pipe, fh, offset, (size_t) length);
        if (slot != NULL &&
            pipe_use(nfs, pipe, slot, fh, buf, length, offset, &rc)) {
            return rc;
        }
    }
    rc = nfs_pread(nfs, fh, buf, (size_t) length, offset);
    return pipe_after(pipe, fh, offset, rc, length);
}

JNIEXPORT jint JNICALL
Java_me_zhanghai_android_files_provider_nfs_client_NfsNative_pread(JNIEnv *env, jclass clazz,
                                                                  jlong context, jlong file,
                                                                  jbyteArray buffer, jint length,
                                                                  jlong offset) {
    jbyte *bytes;
    int rc;
    (void) clazz;
    bytes = (*env)->GetByteArrayElements(env, buffer, NULL);
    if (bytes == NULL) {
        tls_errno = ENOMEM;
        return -ENOMEM;
    }
    if (length < 0 || length > (*env)->GetArrayLength(env, buffer)) {
        (*env)->ReleaseByteArrayElements(env, buffer, bytes, JNI_ABORT);
        tls_errno = EINVAL;
        return -EINVAL;
    }
    rc = pipe_pread(context_from(context), file_from(file), (char *) bytes, length,
                     (uint64_t) offset);
    (*env)->ReleaseByteArrayElements(env, buffer, bytes, 0);
    return fail(rc);
}

JNIEXPORT jint JNICALL
Java_me_zhanghai_android_files_provider_nfs_client_NfsNative_pwrite(JNIEnv *env, jclass clazz,
                                                                   jlong context, jlong file,
                                                                   jbyteArray buffer, jint length,
                                                                   jlong offset) {
    jbyte *bytes;
    int rc;
    (void) clazz;
    bytes = (*env)->GetByteArrayElements(env, buffer, NULL);
    if (bytes == NULL) {
        tls_errno = ENOMEM;
        return -ENOMEM;
    }
    if (length < 0 || length > (*env)->GetArrayLength(env, buffer)) {
        (*env)->ReleaseByteArrayElements(env, buffer, bytes, JNI_ABORT);
        tls_errno = EINVAL;
        return -EINVAL;
    }
    pipe_invalidate(context_from(context), file_from(file));
    rc = nfs_pwrite(context_from(context), file_from(file), bytes, (size_t) length,
                    (uint64_t) offset);
    (*env)->ReleaseByteArrayElements(env, buffer, bytes, JNI_ABORT);
    return fail(rc);
}

JNIEXPORT jint JNICALL
Java_me_zhanghai_android_files_provider_nfs_client_NfsNative_fsync(JNIEnv *env, jclass clazz,
                                                                  jlong context, jlong file) {
    (void) env;
    (void) clazz;
    return fail(nfs_fsync(context_from(context), file_from(file)));
}

JNIEXPORT jint JNICALL
Java_me_zhanghai_android_files_provider_nfs_client_NfsNative_ftruncate(JNIEnv *env, jclass clazz,
                                                                      jlong context, jlong file,
                                                                      jlong size) {
    (void) env;
    (void) clazz;
    pipe_invalidate(context_from(context), file_from(file));
    return fail(nfs_ftruncate(context_from(context), file_from(file), (uint64_t) size));
}

static jlongArray stat_result(JNIEnv *env, int rc, const struct nfs_stat_64 *st);

JNIEXPORT jint JNICALL
Java_me_zhanghai_android_files_provider_nfs_client_NfsNative_list(JNIEnv *env, jclass clazz,
                                                                 jlong context, jbyteArray path,
                                                                 jobject names_out, jobject stats_out) {
    char *path_chars = bytes_dup(env, path);
    struct nfsdir *dir = NULL;
    struct nfsdirent *entry;
    jclass list_class;
    jmethodID add;
    int rc;
    (void) clazz;
    if (path_chars == NULL) {
        tls_errno = ENOMEM;
        return -ENOMEM;
    }
    rc = nfs_opendir(context_from(context), path_chars, &dir);
    free(path_chars);
    if (rc < 0) {
        return fail(rc);
    }
    list_class = (*env)->GetObjectClass(env, names_out);
    add = (*env)->GetMethodID(env, list_class, "add", "(Ljava/lang/Object;)Z");
    if (add == NULL) {
        nfs_closedir(context_from(context), dir);
        return -EIO;
    }
    while ((entry = nfs_readdir(context_from(context), dir)) != NULL) {
        jsize name_length;
        jbyteArray name;
        jlongArray attrs;
        struct nfs_stat_64 st;
        if (entry->name == NULL || is_dot_or_dotdot(entry->name)) {
            continue;
        }
        name_length = (jsize) strlen(entry->name);
        name = (*env)->NewByteArray(env, name_length);
        if (name == NULL) {
            nfs_closedir(context_from(context), dir);
            tls_errno = ENOMEM;
            return -ENOMEM;
        }
        if (name_length > 0) {
            (*env)->SetByteArrayRegion(env, name, 0, name_length, (const jbyte *) entry->name);
        }
        /* READDIRPLUS fills mode, including the file type. Zero means the
         * server sent no attributes and the caller must stat. */
        if (entry->mode != 0) {
            memset(&st, 0, sizeof(st));
            st.nfs_mode = entry->mode;
            st.nfs_size = entry->size;
            st.nfs_uid = entry->uid;
            st.nfs_gid = entry->gid;
            st.nfs_atime = entry->atime.tv_sec;
            st.nfs_atime_nsec = entry->atime_nsec;
            st.nfs_mtime = entry->mtime.tv_sec;
            st.nfs_mtime_nsec = entry->mtime_nsec;
            st.nfs_ctime = entry->ctime.tv_sec;
            st.nfs_ctime_nsec = entry->ctime_nsec;
            attrs = stat_result(env, 0, &st);
        } else {
            attrs = stat_result(env, -1, NULL);
        }
        if (attrs == NULL) {
            (*env)->DeleteLocalRef(env, name);
            nfs_closedir(context_from(context), dir);
            tls_errno = ENOMEM;
            return -ENOMEM;
        }
        (*env)->CallBooleanMethod(env, names_out, add, name);
        (*env)->CallBooleanMethod(env, stats_out, add, attrs);
        (*env)->DeleteLocalRef(env, name);
        (*env)->DeleteLocalRef(env, attrs);
        if ((*env)->ExceptionCheck(env)) {
            nfs_closedir(context_from(context), dir);
            return -EIO;
        }
    }
    nfs_closedir(context_from(context), dir);
    tls_errno = 0;
    return 0;
}

static jlongArray stat_result(JNIEnv *env, int rc, const struct nfs_stat_64 *st) {
    jlong values[11];
    jlongArray array;
    values[0] = rc;
    if (rc == 0) {
        values[1] = (jlong) st->nfs_mode;
        values[2] = (jlong) st->nfs_size;
        values[3] = (jlong) st->nfs_uid;
        values[4] = (jlong) st->nfs_gid;
        values[5] = (jlong) st->nfs_atime;
        values[6] = (jlong) st->nfs_atime_nsec;
        values[7] = (jlong) st->nfs_mtime;
        values[8] = (jlong) st->nfs_mtime_nsec;
        values[9] = (jlong) st->nfs_ctime;
        values[10] = (jlong) st->nfs_ctime_nsec;
    }
    array = (*env)->NewLongArray(env, rc == 0 ? 11 : 1);
    if (array == NULL) {
        return NULL;
    }
    (*env)->SetLongArrayRegion(env, array, 0, rc == 0 ? 11 : 1, values);
    return array;
}

JNIEXPORT jlongArray JNICALL
Java_me_zhanghai_android_files_provider_nfs_client_NfsNative_stat(JNIEnv *env, jclass clazz,
                                                                 jlong context, jbyteArray path,
                                                                 jboolean no_follow) {
    char *path_chars = bytes_dup(env, path);
    struct nfs_stat_64 st;
    int rc;
    (void) clazz;
    if (path_chars == NULL) {
        tls_errno = ENOMEM;
        return stat_result(env, -ENOMEM, NULL);
    }
    memset(&st, 0, sizeof(st));
    rc = no_follow ? nfs_lstat64(context_from(context), path_chars, &st)
                   : nfs_stat64(context_from(context), path_chars, &st);
    free(path_chars);
    fail(rc);
    return stat_result(env, rc < 0 ? rc : 0, &st);
}

JNIEXPORT jlongArray JNICALL
Java_me_zhanghai_android_files_provider_nfs_client_NfsNative_fstat(JNIEnv *env, jclass clazz,
                                                                  jlong context, jlong file) {
    struct nfs_stat_64 st;
    int rc;
    (void) clazz;
    memset(&st, 0, sizeof(st));
    rc = nfs_fstat64(context_from(context), file_from(file), &st);
    fail(rc);
    return stat_result(env, rc < 0 ? rc : 0, &st);
}

JNIEXPORT jint JNICALL
Java_me_zhanghai_android_files_provider_nfs_client_NfsNative_mkdir(JNIEnv *env, jclass clazz,
                                                                  jlong context, jbyteArray path,
                                                                  jint mode) {
    char *path_chars = bytes_dup(env, path);
    int rc;
    (void) clazz;
    if (path_chars == NULL) {
        tls_errno = ENOMEM;
        return -ENOMEM;
    }
    rc = nfs_mkdir2(context_from(context), path_chars, mode);
    free(path_chars);
    return fail(rc);
}

JNIEXPORT jint JNICALL
Java_me_zhanghai_android_files_provider_nfs_client_NfsNative_rmdir(JNIEnv *env, jclass clazz,
                                                                  jlong context, jbyteArray path) {
    char *path_chars = bytes_dup(env, path);
    int rc;
    (void) clazz;
    if (path_chars == NULL) {
        tls_errno = ENOMEM;
        return -ENOMEM;
    }
    rc = nfs_rmdir(context_from(context), path_chars);
    free(path_chars);
    return fail(rc);
}

JNIEXPORT jint JNICALL
Java_me_zhanghai_android_files_provider_nfs_client_NfsNative_unlink(JNIEnv *env, jclass clazz,
                                                                   jlong context, jbyteArray path) {
    char *path_chars = bytes_dup(env, path);
    int rc;
    (void) clazz;
    if (path_chars == NULL) {
        tls_errno = ENOMEM;
        return -ENOMEM;
    }
    rc = nfs_unlink(context_from(context), path_chars);
    free(path_chars);
    return fail(rc);
}

JNIEXPORT jint JNICALL
Java_me_zhanghai_android_files_provider_nfs_client_NfsNative_rename(JNIEnv *env, jclass clazz,
                                                                   jlong context, jbyteArray from,
                                                                   jbyteArray to) {
    char *from_chars = bytes_dup(env, from);
    char *to_chars = bytes_dup(env, to);
    int rc;
    (void) clazz;
    if (from_chars == NULL || to_chars == NULL) {
        free(from_chars);
        free(to_chars);
        tls_errno = ENOMEM;
        return -ENOMEM;
    }
    rc = nfs_rename(context_from(context), from_chars, to_chars);
    free(from_chars);
    free(to_chars);
    return fail(rc);
}

JNIEXPORT jint JNICALL
Java_me_zhanghai_android_files_provider_nfs_client_NfsNative_readlink(JNIEnv *env, jclass clazz,
                                                                     jlong context, jbyteArray path,
                                                                     jobjectArray out) {
    char *path_chars = bytes_dup(env, path);
    char *target = NULL;
    int rc;
    (void) clazz;
    if (path_chars == NULL) {
        tls_errno = ENOMEM;
        return -ENOMEM;
    }
    rc = nfs_readlink2(context_from(context), path_chars, &target);
    free(path_chars);
    if (rc < 0 || target == NULL) {
        free(target);
        return fail(rc < 0 ? rc : -EIO);
    }
    {
        jsize length = (jsize) strlen(target);
        jbyteArray bytes = (*env)->NewByteArray(env, length);
        if (bytes == NULL) {
            free(target);
            tls_errno = ENOMEM;
            return -ENOMEM;
        }
        if (length > 0) {
            (*env)->SetByteArrayRegion(env, bytes, 0, length, (const jbyte *) target);
        }
        (*env)->SetObjectArrayElement(env, out, 0, bytes);
        (*env)->DeleteLocalRef(env, bytes);
    }
    free(target);
    tls_errno = 0;
    return 0;
}

JNIEXPORT jint JNICALL
Java_me_zhanghai_android_files_provider_nfs_client_NfsNative_access(JNIEnv *env, jclass clazz,
                                                                   jlong context, jbyteArray path,
                                                                   jint mode) {
    char *path_chars = bytes_dup(env, path);
    int rc;
    (void) clazz;
    if (path_chars == NULL) {
        tls_errno = ENOMEM;
        return -ENOMEM;
    }
    rc = nfs_access(context_from(context), path_chars, mode);
    free(path_chars);
    return fail(rc);
}

JNIEXPORT jint JNICALL
Java_me_zhanghai_android_files_provider_nfs_client_NfsNative_chmod(JNIEnv *env, jclass clazz,
                                                                  jlong context, jbyteArray path,
                                                                  jint mode) {
    char *path_chars = bytes_dup(env, path);
    int rc;
    (void) clazz;
    if (path_chars == NULL) {
        tls_errno = ENOMEM;
        return -ENOMEM;
    }
    rc = nfs_chmod(context_from(context), path_chars, mode);
    free(path_chars);
    return fail(rc);
}

JNIEXPORT jint JNICALL
Java_me_zhanghai_android_files_provider_nfs_client_NfsNative_chown(JNIEnv *env, jclass clazz,
                                                                  jlong context, jbyteArray path,
                                                                  jint uid, jint gid) {
    char *path_chars = bytes_dup(env, path);
    int rc;
    (void) clazz;
    if (path_chars == NULL) {
        tls_errno = ENOMEM;
        return -ENOMEM;
    }
    rc = nfs_chown(context_from(context), path_chars, uid, gid);
    free(path_chars);
    return fail(rc);
}

static long nsec_to_usec(jlong nsec) {
    if (nsec <= 0) {
        return 0;
    }
    if (nsec >= 1000000000LL) {
        return 999999L;
    }
    return (long) (nsec / 1000);
}

JNIEXPORT jint JNICALL
Java_me_zhanghai_android_files_provider_nfs_client_NfsNative_utimes(JNIEnv *env, jclass clazz,
                                                                   jlong context, jbyteArray path,
                                                                   jlong atime_sec, jlong atime_nsec,
                                                                   jlong mtime_sec, jlong mtime_nsec) {
    char *path_chars = bytes_dup(env, path);
    struct timeval times[2];
    int rc;
    (void) clazz;
    if (path_chars == NULL) {
        tls_errno = ENOMEM;
        return -ENOMEM;
    }
    memset(times, 0, sizeof(times));
    times[0].tv_sec = (time_t) atime_sec;
    times[0].tv_usec = nsec_to_usec(atime_nsec);
    times[1].tv_sec = (time_t) mtime_sec;
    times[1].tv_usec = nsec_to_usec(mtime_nsec);
    rc = nfs_utimes(context_from(context), path_chars, times);
    free(path_chars);
    return fail(rc);
}
