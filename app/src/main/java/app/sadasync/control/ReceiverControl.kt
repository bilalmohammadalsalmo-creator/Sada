package app.sadasync.control

import android.content.Context
import android.media.AudioManager
import android.os.SystemClock
import android.view.KeyEvent
import app.sadasync.data.Prefs
import app.sadasync.util.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * يتحكم بتشغيل القناة على الريسيفر نفسه (إيقاف مؤقت ثم استئناف) ليتأخر الفيديو مع صوته.
 * الطرق، بالترتيب في الوضع التلقائي:
 *  1) ROOT: صلاحية su على الجهاز (أسرع وأدق).
 *  2) ADB: اتصال محلي بمنفذ 5555 (يتطلب تفعيل "تصحيح ADB عبر الشبكة" مرة واحدة وقبول المفتاح).
 *  3) MEDIA_KEY: إرسال مفتاح وسائط عام للنظام؛ لا يوجد ما يؤكد أن تطبيق القنوات يستجيب له، لذلك نتحقق
 *     دائمًا من أن صوت القناة سكت فعلًا أثناء الإيقاف.
 */
class ReceiverControl(private val ctx: Context, private val prefs: Prefs) {

    enum class Method(val label: String) {
        ROOT("root"),
        ADB("ADB محلي"),
        MEDIA_KEY("مفتاح وسائط عام"),
    }

    data class Status(val method: Method?, val detail: String)

    interface KeySender : Closeable {
        val method: Method
        fun key(code: Int)
    }

    /** نتيجة إيقاف مؤقت: وقتا إرسال الأمرين على ساعة nanoTime. */
    class PauseResult(val ok: Boolean, val startNs: Long, val resumeNs: Long, val error: String = "")

    private val adb = AdbClient(File(ctx.filesDir, "adbkey"))

    @Volatile
    var status: Status = Status(null, "لم يُفحص بعد")
        private set

    // ------------------------------------------------------------ المرسلون

    private class RootSender : KeySender {
        override val method = Method.ROOT
        private val proc: Process = Runtime.getRuntime().exec("su")
        private val w = OutputStreamWriter(proc.outputStream)

        init {
            w.write("id\n")
            w.flush()
        }

        override fun key(code: Int) {
            w.write("input keyevent $code\n")
            w.flush()
        }

        override fun close() {
            try {
                w.write("exit\n")
                w.flush()
            } catch (_: Exception) {
            }
            proc.destroy()
        }
    }

    private class AdbSender(private val conn: AdbClient.Connection) : KeySender {
        override val method = Method.ADB
        override fun key(code: Int) {
            conn.shell("input keyevent $code", wait = false)
        }

        override fun close() = conn.close()
    }

