package app.sadasync.capture

import android.annotation.TargetApi
import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.AttributionSource
import android.content.pm.ApplicationInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.os.Build
import android.os.Looper
import android.os.Process
import java.lang.reflect.InvocationTargetException
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * عملية مساعدة تُشغَّل عبر app_process بصلاحية shell (UID 2000).
 * تسجّل مصدر Echo-reference (1997) أي ما يخرج من الريسيفر فعليًا، وتطبع تقريرًا عن مستواه.
 * الأسلوب نفسه الذي يستخدمه ZHE (مصدر تسجيل داخلي لا يتيحه الـSDK العام).
 */
object EchoHelperMain {

    private const val SHELL_PKG = "com.android.shell"

    /** سياق مزيّف بهوية shell حتى يقبل النظام طلب التسجيل. */
    private class FakeCtx(base: Context) : ContextWrapper(base) {
        override fun getPackageName(): String = SHELL_PKG
        override fun getOpPackageName(): String = SHELL_PKG
        @TargetApi(31)
        override fun getAttributionSource(): AttributionSource =
            AttributionSource.Builder(Process.SHELL_UID).setPackageName(SHELL_PKG).build()
        override fun getApplicationContext(): Context = this
    }

    private fun field(cls: Class<*>, name: String) =
        cls.getDeclaredField(name).apply { isAccessible = true }

    private fun makeContext(): Context {
        val atCls = Class.forName("android.app.ActivityThread")
        val ctor = atCls.getDeclaredConstructor().apply { isAccessible = true }
        val at = ctor.newInstance()
        field(atCls, "sCurrentActivityThread").set(null, at)
        field(atCls, "mSystemThread").setBoolean(at, true)
        val abdCls = Class.forName("android.app.ActivityThread\$AppBindData")
        val abd = abdCls.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
        val ai = ApplicationInfo().apply { packageName = SHELL_PKG }
        field(abdCls, "appInfo").set(abd, ai)
        field(atCls, "mBoundApplication").set(at, abd)
        val sys = atCls.getDeclaredMethod("getSystemContext").apply { isAccessible = true }
            .invoke(at) as Context
        val fake = FakeCtx(sys)
        try {
            val app = Application()
            field(ContextWrapper::class.java, "mBase").set(app, fake)
            field(atCls, "mInitialApplication").set(at, app)
        } catch (_: Throwable) {
        }
        return fake
    }

    private fun root(e: Throwable): String {
        var t: Throwable = e
        while (true) {
            val c = (if (t is InvocationTargetException) t.targetException else t.cause) ?: break
            if (c === t) break
            t = c
        }
        val fr = t.stackTrace.firstOrNull()
        return t.javaClass.simpleName + ": " + (t.message ?: "") + (if (fr != null) " @" + fr.className.substringAfterLast('.') + "." + fr.methodName else "")
    }

    private fun build(src: Int, rate: Int, chMask: Int, ctx: Context): AudioRecord {
        val ab = AudioAttributes.Builder()
        ab.javaClass.getMethod("setInternalCapturePreset", Int::class.javaPrimitiveType).invoke(ab, src)
        val attrs = ab.build()
        val fmt = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(rate)
            .setChannelMask(chMask)
            .build()
        val b = AudioRecord.Builder()
        b.javaClass.getMethod("setContext", Context::class.java).invoke(b, ctx)
        b.javaClass.getMethod("setAudioAttributes", AudioAttributes::class.java).invoke(b, attrs)
        b.setAudioFormat(fmt)
        b.setBufferSizeInBytes(rate * 4)
        return b.build()
    }

    private fun levelChar(db: Double): Char {
        val bars = "▁▂▃▄▅▆▇█"
        if (db < -70) return '·'
        val i = (((db + 70) / 60.0) * (bars.length - 1)).toInt().coerceIn(0, bars.length - 1)
        return bars[i]
    }

