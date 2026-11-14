package app.sadasync.sync

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Process
import androidx.core.content.ContextCompat
import app.sadasync.player.PlayerController
import app.sadasync.util.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.sqrt

/**
 * المزامنة التلقائية: يلتقط صوت القناة (AudioPlaybackCapture بعد إذن المستخدم) لمدة 14 ثانية،
 * ويقارن غلاف شدته بغلاف صوتنا المسجّل في DelayAudioProcessor، ثم يضبط التأخير حسب أفضل ازاحة.
 *
 * حدود معروفة: يعمل فقط حين يسمح تطبيق القنوات بالتقاط صوته، وعلى أجهزة تدعم MediaProjection،
 * ويحتاج صوتًا مشتركًا بين المصدرين (جمهور/صافرات/ضجيج ملعب) ليجد التطابق.
 */
class AutoSyncEngine(private val ctx: Context, private val pc: PlayerController) {
    companion object {
        const val CAPTURE_SECONDS = 14
        const val LAG_BACK_SECONDS = 25
        const val MIN_OVERLAP_SECONDS = 5
        const val RATE = 16_000

        /** تعويض ثابت (ms) لتأخر خرج المشغّل عن توقيت المعالج؛ عدّله بعد المعايرة على جهازك. */
        const val OUTPUT_LATENCY_COMP_MS = 0
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    private var job: Job? = null

    val running: Boolean get() = job?.isActive == true

    fun start() {
        if (running) return
        job = scope.launch {
            try {
                run()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.d("autosync failed: $e")
                report("تعذّرت المزامنة: ${e.message}")
            }
        }
    }

    fun cancel() {
        job?.cancel()
    }

    private fun report(s: String) = pc.setSyncStatus(s)

    private suspend fun run() {
        if (!pc.ui.value.playing) return report("شغّل الصوت أولًا")
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return report("المزامنة التلقائية تتطلب أندرويد 10+")

        val projection = CaptureHolder.projection
        if (projection == null) {
            report("امنح إذن المزامنة التلقائية من الصفحة الرئيسية للتطبيق")
            try {
                ctx.startActivity(
                    Intent(ctx, CaptureProjectionActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (e: Exception) {
                AppLog.d("cannot launch permission activity: $e")
            }
            return
        }
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            return report("يلزم إذن تسجيل الصوت")
        }
        val rec = buildRecord(projection) ?: return report("تعذّر فتح التقاط صوت القناة على هذا الجهاز")

        val channelEnv = EnvelopeStore(60)
        var t0 = 0L
        try {
            rec.startRecording()
            t0 = System.nanoTime()
            withContext(Dispatchers.IO) { readLoop(rec, channelEnv, t0) { isActive } }
        } finally {
            try {
                rec.stop()
            } catch (e: Exception) {
                AppLog.d("rec.stop: $e")
            }
            rec.release()
        }

        val n = CAPTURE_SECONDS * EnvelopeStore.BUCKETS_PER_SEC
        val lBack = LAG_BACK_SECONDS * EnvelopeStore.BUCKETS_PER_SEC
        val minOverlap = MIN_OVERLAP_SECONDS * EnvelopeStore.BUCKETS_PER_SEC
        val lFwd = n - minOverlap

        val chRaw = channelEnv.slice(t0, n)
        val refRaw = pc.refEnvelope.slice(t0 - lBack * EnvelopeStore.BUCKET_NS, lBack + n)
        if (chRaw.max() < 5f) return report("لم يُلتقط صوت من القناة (ربما يمنع تطبيق القنوات التقاطه)")
        if (refRaw.max() < 5f) return report("صوتك لا يصدر الآن؛ تأكد أنه يعمل")

        val res = SyncMath.bestLag(SyncMath.features(chRaw), SyncMath.features(refRaw), lBack, lFwd, minOverlap)
        AppLog.d("sync lag=${res.lagBuckets} score=${res.score} second=${res.second}")

        val confident = res.score >= 0.25f && res.score - res.second >= 0.07f
        if (!confident) {
            return report("لم تتأكد المطابقة (${(res.score * 100).toInt()}%). جرّب وقت يوجد فيه هتاف أو تعليق")
        }
        val deltaMs = -res.lagBuckets * 20 - OUTPUT_LATENCY_COMP_MS
        if (deltaMs == 0) return report("التوقيت مضبوط")
        pc.setDelay(pc.ui.value.delayMs + deltaMs)
        report(String.format(Locale.US, "تمت المزامنة: %+.2f ث", deltaMs / 1000.0))
    }

    private fun readLoop(rec: AudioRecord, store: EnvelopeStore, t0: Long, alive: () -> Boolean) {
        val chunk = RATE / EnvelopeStore.BUCKETS_PER_SEC
        val buf = ShortArray(chunk)
        val end = t0 + CAPTURE_SECONDS * 1_000_000_000L
        var lastSec = -1
        while (alive() && System.nanoTime() < end) {
            var got = 0
            while (got < chunk) {
                val r = rec.read(buf, got, chunk - got)
                if (r < 0) throw IllegalStateException("خطأ قراءة الصوت ($r)")
                if (r == 0) Thread.sleep(5)
                got += r
            }
            var sq = 0.0
            for (s in buf) sq += s.toDouble() * s
            val now = System.nanoTime()
            store.put(now - EnvelopeStore.BUCKET_NS, sqrt(sq / chunk).toFloat())
            val sec = ((now - t0) / 1_000_000_000L).toInt()
            if (sec != lastSec) {
                lastSec = sec
                report("يستمع لصوت القناة… $sec/$CAPTURE_SECONDS")
            }
        }
    }

    private fun buildRecord(mp: MediaProjection): AudioRecord? = try {
        val cfg = AudioPlaybackCaptureConfiguration.Builder(mp)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .excludeUid(Process.myUid()) // لا نلتقط صوتنا نحن
            .build()
        val fmt = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build()
        val rec = AudioRecord.Builder()
            .setAudioFormat(fmt)
            .setBufferSizeInBytes(RATE * 2)
            .setAudioPlaybackCaptureConfig(cfg)
            .build()
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            null
        } else rec
    } catch (e: Exception) {
        AppLog.d("buildRecord failed: $e")
        null
    }
}