    private inner class MediaSender : KeySender {
        override val method = Method.MEDIA_KEY
        private val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        override fun key(code: Int) {
            val t = SystemClock.uptimeMillis()
            am.dispatchMediaKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0))
            am.dispatchMediaKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_UP, code, 0))
        }

        override fun close() = Unit
    }

    private fun rootAvailable(): Boolean = try {
        val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
        val ok = p.waitFor(3, TimeUnit.SECONDS) && p.exitValue() == 0 &&
            p.inputStream.bufferedReader().readText().contains("uid=0")
        if (!ok) p.destroy()
        ok
    } catch (e: Exception) {
        false
    }

    /** يقرأ خاصية نظام (reflection ثم getprop). يعيد نصًا فارغًا إن تعذّر. */
    private fun sysProp(key: String): String {
        try {
            val c = Class.forName("android.os.SystemProperties")
            val v = c.getMethod("get", String::class.java, String::class.java).invoke(null, key, "") as String
            if (v.isNotEmpty()) return v
        } catch (_: Throwable) {
        }
        return try {
            val p = Runtime.getRuntime().exec(arrayOf("getprop", key))
            val t = p.inputStream.bufferedReader().readText().trim()
            p.waitFor(1, TimeUnit.SECONDS)
            t
        } catch (_: Throwable) {
            ""
        }
    }

    /** المنافذ المرشحة لـ ADB المحلي: ما يعلنه النظام أولًا ثم 5555. */
    fun adbPorts(): List<Int> {
        val l = mutableListOf<Int>()
        for (k in listOf("service.adb.tcp.port", "persist.adb.tcp.port")) {
            sysProp(k).toIntOrNull()?.let { if (it > 0) l += it }
        }
        if (prefs.adbPortFound > 0) l.add(0, prefs.adbPortFound)
        l += 5555
        return l.distinct()
    }


    private val grantScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)
    val grantResult = kotlinx.coroutines.flow.MutableStateFlow("")
    val granting = kotlinx.coroutines.flow.MutableStateFlow(false)

    /** يبدأ طلب الوصول في الخلفية (لا يتأثر بتبديل التبويبات). النتيجة في [grantResult]. */
    fun startGrant() {
        if (granting.value) return
        granting.value = true
        grantResult.value = "جارٍ البحث عن منفذ الريسيفر… إن ظهرت نافذة «السماح بالتصحيح؟» على الشاشة فاختر «السماح دائمًا» ثم «موافق» (قد يستغرق الفحص حتى دقيقة ونصف)."
        grantScope.launch {
            grantResult.value = try { discoverAndGrant() } catch (e: Throwable) { "خطأ: ${e.message}" }
            granting.value = false
        }
    }


    val probeResult = kotlinx.coroutines.flow.MutableStateFlow("")
    val probing = kotlinx.coroutines.flow.MutableStateFlow(false)

    /** فحص للقراءة فقط عبر ADB: معلومات الجهاز والمنافذ والإعدادات المتعلقة بتأخير الصورة والتونر. */
    fun startProbe(initialDelayMs: Long = 0, onDone: suspend (String) -> Boolean) {
        if (probing.value) return
        probing.value = true
        probeResult.value = "جارٍ فحص الريسيفر…"
        grantScope.launch {
            if (initialDelayMs > 0) delay(initialDelayMs)
            val text = try { runProbe() } catch (e: Throwable) { "خطأ: ${e.message}" }
            probeResult.value = text
            val sent = try { onDone(text) } catch (_: Throwable) { false }
            // نحفظ نسخة في ذاكرة التطبيق أيضًا
            try { File(ctx.filesDir, "probe.txt").writeText(text) } catch (_: Throwable) {}
            probeResult.value = (if (sent) "✔ أُرسلت النتيجة إلى الدعم\n\n" else "✘ تعذّر الإرسال (تيليجرام محجوب؟ شغّل VPN ثم أعد الفحص). النتيجة كاملة أدناه:\n\n") + text
            probing.value = false
        }
    }

    private fun runProbe(): String {
        val port = prefs.adbPortFound
        if (port <= 0) return "نفّذ «طلب وصول الريسيفر» أولًا"
        val cmds = listOf(
            "ls /dev/dvb0.* | grep -v 'ca[0-9]' | head -30",
            "cat /sys/class/dmx/dump_av_level 2>&1 | cut -c1-100 | head -20",
            "cat /sys/class/dmx/dmx_source 2>&1 | cut -c1-100 | head -8",
            "cat /sys/class/dmx/cache_status 2>&1 | cut -c1-100 | head -8",
            "dumpsys media.audio_flinger | grep -i -E 'patch|tuner|dtv|hw_av|source' | cut -c1-110 | head -20",
            "dumpsys audio | grep -i -E 'tuner|dtv|patch|submix' | cut -c1-110 | head -12",
            "dumpsys media.audio_flinger | grep -E 'Output thread|Standby|Sample rate|Format' | cut -c1-100 | head -16",
            "ps -A | grep -i -E 'dvb|tuner|tvserver|tosmart|audio' | cut -c1-100 | head -15",
            "dumpsys window | grep -E 'mCurrentFocus' | cut -c1-120 | head -2",
        )
        val sb = StringBuilder()
        adb.connect(host = prefs.adbHost, port = port, authWaitMs = 15_000).use { c ->
            for (cmd in cmds) {
                sb.appendLine("\$ $cmd")
                sb.appendLine(try { c.shell(cmd).trim().ifEmpty { "(فارغ)" } } catch (e: Exception) { "خطأ: ${e.message}" })
                sb.appendLine()
            }
        }
        return sb.toString()
    }


    /** تجربة: تفتح قائمة تأخير الصورة بأزرار الريموت (Audio مرتين ثم الأحمر)، تحرّك التأخير 3 خطوات ثم تعيده وتغلق. */
    fun startDelayTest(initialDelayMs: Long = 0) {
        if (probing.value) return
        probing.value = true
        probeResult.value = "جارٍ التجربة… راقب الشاشة: يجب أن تظهر قائمة الصوت ثم نافذة تأخير الصورة (حوالي 10 ثوانٍ)."
        grantScope.launch {
            if (initialDelayMs > 0) delay(initialDelayMs)
            val text = try { runDelayTest() } catch (e: Throwable) { "خطأ: ${e.message}" }
            try { File(ctx.filesDir, "delaytest.txt").writeText(text) } catch (_: Throwable) {}
            probeResult.value = text
            probing.value = false
        }
    }

    private suspend fun runDelayTest(): String {
        val port = prefs.adbPortFound
        if (port <= 0) return "نفّذ «طلب وصول الريسيفر» أولًا"
        val sb = StringBuilder()
        adb.connect(host = prefs.adbHost, port = port, authWaitMs = 15_000).use { c ->
            fun sh(cmd: String) = try { c.shell(cmd).trim().ifEmpty { "(فارغ)" } } catch (e: Exception) { "خطأ: ${e.message}" }
            fun key(code: Int) { sh("input keyevent $code") }
            val focusCmd = "dumpsys window | grep -E 'mCurrentFocus' | cut -c1-120 | head -2"
            sh("logcat -c")
            sb.appendLine("قبل الضغط\n" + sh(focusCmd) + "\n")
            key(222); delay(500); key(222); delay(1200)
            sb.appendLine("بعد Audio مرتين (222)\n" + sh(focusCmd) + "\n")
            key(183); delay(1200)
            sb.appendLine("بعد الأحمر (183)\n" + sh(focusCmd) + "\n" + sh("dumpsys activity top | grep -E '^ *ACTIVITY' | cut -c1-120 | head -3") + "\n")
            repeat(3) { key(22); delay(500) }
            sb.appendLine("بعد 3 ضغطات يمين\n" + sh(focusCmd) + "\n")
            sb.appendLine("سجل النظام (delay/avsync)\n" + sh("logcat -d -t 400 | grep -i -E 'delay|avsync' | cut -c1-120 | tail -12") + "\n")
            repeat(3) { key(21); delay(500) }
            delay(400)
            key(4); delay(400); key(4)
            sb.appendLine("تمت إعادة التأخير (3 يسار) وإغلاق القائمة (رجوع)")
        }
        return sb.toString()
    }


    class VideoDelayResult(val ok: Boolean, val appliedMs: Int, val error: String = "")

    private val vdLock = kotlinx.coroutines.sync.Mutex()

    /**
     * يضبط تأخير الصورة في قائمة الريسيفر الأصلية إلى [totalMs] (يُقرَّب إلى مضاعف 500 م.ث لأعلى):
     * Audio مرتين ← الأحمر «Delay» ← يسار ×N للتصفير ← يمين بعدد الخطوات ← رجوع ×2.
     */
    suspend fun setVideoDelay(totalMs: Int): VideoDelayResult = vdLock.withLock {
        withContext(Dispatchers.IO) {
            val port = prefs.adbPortFound
            if (port <= 0) return@withContext VideoDelayResult(false, 0, "نفّذ «طلب وصول الريسيفر» أولًا")
            val steps = ((totalMs + 499) / 500).coerceIn(0, 120) // حتى 60 ث
            try {
                adb.connect(host = prefs.adbHost, port = port, authWaitMs = 8_000).use { c ->
                    c.setTimeout(30_000) // أوامر طويلة: حتى 120 ضغطة
                    fun keys(vararg k: Int) { c.shell("input keyevent " + k.joinToString(" ")) }
                    keys(222); delay(450); keys(222); delay(1100)
                    keys(183); delay(900)
                    keys(*IntArray(125) { 21 }); delay(500)     // تصفير (حتى 60 ث)
                    if (steps > 0) keys(*IntArray(steps) { 22 }) // خطوات 0.5 ث
                    delay(900 + steps * 15L)
                    keys(23); delay(900)   // موافق: تثبيت الفارق قبل الخروج
                    keys(4)                // خروج من القائمة إن بقيت مفتوحة
                }
                prefs.videoDelayMs = steps * 500
                status = Status(Method.ADB, "تحكم عبر ADB المحلي (منفذ $port)")
                VideoDelayResult(true, steps * 500)
            } catch (e: Exception) {
                VideoDelayResult(false, 0, e.message ?: e.javaClass.simpleName)
            }
        }
    }


    /** اختبار التقاط صوت الريسيفر عبر مصدر Echo (مثل ZHE). اترك القناة تعمل بدون تشغيل التعليق. */
    fun startEchoTest(initialDelayMs: Long = 0) {
        if (probing.value) return
        probing.value = true
        probeResult.value = "جارٍ اختبار التقاط الصوت… دع القناة تعمل (حوالي 20 ثانية)."
        grantScope.launch {
            if (initialDelayMs > 0) delay(initialDelayMs)
            val text = try { runEchoTest() } catch (e: Throwable) { "خطأ: ${e.message}" }
            try { File(ctx.filesDir, "echotest.txt").writeText(text) } catch (_: Throwable) {}
            probeResult.value = text
            probing.value = false
        }
    }

    /** استكشاف واجهة Amlogic واختبار كتم صوت القناة بقياس مستوى الخرج. */
    fun startTvTest(initialDelayMs: Long = 0) {
        if (probing.value) return
        probing.value = true
        probeResult.value = "جارٍ اختبار كتم صوت القناة… دع القناة تعمل بصوت مسموع (حوالي 20 ثانية). قد ينقطع صوتها لحظات."
        grantScope.launch {
            if (initialDelayMs > 0) delay(initialDelayMs)
            val text = try { runEchoTest("tvtest") } catch (e: Throwable) { "خطأ: ${e.message}" }
            try { File(ctx.filesDir, "tvtest.txt").writeText(text) } catch (_: Throwable) {}
            probeResult.value = text
            probing.value = false
        }
    }

    private fun runEchoTest(mode: String = "probe"): String {
        val port = prefs.adbPortFound
        if (port <= 0) return "نفّذ «طلب وصول الريسيفر» أولًا"
        val proc = if (android.os.Process.is64Bit()) "app_process64" else "app_process32"
        val sb = StringBuilder()
        adb.connect(host = prefs.adbHost, port = port, authWaitMs = 15_000).use { c ->
            c.setTimeout(60_000)
            val out = c.shell("CLASSPATH=${ctx.applicationInfo.sourceDir} $proc /system/bin app.sadasync.capture.EchoHelperMain $mode 2>&1")
            sb.appendLine(out.trim())
        }
        return sb.toString().replace(Regex("\n(--- )"), "\n\n$1")
    }

    /** يلتقط صوت القناة (Echo) بضع ثوانٍ، يعرض مستواه، ثم يشغّله مكبّرًا لتسمع ما التقطه. */
    fun startEchoListen(initialDelayMs: Long = 0, seconds: Int = 10) {
        if (probing.value) return
        probing.value = true
        probeResult.value = "جارٍ التقاط صوت القناة $seconds ثوانٍ… اترك القناة تعمل ولا تشغّل التعليق."
        grantScope.launch {
            if (initialDelayMs > 0) delay(initialDelayMs)
            val text = try { runEchoListen(seconds) } catch (e: Throwable) { "خطأ: ${e.javaClass.simpleName} ${e.message}" }
            try { File(ctx.filesDir, "echolisten.txt").writeText(text) } catch (_: Throwable) {}
            probeResult.value = text
            probing.value = false
        }
    }

    private suspend fun runEchoListen(seconds: Int): String = withContext(Dispatchers.IO) {
        val port = prefs.adbPortFound
        if (port <= 0) return@withContext "نفّذ «طلب وصول الريسيفر» أولًا"
        val proc = if (android.os.Process.is64Bit()) "app_process64" else "app_process32"
        val ss = java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))
        ss.soTimeout = 25_000
        val helperOut = StringBuilder()
        val t = Thread {
            try {
                adb.connect(host = prefs.adbHost, port = port, authWaitMs = 15_000).use { c ->
                    c.setTimeout((seconds + 40) * 1000)
                    helperOut.append(c.shell("CLASSPATH=${ctx.applicationInfo.sourceDir} $proc /system/bin app.sadasync.capture.EchoHelperMain stream ${ss.localPort} $seconds 2>&1"))
                }
            } catch (e: Throwable) { helperOut.append("adb: ${e.message}") }
        }
        t.start()
        val sock = try { ss.accept() } catch (e: Throwable) {
            ss.close(); t.join(5000)
            return@withContext "لم يتصل المساعد بالتطبيق.\n" + helperOut.toString().trim()
        }
        val din = java.io.DataInputStream(sock.getInputStream().buffered(65536))
        val rate = din.readInt(); val ch = din.readInt()
        val bos = java.io.ByteArrayOutputStream()
        val buf = ByteArray(16384)
        while (true) { val n = din.read(buf); if (n < 0) break; bos.write(buf, 0, n) }
        sock.close(); ss.close(); t.join(8000)
        val raw = bos.toByteArray()
        val n = raw.size / 2
        val pcm = ShortArray(n) { ((raw[it * 2].toInt() and 0xff) or (raw[it * 2 + 1].toInt() shl 8)).toShort() }
        // مستوى كل 100 مللي ث
        val blk = rate / 10 * ch
        val dbs = ArrayList<Double>()
        var peak = 0
        var i = 0
        while (i + blk <= n) {
            var sum = 0.0
            for (k in i until i + blk) { val v = pcm[k].toInt(); sum += v.toDouble() * v; val a = if (v < 0) -v else v; if (a > peak) peak = a }
            val rms = Math.sqrt(sum / blk) / 32768.0
            dbs.add(if (rms <= 1e-9) -120.0 else 20 * Math.log10(rms))
            i += blk
        }
        val bars = "▁▂▃▄▅▆▇█"
        val line = dbs.map { d -> if (d < -70) '·' else bars[(((d + 70) / 40.0) * (bars.length - 1)).toInt().coerceIn(0, bars.length - 1)] }.joinToString("")
        val avg = if (dbs.isEmpty()) -120.0 else dbs.average()
        val sd = if (dbs.size < 2) 0.0 else Math.sqrt(dbs.sumOf { (it - avg) * (it - avg) } / dbs.size)
        val gain = if (peak <= 0) 1.0 else minOf(0.7 * 32767 / peak, 100.0)
        val sb = StringBuilder()
        sb.appendLine("rate=$rate ch=$ch عينات=${n / ch} peak=$peak")
        sb.appendLine("avg=%.1f dBFS · تباين=%.1f dB · تكبير ×%.0f".format(avg, sd, gain))
        sb.appendLine(line)
        sb.appendLine(helperOut.toString().trim().takeLast(160))
        if (peak <= 0 || n == 0) { sb.appendLine("لا صوت (صمت تام)"); return@withContext sb.toString() }
        probeResult.value = sb.toString() + "\nجارٍ تشغيل ما التقطه الآن (مكبّرًا)… اسمع: هل هو صوت القناة؟"
        try {
            val out = ShortArray(n) { (pcm[it] * gain).toInt().coerceIn(-32768, 32767).toShort() }
            val mask = if (ch == 2) android.media.AudioFormat.CHANNEL_OUT_STEREO else android.media.AudioFormat.CHANNEL_OUT_MONO
            val minBuf = android.media.AudioTrack.getMinBufferSize(rate, mask, android.media.AudioFormat.ENCODING_PCM_16BIT)
            val track = android.media.AudioTrack.Builder()
                .setAudioAttributes(android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setAudioFormat(android.media.AudioFormat.Builder()
                    .setEncoding(android.media.AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(rate).setChannelMask(mask).build())
                .setBufferSizeInBytes(maxOf(minBuf, 65536))
                .setTransferMode(android.media.AudioTrack.MODE_STREAM)
                .build()
            track.play()
            track.write(out, 0, n)
            delay(1200)
            track.stop(); track.release()
            sb.appendLine("تم تشغيل المقطع.")
        } catch (e: Throwable) { sb.appendLine("تعذّر التشغيل: ${e.message}") }
        sb.toString()
    }

    /** اختبار قراءة صوت القناة من التونر: يشغّل العملية المساعدة بصلاحية shell ويعرض تقريرها. */
    fun startDvbTest(initialDelayMs: Long = 0) {
        if (probing.value) return
        probing.value = true
        probeResult.value = "جارٍ اختبار التونر… دع القناة تعمل (حوالي 20 ثانية)."
        grantScope.launch {
            if (initialDelayMs > 0) delay(initialDelayMs)
            val text = try { runDvbTest() } catch (e: Throwable) { "خطأ: ${e.message}" }
            try { File(ctx.filesDir, "dvbtest.txt").writeText(text) } catch (_: Throwable) {}
            probeResult.value = text
            probing.value = false
        }
    }

    private fun runDvbTest(): String {
        val port = prefs.adbPortFound
        if (port <= 0) return "نفّذ «طلب وصول الريسيفر» أولًا"
        val ai = ctx.applicationInfo
        val is64 = android.os.Process.is64Bit()
        val proc = if (is64) "app_process64" else "app_process32"
        val lib = ai.nativeLibraryDir + "/libsada_dvb.so"
        val sb = StringBuilder()
        adb.connect(host = prefs.adbHost, port = port, authWaitMs = 15_000).use { c ->
            c.setTimeout(40_000)
            val lvl = c.shell("cat /sys/class/dmx/dump_av_level 2>&1")
            val pid = Regex("audio info:?\\s*pid:0x([0-9a-fA-F]+)").find(lvl)?.groupValues?.get(1)?.toIntOrNull(16) ?: -1
            sb.appendLine("ABI: " + (if (is64) "64-bit" else "32-bit") + " · مكتبة: " + c.shell("ls -l $lib 2>&1").trim().take(80))
            sb.appendLine("PID الصوت من dump_av_level: " + (if (pid > 0) "0x" + Integer.toHexString(pid) else "غير معروف"))
            sb.appendLine()
            val out = c.shell("CLASSPATH=${ai.sourceDir} $proc /system/bin app.sadasync.dvb.DvbHelperMain probe $lib $pid 2>&1")
            sb.appendLine(out.trim())
        }
        return sb.toString().replace(Regex("\n(--- )"), "\n\n$1")
    }

    /** عناوين الجهاز نفسه: المحلي + عناوين الشبكة (IPv4) لأن بعض الأجهزة تفتح ADB على عنوان الشبكة فقط. */
    private fun selfHosts(): List<String> {
        val l = mutableListOf("127.0.0.1")
        try {
            for (ni in NetworkInterface.getNetworkInterfaces()) {
                if (!ni.isUp) continue
                for (a in ni.inetAddresses) {
                    val h = a.hostAddress ?: continue
                    if (!a.isLoopbackAddress && !h.contains(':')) l += h
                }
            }
        } catch (_: Throwable) {
        }
        return l.distinct()
    }

    /** يفحص المنافذ المفتوحة على [host] ضمن النطاقات الشائعة ثم النطاق الواسع، بتوازٍ. */
    private fun openPorts(host: String): List<Int> {
        val found = ConcurrentLinkedQueue<Int>()
        val pool = Executors.newFixedThreadPool(256)
        val ranges = listOf(5550..5600, 1024..65535)
        val seen = HashSet<Int>()
        for (r in ranges) for (port in r) {
            if (!seen.add(port)) continue
            pool.execute {
                try {
                    Socket().use { it.connect(InetSocketAddress(host, port), 250); found += port }
                } catch (_: Throwable) {
                }
            }
        }
        pool.shutdown()
        pool.awaitTermination(90, TimeUnit.SECONDS)
        return found.toList().sorted()
    }

    /**
     * طلب وصول الريسيفر (مثل ZHE): يكتشف منفذ ADB تلقائيًا بدون تحديد منفذ، ويعرض نافذة الموافقة على الريسيفر.
     * يعيد تقريرًا نصيًا بالعربي للعرض.
     */
    suspend fun discoverAndGrant(): String = withContext(Dispatchers.IO) {
        val sb = StringBuilder()
        val tried = HashSet<String>()
        // 1) المرشحون المعروفون أولًا
        val hosts = selfHosts()
        sb.appendLine("العناوين: ${hosts.joinToString()}")
        val candidates = mutableListOf<Pair<String, Int>>()
        for (p in adbPorts()) candidates += "127.0.0.1" to p
        for (h in hosts) for (p in listOf(5555, 5554)) candidates += h to p
        var ok: Pair<String, Int>? = null
        suspend fun tryOne(h: String, p: Int): Boolean {
            if (!tried.add("$h:$p")) return false
            return try {
                adb.connect(host = h, port = p, connectTimeoutMs = 1500, authWaitMs = 60_000).close()
                ok = h to p
                true
            } catch (e: Exception) {
                sb.appendLine("$h:$p ← ${e.message?.take(60) ?: e.javaClass.simpleName}")
                false
            }
        }
        for ((h, p) in candidates) if (tryOne(h, p)) break
        // 2) فحص واسع إن لم ينجح
        if (ok == null) {
            sb.appendLine("— فحص كل المنافذ المفتوحة —")
            for (h in hosts) {
                val open = openPorts(h)
                sb.appendLine("$h منافذ مفتوحة: ${if (open.isEmpty()) "لا شيء" else open.joinToString()}")
                for (p in open) if (tryOne(h, p)) break
                if (ok != null) break
            }
        }
        val r = ok
        if (r != null) {
            prefs.adbHost = r.first
            prefs.adbPortFound = r.second
            sb.insert(0, "✔ تم الوصول: ${r.first}:${r.second}\n")
        } else {
            sb.insert(0, "✘ لم يُعثر على منفذ ADB يقبل الاتصال\n")
        }
        sb.toString()
    }

    /** نص تشخيصي عن حالة ADB في النظام (للعرض في شاشة التشخيص). */
    fun adbInfo(): String = listOf(
        "service.adb.tcp.port", "persist.adb.tcp.port", "service.adb.tls.port",
        "init.svc.adbd", "ro.adb.secure", "ro.debuggable", "persist.sys.usb.config", "sys.usb.config",
    ).joinToString("\n") { "$it = ${sysProp(it).ifEmpty { "—" }}" }

    /** يفتح مرسلًا حسب الإعداد. [authWaitMs] مهلة قبول مفتاح ADB على الشاشة (طويلة فقط في الاختبار اليدوي). */
    suspend fun open(authWaitMs: Int = 8_000): KeySender = withContext(Dispatchers.IO) {
        val mode = prefs.controlMode
        val errors = mutableListOf<String>()
        if (mode == Prefs.CONTROL_AUTO || mode == Prefs.CONTROL_ROOT) {
            try {
                if (rootAvailable()) return@withContext RootSender().also {
                    status = Status(Method.ROOT, "تحكم عبر root")
                }
                errors += "root غير متاح"
            } catch (e: Exception) {
                errors += "root: ${e.message}"
            }
        }
        if (mode == Prefs.CONTROL_AUTO || mode == Prefs.CONTROL_ADB) {
            for (port in adbPorts()) {
                try {
                    val c = adb.connect(host = if (port == prefs.adbPortFound) prefs.adbHost else "127.0.0.1", port = port, authWaitMs = authWaitMs)
                    status = Status(Method.ADB, "تحكم عبر ADB المحلي (منفذ $port)")
                    return@withContext AdbSender(c)
                } catch (e: Exception) {
                    errors += "ADB $port: ${e.message?.take(70) ?: e.javaClass.simpleName}"
                }
            }
        }
        if (mode == Prefs.CONTROL_AUTO || mode == Prefs.CONTROL_MEDIA) {
            status = Status(
                Method.MEDIA_KEY,
                "مفتاح وسائط عام (غير مؤكد)" + if (errors.isNotEmpty()) " — " + errors.joinToString("، ") else "",
            )
            return@withContext MediaSender()
        }
        status = Status(null, errors.joinToString("، ").ifEmpty { "لا توجد طريقة تحكم" })
        throw IllegalStateException(status.detail)
    }

    /**
     * يوقف القناة مؤقتًا [ms] مللي ثانية ثم يستأنفها. الوقت يُحسب من لحظة إرسال أمر الإيقاف إلى لحظة
     * إرسال أمر الاستئناف، فتتلاشى فروق زمن تنفيذ الأمرين تقريبًا.
     */
    suspend fun pauseFor(ms: Long): PauseResult {
        val sender = try {
            open()
        } catch (e: Exception) {
            return PauseResult(false, 0, 0, e.message ?: "تعذّر الاتصال بالريسيفر")
        }
        var started = 0L
        return try {
            withContext(Dispatchers.IO) {
                started = System.nanoTime()
                sender.key(prefs.pauseKey)
            }
            val target = started + ms * 1_000_000L
            val wait = (target - System.nanoTime()) / 1_000_000L - 15
            if (wait > 0) delay(wait)
            while (System.nanoTime() < target) { /* انتظار نشط قصير لدقة أعلى */ }
            val resumed = withContext(Dispatchers.IO) {
                val t = System.nanoTime()
                sender.key(prefs.resumeKey)
                t
            }
            PauseResult(true, started, resumed)
        } catch (e: Exception) {
            AppLog.d("pauseFor failed: $e")
            // لا نترك القناة موقوفة إن فشل الاستئناف بعد نجاح الإيقاف
            if (started != 0L) runCatching { sender.key(prefs.resumeKey) }
            PauseResult(false, started, System.nanoTime(), e.message ?: "فشل إرسال الأمر")
        } finally {
            sender.close()
        }
    }

    /** اختبار يدوي من شاشة التشخيص: يوقف 5 ثوانٍ ثم يستأنف، ويعيد وصفًا للنتيجة. */
    suspend fun testPulse(): String {
        val s = try {
            open(authWaitMs = 40_000)
        } catch (e: Exception) {
            return "فشل الاتصال: ${e.message}"
        }
        s.close()
        val r = pauseFor(5_000)
        return if (r.ok) "أُرسل أمر الإيقاف ثم الاستئناف عبر ${status.method?.label}. هل تجمّدت الصورة 5 ثوانٍ؟"
        else "فشل: ${r.error}"
    }
}