    private fun attempt(name: String, src: Int, rate: Int, chMask: Int, chCount: Int, ctx: Context, seconds: Int) {
        println("--- $name")
        var rec: AudioRecord? = null
        try {
            rec = build(src, rate, chMask, ctx)
            println("state=${rec.state} rate=${rec.sampleRate} ch=${rec.channelCount}")
            if (rec.state != AudioRecord.STATE_INITIALIZED) {
                println("لم يبدأ التسجيل (غير مهيّأ)")
                return
            }
            rec.startRecording()
            println("recordingState=${rec.recordingState}")
            val chunk = rate / 10 * chCount
            val buf = ShortArray(chunk)
            val dbs = ArrayList<Double>()
            var frames = 0L
            var peak = 0
            var readErr = 0
            val end = System.nanoTime() + seconds * 1_000_000_000L
            while (System.nanoTime() < end) {
                val n = rec.read(buf, 0, chunk)
                if (n <= 0) { readErr = n; if (n < 0) break else continue }
                frames += n / chCount
                var s = 0.0
                for (i in 0 until n) {
                    val v = buf[i].toInt()
                    s += v.toDouble() * v
                    val a = if (v < 0) -v else v
                    if (a > peak) peak = a
                }
                val rms = sqrt(s / n) / 32768.0
                dbs.add(if (rms <= 1e-9) -120.0 else 20 * log10(rms))
            }
            rec.stop()
            val mx = dbs.maxOrNull() ?: -120.0
            val avg = if (dbs.isEmpty()) -120.0 else dbs.average()
            val loud = dbs.count { it > -60.0 }
            println("frames=$frames readErr=$readErr peak=$peak")
            println("avg=%.1f dBFS · max=%.1f dBFS · فوق -60: %d من %d".format(avg, mx, loud, dbs.size))
            println(dbs.map { levelChar(it) }.joinToString(""))
        } catch (e: Throwable) {
            println("فشل: " + root(e))
        } finally {
            try { rec?.release() } catch (_: Throwable) {}
        }
    }

    /** يرسل PCM (16 بت، ستيريو أو مونو) إلى التطبيق عبر مقبس محلي: ترويسة int rate, int ch ثم البيانات. */
    private fun stream(port: Int, seconds: Int, ctx: Context) {
        var rec: AudioRecord? = null
        var chCount = 2
        try {
            val rate = 48000
            rec = try {
                build(1997, rate, AudioFormat.CHANNEL_IN_STEREO, ctx)
            } catch (e: Throwable) {
                chCount = 1
                build(1997, rate, AudioFormat.CHANNEL_IN_MONO, ctx)
            }
            if (rec.state != AudioRecord.STATE_INITIALIZED) { println("غير مهيّأ"); return }
            val sock = java.net.Socket("127.0.0.1", port)
            val out = java.io.DataOutputStream(sock.getOutputStream().buffered(65536))
            out.writeInt(rate); out.writeInt(chCount)
            rec.startRecording()
            val chunk = rate / 20 * chCount // 50 مللي ث
            val sb = ShortArray(chunk)
            val bb = ByteArray(chunk * 2)
            val end = System.nanoTime() + seconds * 1_000_000_000L
            while (System.nanoTime() < end) {
                val n = rec.read(sb, 0, chunk)
                if (n <= 0) { if (n < 0) break else continue }
                for (i in 0 until n) {
                    bb[i * 2] = (sb[i].toInt() and 0xff).toByte()
                    bb[i * 2 + 1] = (sb[i].toInt() shr 8).toByte()
                }
                out.write(bb, 0, n * 2)
            }
            out.flush()
            rec.stop()
            sock.close()
            println("تم: أُرسل $seconds ث")
        } catch (e: Throwable) {
            println("فشل البث: " + root(e))
        } finally {
            try { rec?.release() } catch (_: Throwable) {}
        }
    }

    private fun measure(rec: AudioRecord, ms: Int): Double {
        val buf = ShortArray(9600)
        var sum = 0.0
        var cnt = 0L
        val end = System.nanoTime() + ms * 1_000_000L
        while (System.nanoTime() < end) {
            val n = rec.read(buf, 0, buf.size)
            if (n <= 0) continue
            for (i in 0 until n) { val v = buf[i].toInt(); sum += v.toDouble() * v }
            cnt += n
        }
        val rms = if (cnt == 0L) 0.0 else sqrt(sum / cnt) / 32768.0
        return if (rms <= 1e-9) -120.0 else 20 * log10(rms)
    }

