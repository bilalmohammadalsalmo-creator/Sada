// طبقة رقيقة فوق واجهة Linux DVB العامة (linux/dvb/dmx.h): فتح مرشّح على demux وقراءة TS من dvr.
// تُحمَّل داخل عملية مساعدة بصلاحية shell (انظر DvbHelperMain.kt).
#include <jni.h>
#include <errno.h>
#include <fcntl.h>
#include <poll.h>
#include <string.h>
#include <sys/ioctl.h>
#include <unistd.h>
#include <linux/dvb/dmx.h>

// رموز الخطأ: -(1000+errno) فشل open ، -(2000+errno) فشل ioctl ، -(3000+errno) فشل poll/read
JNIEXPORT jint JNICALL Java_app_sadasync_dvb_DvbNative_openFilter(JNIEnv *env, jclass cls, jstring jpath, jint pid) {
    const char *path = (*env)->GetStringUTFChars(env, jpath, 0);
    int fd = open(path, O_RDWR);
    int e = errno;
    (*env)->ReleaseStringUTFChars(env, jpath, path);
    if (fd < 0) return -(1000 + e);
    ioctl(fd, DMX_SET_BUFFER_SIZE, 4 * 1024 * 1024); // قد يفشل؛ نتجاهله
    struct dmx_pes_filter_params p;
    memset(&p, 0, sizeof(p));
    p.pid = (__u16)pid;                 // 0x2000 = كل الحزم
    p.input = DMX_IN_FRONTEND;
    p.output = DMX_OUT_TSDEMUX_TAP;     // تخرج حزم TS على جهاز dvr المقابل
    p.pes_type = DMX_PES_OTHER;
    p.flags = DMX_IMMEDIATE_START;
    if (ioctl(fd, DMX_SET_PES_FILTER, &p) < 0) {
        int e2 = errno;
        close(fd);
        return -(2000 + e2);
    }
    return fd;
}

JNIEXPORT jint JNICALL Java_app_sadasync_dvb_DvbNative_openDvr(JNIEnv *env, jclass cls, jstring jpath) {
    const char *path = (*env)->GetStringUTFChars(env, jpath, 0);
    int fd = open(path, O_RDONLY | O_NONBLOCK);
    int e = errno;
    (*env)->ReleaseStringUTFChars(env, jpath, path);
    return fd < 0 ? -(1000 + e) : fd;
}

JNIEXPORT jint JNICALL Java_app_sadasync_dvb_DvbNative_readFd(JNIEnv *env, jclass cls, jint fd, jbyteArray buf, jint timeoutMs) {
    struct pollfd pfd;
    pfd.fd = fd;
    pfd.events = POLLIN;
    pfd.revents = 0;
    int pr = poll(&pfd, 1, timeoutMs);
    if (pr < 0) return -(3000 + errno);
    if (pr == 0) return 0;
    jsize len = (*env)->GetArrayLength(env, buf);
    jbyte *b = (*env)->GetByteArrayElements(env, buf, 0);
    ssize_t n = read(fd, b, len);
    int e = errno;
    (*env)->ReleaseByteArrayElements(env, buf, b, 0);
    if (n < 0) return e == EAGAIN ? 0 : -(3000 + e);
    return (jint)n;
}

JNIEXPORT void JNICALL Java_app_sadasync_dvb_DvbNative_stopFilter(JNIEnv *env, jclass cls, jint fd) {
    if (fd >= 0) ioctl(fd, DMX_STOP);
}

JNIEXPORT void JNICALL Java_app_sadasync_dvb_DvbNative_closeFd(JNIEnv *env, jclass cls, jint fd) {
    if (fd >= 0) close(fd);
}
