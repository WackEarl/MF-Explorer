#include <jni.h>

#include <errno.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <sys/time.h>

#include <unistd.h>

#include <nfsc/libnfs.h>

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
    /* libnfs hard-mounts forever unless this is cleared. */
    nfs_set_autoreconnect(nfs, 0);
    if (timeout_ms > 0) {
        nfs_set_timeout(nfs, timeout_ms);
    }
    tls_errno = 0;
    return (jlong) (intptr_t) nfs;
}

JNIEXPORT void JNICALL
Java_me_zhanghai_android_files_provider_nfs_client_NfsNative_destroy(JNIEnv *env, jclass clazz,
                                                                    jlong context) {
    (void) env;
    (void) clazz;
    if (context != 0) {
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
    return (jlong) (intptr_t) file;
}

JNIEXPORT jint JNICALL
Java_me_zhanghai_android_files_provider_nfs_client_NfsNative_closeFile(JNIEnv *env, jclass clazz,
                                                                      jlong context, jlong file) {
    (void) env;
    (void) clazz;
    return fail(nfs_close(context_from(context), file_from(file)));
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
    rc = nfs_pread(context_from(context), file_from(file), bytes, (size_t) length,
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
    return fail(nfs_ftruncate(context_from(context), file_from(file), (uint64_t) size));
}

JNIEXPORT jint JNICALL
Java_me_zhanghai_android_files_provider_nfs_client_NfsNative_list(JNIEnv *env, jclass clazz,
                                                                 jlong context, jbyteArray path,
                                                                 jobject out) {
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
    list_class = (*env)->GetObjectClass(env, out);
    add = (*env)->GetMethodID(env, list_class, "add", "(Ljava/lang/Object;)Z");
    if (add == NULL) {
        nfs_closedir(context_from(context), dir);
        return -EIO;
    }
    while ((entry = nfs_readdir(context_from(context), dir)) != NULL) {
        jsize name_length;
        jbyteArray name;
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
        (*env)->CallBooleanMethod(env, out, add, name);
        (*env)->DeleteLocalRef(env, name);
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
