package app.sadasync.control

import android.content.Context
import android.media.AudioManager
import android.os.SystemClock
import android.view.KeyEvent
import app.sadasync.data.Prefs
import app.sadasync.util.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File
import java.io.OutputStreamWriter
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
            try {
                val c = adb.connect(authWaitMs = authWaitMs)
                status = Status(Method.ADB, "تحكم عبر ADB المحلي")
                return@withContext AdbSender(c)
            } catch (e: Exception) {
                errors += "ADB: ${e.message ?: e.javaClass.simpleName}"
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
