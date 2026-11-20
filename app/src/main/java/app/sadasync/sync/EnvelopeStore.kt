package app.sadasync.sync

/**
 * حلقة تخزين لـ"غلاف" شدة الصوت بدقة 20 مللي ثانية (50 قيمة في الثانية).
 * تُستخدم لمطابقة صوتنا مع صوت القناة عبر الارتباط المتقاطع.
 * المفتاح هو System.nanoTime() بحيث يمكن مقارنة المصدرين على نفس الساعة.
 */
class EnvelopeStore(seconds: Int = 150) {
    companion object {
        const val BUCKET_NS = 20_000_000L
        const val BUCKETS_PER_SEC = 50
    }

    private val n = seconds * BUCKETS_PER_SEC
    private val values = FloatArray(n)
    private val ids = LongArray(n) { -1L }

    @Synchronized
    fun put(timeNs: Long, v: Float) {
        val b = timeNs / BUCKET_NS
        if (b < 0) return
        val s = (b % n).toInt()
        ids[s] = b
        values[s] = v
    }

    /** يعيد [count] قيمة تبدأ من الزمن [startNs]؛ القيم المفقودة = 0. */
    @Synchronized
    fun slice(startNs: Long, count: Int): FloatArray {
        val b0 = Math.floorDiv(startNs, BUCKET_NS)
        return FloatArray(count) { i ->
            val b = b0 + i
            if (b < 0) {
                0f
            } else {
                val s = (b % n).toInt()
                if (ids[s] == b) values[s] else 0f
            }
        }
    }

    /** يمسح كل المحتوى (مثلًا عند إعادة تشغيل الالتقاط). */
    @Synchronized
    fun clear() {
        ids.fill(-1L)
        values.fill(0f)
    }
}