    /**
     * يستكشف واجهة Amlogic TvControlManager (كما يفعل ZHE) ويختبر كتم صوت القناة قبل المزج:
     * يقيس مستوى الخرج قبل/بعد الاستدعاء بالقيمتين 1 و0، ثم يُعيد الصوت للحالة المسموعة.
     */
    private fun tvTest(ctx: Context) {
        println("--- واجهة TvControlManager")
        val paths = listOf(
            "/system/framework/droidlogic-tv.jar", "/vendor/framework/droidlogic-tv.jar",
            "/vendor/framework/droidlogic.tv.software.core.jar", "/system/framework/droidlogic.jar",
        ).filter { java.io.File(it).exists() }
        println("jars: " + paths.joinToString { it.substringAfterLast('/') })
        val cls = try {
            dalvik.system.PathClassLoader(paths.joinToString(":"), ClassLoader.getSystemClassLoader())
                .loadClass("com.droidlogic.app.tv.TvControlManager")
        } catch (e: Throwable) { println("لا يوجد الصنف: " + root(e)); return }
        val re = Regex("(?i)mute|lag|delay|pregain|volume|avsync")
        val ms = cls.methods.filter { re.containsMatchIn(it.name) }.sortedBy { it.name }
        println("طرق ذات صلة (${ms.size}):")
        for (m in ms.take(36)) {
            println(m.name + "(" + m.parameterTypes.joinToString(",") { it.simpleName } + ")→" + m.returnType.simpleName)
        }
        val target = cls.methods.firstOrNull { it.name.equals("SetAmAudioPreMute", true) }
        if (target == null) { println("لا توجد SetAmAudioPreMute"); return }
        println("--- اختبار الكتم: " + target.name + "(" + target.parameterTypes.joinToString(",") { it.simpleName } + ")")
        val tv: Any? = try {
            val gi = cls.methods.firstOrNull { it.name == "getInstance" && it.parameterTypes.isEmpty() }
            gi?.invoke(null)
        } catch (e: Throwable) { println("getInstance فشل: " + root(e)); null }
        if (tv == null && !java.lang.reflect.Modifier.isStatic(target.modifiers)) { println("لا نسخة من TvControlManager"); return }
        fun call(v: Int): String = try {
            val pt = target.parameterTypes
            val arg: Any = if (pt.size == 1 && pt[0] == java.lang.Boolean.TYPE) (v == 1) else v
            "ret=" + target.invoke(tv, arg)
        } catch (e: Throwable) { "فشل: " + root(e) }
        val rec = try { build(1997, 48000, AudioFormat.CHANNEL_IN_STEREO, ctx) } catch (e: Throwable) { println("تعذّر Echo: " + root(e)); return }
        var restore = 0
        try {
            rec.startRecording()
            measure(rec, 800)
            val base = measure(rec, 2000)
            println("قبل: %.1f dBFS".format(base))
            val r1 = call(1); measure(rec, 800); val l1 = measure(rec, 2000)
            println("بعد (1): %.1f dBFS · %s".format(l1, r1))
            val r0 = call(0); measure(rec, 800); val l0 = measure(rec, 2000)
            println("بعد (0): %.1f dBFS · %s".format(l0, r0))
            restore = if (Math.abs(l1 - base) < Math.abs(l0 - base)) 1 else 0
            val muteVal = 1 - restore
            println("الكتم يتحقق بالقيمة $muteVal ؛ هبوط ${"%.1f".format(base - minOf(l1, l0))} dB")
        } catch (e: Throwable) {
            println("فشل الاختبار: " + root(e))
        } finally {
            println("إرجاع الصوت: " + call(restore))
            try { rec.stop(); rec.release() } catch (_: Throwable) {}
        }
    }

    @JvmStatic
    fun main(args: Array<String>) {
        try { Looper.prepareMainLooper() } catch (_: Throwable) {}
        println("uid=${Process.myUid()} sdk=${Build.VERSION.SDK_INT}")
        val ctx = try {
            makeContext()
        } catch (e: Throwable) {
            println("تعذّر إنشاء السياق: " + root(e))
            System.out.flush(); System.exit(0); return
        }
        if (args.isNotEmpty() && args[0] == "tvtest") {
            tvTest(ctx)
            System.out.flush(); System.exit(0); return
        }
        if (args.isNotEmpty() && args[0] == "stream") {
            stream(args[1].toInt(), args.getOrNull(2)?.toIntOrNull() ?: 10, ctx)
            System.out.flush(); System.exit(0); return
        }
        val secs = 3
        attempt("Echo 1997 · 48k ستيريو", 1997, 48000, AudioFormat.CHANNEL_IN_STEREO, 2, ctx, secs)
        attempt("Echo 1997 · 48k مونو", 1997, 48000, AudioFormat.CHANNEL_IN_MONO, 1, ctx, secs)
        attempt("Echo 1997 · 44.1k ستيريو", 1997, 44100, AudioFormat.CHANNEL_IN_STEREO, 2, ctx, secs)
        attempt("Remote submix 8 · 48k ستيريو", 8, 48000, AudioFormat.CHANNEL_IN_STEREO, 2, ctx, secs)
        System.out.flush()
        System.exit(0)
    }
}
