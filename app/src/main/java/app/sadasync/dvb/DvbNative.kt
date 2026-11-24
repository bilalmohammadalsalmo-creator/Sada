package app.sadasync.dvb

/** واجهة JNI للمكتبة libsada_dvb.so. تُستدعى فقط من العملية المساعدة (بصلاحية shell). */
object DvbNative {
    @JvmStatic external fun openFilter(demuxPath: String, pid: Int, output: Int): Int
    @JvmStatic external fun openDvr(dvrPath: String): Int
    @JvmStatic external fun readFd(fd: Int, buf: ByteArray, timeoutMs: Int): Int
    @JvmStatic external fun stopFilter(fd: Int)
    @JvmStatic external fun closeFd(fd: Int)
}
