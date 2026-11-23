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
    fun startProbe(onDone: suspend (String) -> Boolean) {
        if (probing.value) return
        probing.value = true
        probeResult.value = "جارٍ فحص الريسيفر…"
        grantScope.launch {
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
            "cat /proc/net/tcp | grep ' 0A ' | cut -c1-60 | head -12",
            "ls -l /dev/dvb* 2>&1 | head -12",
            "ls /sys/class/dmx 2>&1 | head -12",
            "settings list system | grep -i -E 'delay|sync|avsync' | cut -c1-90 | head -12",
            "settings list global | grep -i -E 'delay|sync|avsync' | cut -c1-90 | head -12",
            "settings list secure | grep -i -E 'delay|avsync' | cut -c1-90 | head -12",
            "getprop | grep -i -E 'delay|avsync|dvb|tvinput' | cut -c1-90 | head -15",
            "dumpsys audio | grep -i -E 'delay|latency' | cut -c1-90 | head -10",
            "pm list packages | grep -i -E 'tv|dvb|ghazal|sat|player|launcher|iptv' | cut -c1-70 | head -25",
            "dumpsys window | grep -E 'mCurrentFocus|mFocusedApp' | head -3",
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
