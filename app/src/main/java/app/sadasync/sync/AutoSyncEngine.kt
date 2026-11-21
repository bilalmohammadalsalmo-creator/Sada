package app.sadasync.sync

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Process
import androidx.core.content.ContextCompat
import app.sadasync.control.ReceiverControl
import app.sadasync.data.Prefs
import app.sadasync.player.PlayerController
import app.sadasync.util.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.sqrt

data class EngineUi(
    val enabled: Boolean = false,
    /** حالة التقاط صوت القناة (نص للعرض). */
    val capture: String = "متوقف",
    val capturing: Boolean = false,
    /** حالة التحكم بالريسيفر (نص للعرض). */
    val control: String = "لم يُفحص بعد",
    val brain: SyncBrain.Info = SyncBrain.Info(text = "متوقف"),
    val lastAction: String = "",
    val actionsCount: Int = 0,
)

/**
 * المزامنة التلقائية المستمرة:
 *  - تلتقط صوت القناة باستمرار (AudioPlaybackCapture بعد إذن المستخدم، أو الميكروفون عند الاختيار).
 *  - [SyncBrain] يقارنه بصوتك الخارجي كل بضع ثوانٍ ويقرر: تأخير الصورة (إيقاف مؤقت للريسيفر) أو تأخير صوتك.
 *  - عند الانقطاع أو تغيير القناة يعيد القياس والمطابقة بنفسه بدون أي تدخل.
 */
