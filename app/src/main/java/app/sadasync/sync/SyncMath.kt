package app.sadasync.sync

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** الرياضيات الصرفة لمطابقة غلافين صوتيين (بدون أي اعتماد على أندرويد، سهلة الاختبار). */
object SyncMath {
    class Result(val lagBuckets: Int, val score: Float, val second: Float)

    /**
     * يحوّل غلاف الشدة إلى سمات تبرز "الأحداث" (هتاف، صافرة، بداية كلام):
     * لوغاريتم ثم طرح المتوسط المتحرك (نافذة ~2 ثانية) حتى لا يهيمن مستوى الصوت العام.
     */
    fun features(x: FloatArray): FloatArray {
        val n = x.size
        val l = FloatArray(n) { ln(1f + x[it]) }
        val pre = DoubleArray(n + 1)
        for (i in 0 until n) pre[i + 1] = pre[i] + l[i]
        val w = 50
        return FloatArray(n) { i ->
            val a = max(0, i - w)
            val b = min(n, i + w + 1)
            l[i] - ((pre[b] - pre[a]) / (b - a)).toFloat()
        }
    }

    /**
     * [c] غلاف القناة (طوله n). [rFull] غلاف صوتنا ويغطي [lBack] قبل بداية [c] حتى نهايتها (طوله lBack+n).
     * نبحث عن lag بحيث c[t] ≈ rFull[lBack + t + lag].
     * lag سالب  → صوتنا يسبق القناة (نحتاج تأخيرًا إضافيًا بمقدار |lag|).
     * lag موجب  → صوتنا متأخر عن القناة (نحتاج تقديمًا).
     */
    fun bestLag(c: FloatArray, rFull: FloatArray, lBack: Int, lFwd: Int, minOverlap: Int): Result {
        val n = c.size
        val scores = FloatArray(lBack + lFwd + 1) { -1f }
        var best = -2f
        var bestLag = 0
        for (lag in -lBack..lFwd) {
            val t1 = min(n, n - lag)
            if (t1 < minOverlap) continue
            var dot = 0.0
            var cc = 0.0
            var rr = 0.0
            for (t in 0 until t1) {
                val a = c[t]
                val b = rFull[lBack + t + lag]
                dot += a * b
                cc += a * a
                rr += b * b
            }
            val s = if (cc < 1e-6 || rr < 1e-6) 0f else (dot / sqrt(cc * rr)).toFloat()
            scores[lag + lBack] = s
            if (s > best) {
                best = s
                bestLag = lag
            }
        }
        var second = -1f
        for (i in scores.indices) {
            if (abs(i - (bestLag + lBack)) > 25 && scores[i] > second) second = scores[i]
        }
        return Result(bestLag, best, second)
    }
}
