package app.sadasync.sync

import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * "دماغ" المزامنة التلقائية المستمرة. منطق صرف (بلا أندرويد): يُغذّى بغلافي الصوت ويقرر ماذا يُفعل.
 *
 * التعريف: offset = تأخر صوتنا الخارجي عن صوت القناة كما نسمعهما الآن.
 *  - offset موجب → نؤخّر الصورة (إيقاف مؤقت ثم تشغيل في الريسيفر) بمقدار offset.
 *  - offset سالب → نؤخّر صوتنا بمقدار |offset|.
 *
 * الدورة: WAITING/SEARCHING (جمع بيانات ومطابقة) → APPLYING (تنفيذ) → SEARCHING (تحقق من النتيجة)
 * → LOCKED (مراقبة دورية، وإعادة بحث إذا ضاعت المطابقة).
 *
 * ضد المطابقات الكاذبة (إيقاف الصورة بلا سبب أسوأ من عدم المزامنة): لا نكتفي بنافذة واحدة، بل نجمع درجات
 * الارتباط لكل lag عبر عدة نوافذ متتالية، ولا نقبل إلا قمة ثابتة تتفوق إحصائيًا (z-score) على بقية القيم.
 */
class SyncBrain(var cfg: Config = Config()) {

    data class Config(
        /** طول نافذة صوتنا المستخدمة في كل قياس. */
        val windowSec: Int = 20,
        /** نترك آخر ثوانٍ من صوتنا خارج النافذة ليتسع المدى السالب (صوتنا يسبق القناة). */
        val tailSec: Int = 4,
        val maxPosSec: Int = 50,
        /** مدى البحث وقت المراقبة (بعد القفل). أكبر من هذا يعني فقدان المطابقة وإعادة بحث كامل. */
        val lockedRangeSec: Int = 15,
        /** صوتنا يسبق القناة بأكثر من هذا → نؤخّر صوتنا (دقيق، فنقبل خطأً صغيرًا). */
        val toleranceAudioMs: Int = 60,
        /** فرق موجب أصغر من هذا لا نحاول إصلاحه بالإيقاف المؤقت (دقة ضغط الأزرار لا تكفي). */
        val minVideoShiftMs: Int = 300,
        val maxVideoShiftMs: Int = 60_000,
        val maxAudioShiftMs: Int = 30_000,
        /**
         * نُطيل إيقاف الصورة عمدًا بهذا المقدار، فتصبح بقية الفرق سالبة (صوتك يسبق)، فنعالجها بتأخير صوتك الدقيق
         * (بدقة 20 م.ث) بدل الاعتماد على دقة ضغطة الريموت.
         */
        val videoOvershootMs: Int = 400,
        /** أدنى متوسط ارتباط للقمة، وأدنى تفوق على أفضل قمة بعيدة عنها، وأدنى z-score. */
        val minScore: Float = 0.12f,
        val minMargin: Float = 0.04f,
        val minZ: Float = 11f,
        /** أقل عدد قياسات متتالية قبل الحكم. */
        val minChecks: Int = 6,
        /** وزن القياسات القديمة أثناء المراقبة (قبل القفل = 1.0 أي بلا نسيان). */
        val lockedDecay: Double = 0.7,
        val searchEveryMs: Long = 3_000,
        val lockedEveryMs: Long = 6_000,
        val lostAfterMisses: Int = 6,
        /** معايرة ثابتة (ms) تُضاف إلى الفرق المقاس لتعويض تأخر الخرج. */
        val calibMs: Int = 0,
        val cooldownAfterFailMs: Long = 30_000,
    )

    enum class Phase { WAITING, SEARCHING, APPLYING, LOCKED }

    sealed interface Action {
        /** [ms] مدة الإيقاف المقترحة (تشمل تجاوزًا مقصودًا)، و[rawOffsetMs] الفرق المقاس الفعلي. */
        data class DelayVideo(val ms: Int, val rawOffsetMs: Int) : Action
        data class DelayAudio(val ms: Int) : Action
    }

