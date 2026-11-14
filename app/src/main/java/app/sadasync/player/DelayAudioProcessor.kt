@file:OptIn(UnstableApi::class)

package app.sadasync.player

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import app.sadasync.sync.EnvelopeStore
import java.nio.ByteBuffer
import kotlin.math.sqrt

/**
 * معالج صوت يؤخّر الصوت بمقدار [delayMs] عبر ذاكرة حلقية (حتى 30 ثانية).
 * هذه هي الطريقة الوحيدة لتأخير بث مباشر لا يمكن "الرجوع" فيه.
 * كما يسجّل غلاف الشدة (بعد التأخير) في [envelopeSink] لاستخدامه في المزامنة التلقائية.
 */
class DelayAudioProcessor : BaseAudioProcessor() {
    companion object {
        const val MAX_DELAY_MS = 30_000
    }

    @Volatile var delayMs: Int = 0
    @Volatile var envelopeSink: EnvelopeStore? = null

    private var ring = ByteArray(0)
    private var pos = 0
    private var sampleRate = 44100
    private var frameSize = 4

    // حالة تسجيل الغلاف
    private var bucketFrames = 882
    private var accSq = 0.0
    private var accN = 0
    private var anchorNs = 0L
    private var frameIndex = 0L

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        sampleRate = inputAudioFormat.sampleRate
        frameSize = inputAudioFormat.bytesPerFrame
        bucketFrames = sampleRate / EnvelopeStore.BUCKETS_PER_SEC
        ring = ByteArray(sampleRate * frameSize * (MAX_DELAY_MS / 1000 + 1))
        resetState()
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val n = inputBuffer.remaining()
        if (n == 0) return
        val src = ByteArray(n)
        inputBuffer.get(src)
        val out = replaceOutputBuffer(n)

        val cap = ring.size
        val delayFrames = (delayMs.coerceIn(0, MAX_DELAY_MS).toLong() * sampleRate / 1000).toInt()
        var w = pos
        var r = w - delayFrames * frameSize
        if (r < 0) r += cap

        val dst = ByteArray(n)
        for (i in 0 until n) {
            ring[w] = src[i]
            dst[i] = ring[r]
            if (++w == cap) w = 0
            if (++r == cap) r = 0
        }
        pos = w

        out.put(dst)
        out.flip()
        tap(dst)
    }

    override fun onFlush() {
        ring.fill(0)
        resetState()
    }

    override fun onReset() {
        ring = ByteArray(0)
        resetState()
    }

    private fun resetState() {
        pos = 0
        accSq = 0.0
        accN = 0
        anchorNs = 0L
        frameIndex = 0L
    }

    /** يحسب RMS للقناة الأولى لكل 20ms ويسجله مع زمنه التقديري على ساعة nanoTime. */
    private fun tap(b: ByteArray) {
        val sink = envelopeSink ?: return
        if (anchorNs == 0L) anchorNs = System.nanoTime()
        var i = 0
        while (i + 1 < b.size) {
            val s = ((b[i + 1].toInt() shl 8) or (b[i].toInt() and 0xFF)).toShort().toInt()
            accSq += s.toDouble() * s
            accN++
            if (accN >= bucketFrames) {
                val t = anchorNs + frameIndex * 1_000_000_000L / sampleRate
                sink.put(t, sqrt(accSq / accN).toFloat())
                accSq = 0.0
                accN = 0
            }
            frameIndex++
            i += frameSize
        }
        // لو تأخر التشغيل عن الساعة (انقطاع/تحميل) نعيد التثبيت حتى لا ينجرف الزمن
        val expected = anchorNs + frameIndex * 1_000_000_000L / sampleRate
        val now = System.nanoTime()
        if (now - expected > 500_000_000L) {
            anchorNs = now
            frameIndex = 0L
        }
    }
}
