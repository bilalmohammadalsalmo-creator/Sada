package app.sadasync.sync

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * يحوّل دفعات PCM القادمة من مصدر Echo إلى قيم "غلاف" كل 20 مللي ثانية (منطق صرف بلا أندرويد).
 * زمن وصول كل دفعة يتذبذب قليلًا، لذلك نبني ساعة من عدد العينات ونصحّحها ببطء نحو زمن الوصول،
 * وإن ابتعدت كثيرًا (انقطاع فعلي) نعيد ضبطها ونُعلم المستدعي.
 */
class EchoEnvelope(
    private val rate: Int,
    private val channels: Int,
    private val onBucket: (startNs: Long, rms: Float) -> Unit,
) {
    private val framesPerBucket = rate / EnvelopeStore.BUCKETS_PER_SEC
    private var clockNs = Long.MIN_VALUE
    private var nextBucket = Long.MIN_VALUE
    private var sumSq = 0.0
    private var frames = 0

    /**
     * [endNs] زمن قراءة آخر عينة في الدفعة (System.nanoTime).
     * يعيد true إن اكتُشف انقطاع.
     */
    fun add(endNs: Long, pcm: ShortArray, count: Int): Boolean {
        val total = count / channels
        if (total <= 0) return false
        val durNs = total * 1_000_000_000L / rate
        var gap = false
        clockNs = if (clockNs == Long.MIN_VALUE) {
            endNs
        } else {
            val expected = clockNs + durNs
            val err = endNs - expected
            if (abs(err) > MAX_DRIFT_NS) {
                gap = true
                sumSq = 0.0
                frames = 0
                endNs
            } else {
                expected + err / 50
            }
        }
        for (f in 0 until total) {
            for (c in 0 until channels) {
                val v = pcm[f * channels + c].toDouble()
                sumSq += v * v
            }
            frames++
            if (frames == framesPerBucket) {
                val bucketEnd = clockNs - (total - 1 - f) * 1_000_000_000L / rate
                emit(bucketEnd - EnvelopeStore.BUCKET_NS, sqrt(sumSq / (frames * channels)).toFloat())
                sumSq = 0.0
                frames = 0
            }
        }
        return gap
    }

    /** خانات متتالية بلا فجوات: لا نقفز إلا إذا ابتعدت الساعة أكثر من خانة (انقطاع أو انحراف متراكم). */
    private fun emit(startNs: Long, rms: Float) {
        val b = EnvelopeStore.BUCKET_NS
        val target = Math.floorDiv(startNs + b / 2, b)
        if (nextBucket == Long.MIN_VALUE || abs(target - nextBucket) > 1) nextBucket = target
        onBucket(nextBucket * b, rms)
        nextBucket++
    }

    companion object {
        private const val MAX_DRIFT_NS = 400_000_000L
    }
}
