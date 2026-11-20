package app.sadasync.sync

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** الرياضيات الصرفة لمطابقة غلافين صوتيين (بدون أي اعتماد على أندرويد، سهلة الاختبار). */
object SyncMath {
    /** نمط السمات: 2 = بدايات الأحداث (الأفضل في الاختبار). */
    @JvmField var mode = 2

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
        val f = FloatArray(n) { i ->
            val a = max(0, i - w)
            val b = min(n, i + w + 1)
            l[i] - ((pre[b] - pre[a]) / (b - a)).toFloat()
        }
        return when (mode) {
            1 -> FloatArray(n) { i -> max(0f, f[i]) }
            2 -> {
                // بدايات الأحداث: الفرق الموجب خلال 100ms على لوغاريتم مُنعَّم
                val sm = FloatArray(n) { i ->
                    var s = 0f
                    var c = 0
                    for (k in max(0, i - 2)..min(n - 1, i + 2)) { s += l[k]; c++ }
                    s / c
                }
                FloatArray(n) { i -> if (i < 5) 0f else max(0f, sm[i] - sm[i - 5]) }
            }
            else -> f
        }
    }

    /**
     * [e] سمات صوتنا الخارجي (طولها n). [c] سمات القناة، وتبدأ قبل بداية [e] بمقدار [pos] وتنتهي بعد نهايتها
     * بمقدار [neg] (طولها n + pos + neg).
     *
     * نبحث عن أفضل lag بحيث e[t] ≈ c[t + pos - lag]:
     *  - lag موجب: صوتنا متأخر عن القناة → نؤخّر الصورة بمقدار lag.
     *  - lag سالب: صوتنا يسبق القناة → نؤخّر صوتنا بمقدار |lag|.
     * lag ∈ [-neg, +pos]. التداخل دائمًا كامل (n)، فلا حاجة لحد أدنى للتداخل.
     */
    fun bestLag(e: FloatArray, c: FloatArray, pos: Int, neg: Int): Result {
        val scores = scoreAll(e, c, pos, neg)
        var best = -2f
        var bestLag = 0
        for (i in scores.indices) {
            if (scores[i] > best) {
                best = scores[i]
                bestLag = i - neg
            }
        }
        var second = -1f
        for (i in scores.indices) {
            if (abs(i - (bestLag + neg)) > 25 && scores[i] > second) second = scores[i]
        }
        return Result(bestLag, best, second)
    }

    /** درجة الارتباط (−1…1) لكل lag في [-neg, +pos]؛ العنصر i يوافق lag = i − neg. */
    fun scoreAll(e: FloatArray, c: FloatArray, pos: Int, neg: Int): FloatArray {
        val n = e.size
        require(c.size == n + pos + neg) { "bad sizes ${c.size} vs ${n + pos + neg}" }

        var ee = 0.0
        for (v in e) ee += v.toDouble() * v
        // مجاميع تراكمية لمربعات سمات القناة لحساب طاقة كل مقطع بسرعة
        val cs = DoubleArray(c.size + 1)
        for (i in c.indices) cs[i + 1] = cs[i] + c[i].toDouble() * c[i]

        val scores = FloatArray(pos + neg + 1)
        for (lag in -neg..pos) {
            val start = pos - lag
            val cc = cs[start + n] - cs[start]
            var s = 0f
            if (ee > 1e-6 && cc > 1e-6) {
                var dot = 0.0
                for (t in 0 until n) dot += e[t].toDouble() * c[start + t]
                s = (dot / sqrt(ee * cc)).toFloat()
            }
            scores[lag + neg] = s
        }
        return scores
    }
}