    data class Info(
        val phase: Phase = Phase.WAITING,
        val text: String = "",
        val offsetMs: Int? = null,
        val score: Float = 0f,
        val z: Float = 0f,
        val rangeSec: Int = 0,
        /** أفضل lag مرشّح حاليًا (حتى لو لم تكتمل الثقة)، للتشخيص. */
        val peakLagMs: Int? = null,
        val checks: Int = 0,
        val chanSilent: Boolean = false,
        val extSilent: Boolean = false,
    )

    @Volatile
    var info: Info = Info(text = "متوقف")
        private set

    private val per = EnvelopeStore.BUCKETS_PER_SEC
    private val negMax = cfg.tailSec * per - 10
    private val posMax = cfg.maxPosSec * per
    private val acc = DoubleArray(negMax + posMax + 1)
    private val wgt = DoubleArray(negMax + posMax + 1)
    private var checks = 0

    private var phase = Phase.WAITING
    private val notStarted = Long.MAX_VALUE / 4
    private var chanValidFrom = notStarted
    private var extValidFrom = notStarted
    private var lastCheckNs: Long? = null
    private var cooldownUntil = Long.MIN_VALUE / 4
    private var pendingLag: Int? = null
    private var misses = 0

    private fun set(
        p: Phase,
        text: String,
        offset: Int? = info.offsetMs,
        score: Float = info.score,
        z: Float = info.z,
        range: Int = info.rangeSec,
        chanSilent: Boolean = false,
        extSilent: Boolean = false,
        peak: Int? = info.peakLagMs,
    ) {
        phase = p
        info = Info(p, text, offset, score, z, range, peak, checks, chanSilent, extSilent)
    }

    private fun clearEvidence() {
        acc.fill(0.0)
        wgt.fill(0.0)
        checks = 0
        pendingLag = null
        misses = 0
    }

    // ------------------------------------------------------------ أحداث من الخارج

    /** بداية جديدة كاملة (تشغيل المزامنة، أو إعادة مزامنة يدوية). */
    fun reset(nowNs: Long) {
        chanValidFrom = nowNs
        extValidFrom = nowNs
        lastCheckNs = null
        cooldownUntil = Long.MIN_VALUE / 4
        clearEvidence()
        set(Phase.WAITING, "يبدأ الاستماع…", offset = null, score = 0f, z = 0f, range = 0)
    }

    /** يبدأ بحثًا جديدًا مع الإبقاء على ما سُجّل (للزر اليدوي «إعادة المزامنة»). */
    fun restartSearch() {
        if (phase == Phase.APPLYING) return
        clearEvidence()
        lastCheckNs = null
        set(Phase.SEARCHING, "بدأ بحثًا جديدًا…", offset = null, score = 0f, z = 0f)
    }

    /** محتوى القناة المسجَّل قبل [nowNs] لم يعد صالحًا للمطابقة (تغيير قناة، انقطاع، إعادة التقاط). */
    fun onChannelRestart(nowNs: Long) {
        if (phase == Phase.APPLYING) return
        invalidateChannel(nowNs)
    }

    /** مصدرنا انقطع أو أُعيد تحميله أو غُيّر؛ محتواه المسجَّل قبل [nowNs] لم يعد صالحًا. */
    fun onExternalRestart(nowNs: Long) {
        if (phase == Phase.APPLYING) return
        invalidateExternal(nowNs)
    }

    /** نُفّذ تأخير الصورة (انتهى الإيقاف المؤقت). */
    fun onVideoShifted(nowNs: Long) = invalidateChannel(nowNs)

    /** غُيّر تأخير صوتنا؛ الغلاف المسجّل بعد التغيير يختلف عن قبله. */
    fun onAudioShifted(nowNs: Long) = invalidateExternal(nowNs + 300_000_000L)

    /** فشل التنفيذ (مثلًا لا طريقة للتحكم بالريسيفر). نتوقف قليلًا حتى لا نكرر الفشل كل ثوانٍ. */
    fun onApplyFailed(nowNs: Long, reason: String) {
        cooldownUntil = nowNs + cfg.cooldownAfterFailMs * 1_000_000L
        clearEvidence()
        set(Phase.SEARCHING, reason)
    }