class AutoSyncEngine(
    private val ctx: Context,
    private val pc: PlayerController,
    private val prefs: Prefs,
) {
    companion object {
        const val RATE = 16_000
        private const val TICK_MS = 500L
        private const val STEP_EVERY_TICKS = 2
    }

    val chanEnv = EnvelopeStore(180)
    private val brain = SyncBrain(SyncBrain.Config(calibMs = prefs.calibMs))
    val control = ReceiverControl(ctx, prefs)

    private val _ui = MutableStateFlow(EngineUi(enabled = prefs.autoSyncEnabled))
    val ui: StateFlow<EngineUi> = _ui

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    private var job: Job? = null

    @Volatile
    private var captureJob: Job? = null

    private var actionsCount = 0
    private var lastAction = ""

    val running: Boolean get() = job?.isActive == true

    init {
        // تبدأ المزامنة مع أول تشغيل لصوتك (إن كانت مفعّلة)، وكل اضطراب لاحق يعيد القياس
        pc.onDisturbed = {
            if (!running && prefs.autoSyncEnabled && pc.ui.value.playing) start() else onAudioDisturbed()
        }
    }

    // ------------------------------------------------------------ تحكم

    /** يشغّل المزامنة المستمرة (ويحفظ التفضيل). آمن للاستدعاء المتكرر. */
    fun start() {
        prefs.autoSyncEnabled = true
        _ui.value = _ui.value.copy(enabled = true)
        if (running) return
        job = scope.launch {
            try {
                run()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.d("autosync crashed: $e")
                publish(captureText = "خطأ: ${e.message}")
            }
        }
    }

    /** يوقف المزامنة (ويحفظ التفضيل). */
    fun stop() {
        prefs.autoSyncEnabled = false
        cancel()
        _ui.value = _ui.value.copy(enabled = false, capture = "متوقف", capturing = false,
            brain = SyncBrain.Info(text = "المزامنة التلقائية متوقفة"))
    }

    /** يوقف المهمة الجارية فقط دون تغيير التفضيل. */
    fun cancel() {
        job?.cancel()
        captureJob?.cancel()
    }

    /** يبدأ القياس من الصفر (زر "مزامنة الآن"). */
    fun resync() {
        synchronized(brain) { brain.reset(System.nanoTime()) }
        start()
    }

    /** يُستدعى عند تغيير القناة على الريسيفر: التأخير السابق انتهى ومحتوى القناة تغيّر. */
    fun onChannelChange() {
        if (!running) return
        synchronized(brain) { brain.onChannelRestart(System.nanoTime() + 1_500_000_000L) }
    }

    private fun onAudioDisturbed() {
        if (!running) return
        synchronized(brain) { brain.onExternalRestart(System.nanoTime()) }
    }

    // ------------------------------------------------------------ الحلقة الرئيسية

    private suspend fun run() {
        publish(captureText = "يبدأ…")
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissionScreen()
            publish(captureText = "يلزم إذن تسجيل الصوت")
        }
        synchronized(brain) {
            brain.cfg = SyncBrain.Config(calibMs = prefs.calibMs)
            brain.reset(System.nanoTime())
        }
        captureJob = scope.launch { captureLoop() }

        var tick = 0
        while (scope.isActive && _ui.value.enabled) {
            delay(TICK_MS)
            tick++
            if (tick % STEP_EVERY_TICKS == 0) {
                val actions = synchronized(brain) { brain.step(System.nanoTime(), chanEnv, pc.refEnvelope) }
                for (a in actions) execute(a)
            }
            publish()
        }
    }

    private fun publish(captureText: String? = null, capturing: Boolean? = null) {
        val info = synchronized(brain) { brain.info }
        val cur = _ui.value
        _ui.value = cur.copy(
            brain = info,
            capture = captureText ?: cur.capture,
            capturing = capturing ?: cur.capturing,
            control = control.status.detail,
            lastAction = lastAction,
            actionsCount = actionsCount,
        )
    }

    private fun note(text: String) {
        lastAction = text
        actionsCount++
        AppLog.d("action: $text")
    }

    // ------------------------------------------------------------ التنفيذ

    private suspend fun execute(a: SyncBrain.Action) {
        when (a) {
            is SyncBrain.Action.DelayAudio -> applyAudioDelta(a.ms, "تأخير صوتك ${a.ms} م.ث")
            is SyncBrain.Action.DelayVideo -> {
                val have = pc.ui.value.delayMs
                if (have >= a.rawOffsetMs) {
                    // صوتك مؤخَّر أصلًا: يكفي تقليل تأخيره بدل إيقاف الصورة
                    applyAudioDelta(-a.rawOffsetMs, "تقليل تأخير صوتك ${a.rawOffsetMs} م.ث")
                } else {
                    delayVideo(a)
                }
            }
        }
    }

    // اختبار الإيقاف المؤقت من شاشة التشخيص: يعمل في نطاق المحرك كي لا يُلغى إذا تغيّرت الشاشة
    private val _test = MutableStateFlow("")
    val testResult: StateFlow<String> = _test

    @Volatile
    private var testing = false

    fun runPauseTest() {
        if (testing) return
        testing = true
        scope.launch {
            // مهلة للخروج من التطبيق ومشاهدة القناة: الأمر يذهب للتطبيق الظاهر على الشاشة
            for (n in 15 downTo 1) {
                _test.value = "اخرج الآن إلى القناة وراقب الصورة… يبدأ الاختبار بعد $n ث"
                delay(1000)
            }
            _test.value = "جارٍ إرسال أمر الإيقاف… راقب الصورة 5 ثوانٍ"
            _test.value = try {
                control.testPulse()
            } catch (e: Exception) {
                "فشل: ${e.message}"
            }
            testing = false
        }
    }

    /**
     * مزامنة يدوية بنقرتين: [offsetMs] = كم يتأخر التعليق عن القناة كما سمعه المستخدم (يتضمن تأخير صوتنا الحالي).
     * إن كان تأخير صوتنا الحالي يكفي نقلّله، وإلا نوقف الريسيفر (offset + 400) ثم نؤخّر صوتنا 400 ليتطابقا،
     * ويبقى هامش 400 م.ث للضبط الدقيق بالزرين ±0.1.
     */
    fun manualOffset(offsetMs: Int) {
        scope.launch {
            val over = 400
            val have = pc.ui.value.delayMs
            when {
                offsetMs < 150 -> note("الفرق المقاس صغير جدًا (${offsetMs} م.ث): لا تغيير")
                offsetMs > 60_000 -> note("الفرق المقاس كبير جدًا: أعد المحاولة")
                have >= offsetMs -> {
                    pc.setDelay(have - offsetMs)
                    note("يدوي: تقليل تأخير صوتك ${offsetMs} م.ث")
                }
                else -> {
                    val r = control.pauseFor((offsetMs + over).toLong())
                    if (!r.ok) {
                        note("تعذّر التحكم بالريسيفر: ${r.error}")
                    } else {
                        pc.setDelay((have + over + prefs.calibMs).coerceIn(0, PlayerController.MAX_DELAY_MS))
                        note("يدوي: تأخير الصورة ${offsetMs + over} م.ث")
                    }
                }
            }
            publish()
        }
    }

    private fun applyAudioDelta(deltaMs: Int, text: String) {
        val target = pc.ui.value.delayMs + deltaMs
        if (target > PlayerController.MAX_DELAY_MS || target < 0) {
            synchronized(brain) { brain.onApplyFailed(System.nanoTime(), "تأخير الصوت المطلوب خارج الحد المسموح") }
            return
        }
        pc.setDelay(target)
        synchronized(brain) { brain.onAudioShifted(System.nanoTime()) }
        note(text)
    }

    private suspend fun delayVideo(a: SyncBrain.Action.DelayVideo) {
        publish()
        val r = control.pauseFor(a.ms.toLong())
        if (!r.ok) {
            synchronized(brain) { brain.onApplyFailed(System.nanoTime(), "تعذّر التحكم بالريسيفر: ${r.error}") }
            return
        }
        // تحقق: صوت القناة يجب أن يسكت أثناء الإيقاف، وإلا فالأمر لم يصل للمشغّل
        val from = r.startNs + 800_000_000L
        val count = ((r.resumeNs - r.startNs - 1_100_000_000L) / EnvelopeStore.BUCKET_NS).toInt()
        if (count >= 10) {
            val seg = chanEnv.slice(from, count)
            val quiet = seg.count { it < 8f }.toFloat() / seg.size
            if (quiet < 0.8f) {
                synchronized(brain) {
                    brain.onApplyFailed(
                        System.nanoTime(),
                        "أمر الإيقاف لم يوقف القناة؛ غيّر زر الإيقاف أو طريقة التحكم من الإعدادات",
                    )
                }
                return
            }
        }
        synchronized(brain) { brain.onVideoShifted(r.resumeNs + 500_000_000L) }
        note("تأخير الصورة ${a.ms} م.ث")
    }

    // ------------------------------------------------------------ التقاط صوت القناة

    private fun requestPermissionScreen() {
        try {
            ctx.startActivity(
                Intent(ctx, CaptureProjectionActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: Exception) {
            AppLog.d("cannot launch permission activity: $e")
        }
    }

    private suspend fun captureLoop() {
        var askedOnce = false
        while (scope.isActive && _ui.value.enabled) {
            val mic = prefs.captureFromMic
            val granted = ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
            val projection = CaptureHolder.projection
            if (!granted || (!mic && projection == null)) {
                publish(captureText = if (!granted) "يلزم إذن تسجيل الصوت" else "يلزم إذن التقاط صوت القناة", capturing = false)
                if (!askedOnce && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    askedOnce = true
                    requestPermissionScreen()
                }
                delay(2_000)
                continue
            }
            if (mic) startMicService()
            val rec = buildRecord(projection, mic)
            if (rec == null) {
                publish(captureText = "تعذّر فتح التقاط الصوت على هذا الجهاز", capturing = false)
                delay(5_000)
                continue
            }
            try {
                rec.startRecording()
                publish(captureText = if (mic) "يلتقط من الميكروفون" else "يلتقط صوت القناة", capturing = true)
                readLoop(rec)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.d("capture error: $e")
                publish(captureText = "انقطع الالتقاط، يعيد المحاولة…", capturing = false)
            } finally {
                try {
                    rec.stop()
                } catch (_: Exception) {
                }
                rec.release()
            }
            synchronized(brain) { brain.onChannelRestart(System.nanoTime()) }
            delay(1_000)
        }
    }

    private fun startMicService() {
        try {
            ContextCompat.startForegroundService(
                ctx, Intent(ctx, CaptureService::class.java).putExtra(CaptureService.EXTRA_MIC_ONLY, true)
            )
        } catch (e: Exception) {
            AppLog.d("mic service failed: $e")
        }
    }

    private suspend fun readLoop(rec: AudioRecord) {
        val chunk = RATE / EnvelopeStore.BUCKETS_PER_SEC
        val buf = ShortArray(chunk)
        var last = System.nanoTime()
        while (scope.isActive && _ui.value.enabled) {
            var got = 0
            while (got < chunk) {
                val r = rec.read(buf, got, chunk - got)
                if (r < 0) throw IllegalStateException("خطأ قراءة الصوت ($r)")
                if (r == 0) delay(5)
                got += r
            }
            var sq = 0.0
            for (s in buf) sq += s.toDouble() * s
            val now = System.nanoTime()
            // فجوة طويلة بين قراءتين = انقطع الالتقاط فعليًا
            if (now - last > 600_000_000L) synchronized(brain) { brain.onChannelRestart(now) }
            last = now
            chanEnv.put(now - EnvelopeStore.BUCKET_NS, sqrt(sq / chunk).toFloat())
        }
    }

    private fun buildRecord(mp: MediaProjection?, mic: Boolean): AudioRecord? = try {
        val fmt = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build()
        val b = AudioRecord.Builder().setAudioFormat(fmt).setBufferSizeInBytes(RATE * 2)
        if (mic || mp == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            b.setAudioSource(MediaRecorder.AudioSource.MIC)
        } else {
            val cfg = AudioPlaybackCaptureConfiguration.Builder(mp)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .excludeUid(Process.myUid()) // لا نلتقط صوتنا نحن
                .build()
            b.setAudioPlaybackCaptureConfig(cfg)
        }
        val rec = b.build()
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            null
        } else rec
    } catch (e: Exception) {
        AppLog.d("buildRecord failed: $e")
        null
    }
}
