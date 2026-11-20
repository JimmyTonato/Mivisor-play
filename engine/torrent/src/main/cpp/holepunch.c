#define _GNU_SOURCE
#include <jni.h>
#include <fcntl.h>
#include <errno.h>
#include <unistd.h>
#include <linux/falloc.h>

JNIEXPORT jint JNICALL
Java_com_mivisor_play_torrent_HolePuncher_punch(JNIEnv *env, jclass clazz, jstring path, jlong offset, jlong length) {
    if (length <= 0) return 0;
    const char *p = (*env)->GetStringUTFChars(env, path, NULL);
    int fd = open(p, O_RDWR | O_CLOEXEC);
    (*env)->ReleaseStringUTFChars(env, path, p);
    if (fd < 0) return -errno;
    int r = fallocate(fd, FALLOC_FL_PUNCH_HOLE | FALLOC_FL_KEEP_SIZE, (off_t) offset, (off_t) length);
    int err = errno;
    close(fd);
    return r == 0 ? 0 : -err;
}