    private fun invalidateChannel(nowNs: Long) {
        chanValidFrom = nowNs
        clearEvidence()
        set(Phase.SEARCHING, "يعيد جمع صوت القناة…", range = 0)
    }

    private fun invalidateExternal(nowNs: Long) {
        extValidFrom = nowNs
        clearEvidence()
        set(Phase.SEARCHING, "يعيد جمع صوتك…", range = 0)
    }

    // ------------------------------------------------------------ الدورة

    fun step(nowNs: Long, chan: EnvelopeStore, ext: EnvelopeStore): List<Action> {
        if (phase == Phase.APPLYING) return emptyList()
        if (nowNs < cooldownUntil) return emptyList()
        val every = (if (phase == Phase.LOCKED) cfg.lockedEveryMs else cfg.searchEveryMs) * 1_000_000L
        val last = lastCheckNs
        if (last != null && nowNs - last < every) return emptyList()
        lastCheckNs = nowNs

        val b = EnvelopeStore.BUCKET_NS
        val nowB = Math.floorDiv(nowNs, b)
        val n = cfg.windowSec * per
        val tailB = cfg.tailSec * per
        val aB = nowB - tailB - n // بداية نافذة صوتنا (بالـbuckets)

        val extFromB = -Math.floorDiv(-extValidFrom, b)
        val chanFromB = -Math.floorDiv(-chanValidFrom, b)
        val keep = if (phase == Phase.LOCKED) Phase.LOCKED else Phase.WAITING
        if (aB < extFromB) {
            set(keep, "ينتظر صوتك (${(extFromB - aB) / per + 1} ث)…")
            return emptyList()
        }
        val posLimit = if (phase == Phase.LOCKED) cfg.lockedRangeSec * per else posMax
        val pos = min(posLimit.toLong(), aB - chanFromB).toInt()
        val neg = negMax
        if (pos < per) {
            set(keep, "يستمع لصوت القناة (${(chanFromB + per - aB) / per + 1} ث)…")
            return emptyList()
        }

        val eRaw = ext.slice(aB * b, n)
        val cRaw = chan.slice((aB - pos) * b, pos + n + neg)
        val chanSilent = cRaw.max() < 5f
        val extSilent = eRaw.max() < 5f
        if (chanSilent) {
            set(phase, "لا يصل صوت من القناة (قد يمنع الجهاز التقاطه)", chanSilent = true)
            return emptyList()
        }
        if (extSilent) {
            set(phase, "صوتك الخارجي لا يصدر الآن", extSilent = true)
            return emptyList()
        }

        // نضيف درجات هذه النافذة إلى الأدلة المتراكمة
        val scores = SyncMath.scoreAll(SyncMath.features(eRaw), SyncMath.features(cRaw), pos, neg)
        val decay = if (phase == Phase.LOCKED) cfg.lockedDecay else 1.0
        for (i in scores.indices) {
            acc[i] = acc[i] * decay + scores[i]
            wgt[i] = wgt[i] * decay + 1.0
        }
        checks++
        val rangeSec = pos / per
        val ev = evaluate(pos)

        val confident = ev != null && checks >= cfg.minChecks &&
            ev.peak >= cfg.minScore && ev.peak - ev.second >= cfg.minMargin && ev.z >= cfg.minZ
        if (!confident || ev == null) {
            pendingLag = null
            val sc = ev?.peak ?: info.score
            val zz = ev?.z ?: 0f
            val peakMs = ev?.let { it.lag * 20 + cfg.calibMs }
            if (phase == Phase.LOCKED) {
                misses++
                if (misses >= cfg.lostAfterMisses) {
                    clearEvidence()
                    set(Phase.SEARCHING, "فُقدت المطابقة، يعيد البحث…", score = sc, z = zz, range = rangeSec, peak = peakMs)
                } else {
                    set(Phase.LOCKED, "متزامن (مطابقة ضعيفة الآن)", score = sc, z = zz, range = rangeSec, peak = peakMs)
                }
            } else {
                set(
                    Phase.SEARCHING,
                    String.format(Locale.US, "جارٍ مطابقة الصوتين… (مدى البحث %d ث)", rangeSec),
                    score = sc, z = zz, range = rangeSec, peak = peakMs,
                )
            }
            return emptyList()
        }

        misses = 0
        val offset = ev.lag * 20 + cfg.calibMs
        val acceptable = offset >= -cfg.toleranceAudioMs && offset < cfg.minVideoShiftMs
        if (acceptable) {
            pendingLag = null
            lock(offset, ev.peak, ev.z, rangeSec)
            return emptyList()
        }

        // قياس واثق لكن الفرق كبير: نطلب تأكيدًا من قياس ثانٍ قريب منه قبل أي تنفيذ
        val p = pendingLag
        if (p != null && abs(p - ev.lag) <= 3) {
            pendingLag = null
            return decide(offset, ev.peak, ev.z, rangeSec)
        }
        pendingLag = ev.lag
        set(
            phase,
            String.format(Locale.US, "وجد فرقًا %+.2f ث، يتأكد…", offset / 1000.0),
            offset = offset, score = ev.peak, z = ev.z, range = rangeSec,
        )
        return emptyList()
    }

