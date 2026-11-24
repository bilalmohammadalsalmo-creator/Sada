package app.sadasync.dvb

/**
 * عملية مساعدة تُشغَّل عبر ADB بصلاحية shell:
 *   CLASSPATH=<base.apk> app_process /system/bin app.sadasync.dvb.DvbHelperMain probe <libPath> <audioPid|-1>
 * تفتح مرشّح TS على demux6 وتقرأ من dvr6 بضع ثوانٍ ثم تطبع تقريرًا يجيب: هل نستطيع قراءة صوت القناة؟
 */
object DvbHelperMain {
    private const val DEMUX = "/dev/dvb0.demux6"
    private const val DVR = "/dev/dvb0.dvr6"

    @JvmStatic
    fun main(args: Array<String>) {
        try {
            val lib = args.getOrNull(1) ?: error("no lib path")
            System.load(lib)
            val pid = args.getOrNull(2)?.toIntOrNull() ?: -1
            println("uid=" + android.os.Process.myUid() + " abi-lib=" + lib.substringAfterLast('/'))
            // نجرّب كل الحزم (0x2000) أولًا، ثم PID الصوت وحده إن عُرف
            probe(0x2000, "كل الحزم")
            if (pid in 1..0x1FFE) probe(pid, "PID الصوت 0x" + Integer.toHexString(pid))
        } catch (t: Throwable) {
            println("خطأ في المساعد: $t")
        }
    }

    private fun probe(pid: Int, label: String) {
        println("--- $label ---")
        val ffd = DvbNative.openFilter(DEMUX, pid)
        if (ffd < 0) {
            println("openFilter فشل: $ffd  (-1000-errno فتح، -2000-errno ioctl)")
            return
        }
        val dfd = DvbNative.openDvr(DVR)
        if (dfd < 0) {
            println("openDvr فشل: $dfd")
            DvbNative.stopFilter(ffd); DvbNative.closeFd(ffd)
            return
        }
        val buf = ByteArray(188 * 512)
        var total = 0L
        var packets = 0L
        var badSync = 0L
        val cnt = IntArray(8192)
        val scr = IntArray(8192)
        val pesStart = IntArray(8192)
        val sync = Array(8192) { "" }
        val sid = IntArray(8192)
        val end = System.nanoTime() + 3_000_000_000L
        var carry = 0
        val work = ByteArray(buf.size + 188)
        var err = 0
        while (System.nanoTime() < end) {
            val n = DvbNative.readFd(dfd, buf, 300)
            if (n < 0) { err = n; break }
            if (n == 0) continue
            total += n
            System.arraycopy(buf, 0, work, carry, n)
            val len = carry + n
            var i = 0
            while (i + 188 <= len) {
                if (work[i].toInt() != 0x47) { i++; badSync++; continue }
                packets++
                val b1 = work[i + 1].toInt() and 0xFF
                val b2 = work[i + 2].toInt() and 0xFF
                val b3 = work[i + 3].toInt() and 0xFF
                val p = ((b1 and 0x1F) shl 8) or b2
                cnt[p]++
                if ((b3 and 0xC0) != 0) scr[p]++
                val pusi = (b1 and 0x40) != 0
                val afc = (b3 shr 4) and 3
                var off = i + 4
                if (afc and 2 != 0) off += 1 + (work[i + 4].toInt() and 0xFF)
                if (afc and 1 != 0 && off < i + 188 && (b3 and 0xC0) == 0) {
                    if (pusi && off + 9 < i + 188 && work[off].toInt() == 0 && work[off + 1].toInt() == 0 && work[off + 2].toInt() == 1) {
                        pesStart[p]++
                        sid[p] = work[off + 3].toInt() and 0xFF
                        if (sync[p].isEmpty()) {
                            val hdr = 9 + (work[off + 8].toInt() and 0xFF)
                            val s = off + hdr
                            if (s + 2 <= i + 188) {
                                val a = work[s].toInt() and 0xFF
                                val b = work[s + 1].toInt() and 0xFF
                                sync[p] = when {
                                    a == 0xFF && (b and 0xF6) == 0xF0 -> "ADTS-AAC"
                                    a == 0xFF && (b and 0xE0) == 0xE0 -> "MPEG-audio"
                                    a == 0x0B && b == 0x77 -> "AC3"
                                    else -> ""
                                }
                            }
                        }
                    }
                }
                i += 188
            }
            carry = len - i
            if (carry > 0) System.arraycopy(work, i, work, 0, carry)
        }
        DvbNative.stopFilter(ffd); DvbNative.closeFd(dfd); DvbNative.closeFd(ffd)
        println("bytes=$total packets=$packets badSync=$badSync readErr=$err")
        // أكثر PIDs ازدحامًا
        val top = (0 until 8192).filter { cnt[it] > 0 }.sortedByDescending { cnt[it] }.take(8)
        for (p in top) {
            println(
                "pid 0x" + Integer.toHexString(p) + " pkts=" + cnt[p] + " scrambled=" + scr[p] +
                    " pes=" + pesStart[p] + (if (pesStart[p] > 0) " sid=0x" + Integer.toHexString(sid[p]) else "") +
                    (if (sync[p].isNotEmpty()) " " + sync[p] else ""),
            )
        }
        // ملخص: PIDs فيها صوت واضح (غير مشفّر)
        val audio = (0 until 8192).filter { sync[it].isNotEmpty() }
        println("PIDs صوت مقروءة: " + (if (audio.isEmpty()) "لا شيء" else audio.joinToString { "0x" + Integer.toHexString(it) + "(" + sync[it] + ")" }))
    }
}