    private class Eval(val lag: Int, val peak: Float, val second: Float, val z: Float)

    /** يقيّم المتوسطات المتراكمة على lag ∈ [-negMax, pos] التي جُمع لها ما يكفي من القياسات. */
    private fun evaluate(pos: Int): Eval? {
        val minW = cfg.minChecks - 0.5
        var m = 0
        var sum = 0.0
        var sum2 = 0.0
        var best = -2.0
        var bestLag = 0
        for (lag in -negMax..pos) {
            val i = lag + negMax
            if (wgt[i] < minW) continue
            val mean = acc[i] / wgt[i]
            m++
            sum += mean
            sum2 += mean * mean
            if (mean > best) {
                best = mean
                bestLag = lag
            }
        }
        if (m < 100) return null
        val mu = sum / m
        val sd = sqrt(max(sum2 / m - mu * mu, 1e-8))
        var second = -2.0
        for (lag in -negMax..pos) {
            val i = lag + negMax
            if (wgt[i] < minW || abs(lag - bestLag) <= 25) continue
            val mean = acc[i] / wgt[i]
            if (mean > second) second = mean
        }
        return Eval(bestLag, best.toFloat(), second.toFloat(), ((best - mu) / sd).toFloat())
    }

    private fun lock(offset: Int, score: Float, z: Float, range: Int) {
        val txt = if (abs(offset) <= cfg.toleranceAudioMs) {
            String.format(Locale.US, "متزامن ✓ (فرق %+d م.ث)", offset)
        } else {
            String.format(Locale.US, "متزامن تقريبًا (فرق %+d م.ث)", offset)
        }
        set(Phase.LOCKED, txt, offset = offset, score = score, z = z, range = range)
    }

    private fun decide(offset: Int, score: Float, z: Float, range: Int): List<Action> {
        if (offset > 0) {
            if (offset > cfg.maxVideoShiftMs) {
                set(Phase.SEARCHING, "الفرق أكبر من الحد المسموح", offset, score, z, range)
                return emptyList()
            }
            set(Phase.APPLYING, String.format(Locale.US, "يؤخّر الصورة %.2f ث…", (offset + cfg.videoOvershootMs) / 1000.0), offset, score, z, range)
            return listOf(Action.DelayVideo(offset + cfg.videoOvershootMs, offset))
        }
        val ms = -offset
        if (ms > cfg.maxAudioShiftMs) {
            set(Phase.SEARCHING, "الفرق أكبر من الحد المسموح", offset, score, z, range)
            return emptyList()
        }
        set(Phase.APPLYING, String.format(Locale.US, "يؤخّر صوتك %.2f ث…", ms / 1000.0), offset, score, z, range)
        return listOf(Action.DelayAudio(ms))
    }
}
