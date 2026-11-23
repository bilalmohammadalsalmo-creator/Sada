package app.sadasync.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View
import app.sadasync.App
import app.sadasync.sync.EngineUi
import app.sadasync.sync.EnvelopeStore
import app.sadasync.sync.SyncBrain
import java.util.Locale
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/**
 * اللوحة التي تظهر فوق القناة: قائمة أوامر على اليمين، وعلى اليسار حالة المزامنة الحيّة مع موجتي الصوت
 * (القناة وصوتك) لترى المطابقة بعينك. تُرسم برمجيًا لأنها تعيش داخل نافذة Overlay تابعة لخدمة الوصول،
 * ويقودها الريموت عبر PanelAccessibilityService (لا تأخذ التركيز).
 */
class PanelView(ctx: Context) : View(ctx) {

    private class Row(
        val label: String,
        val value: () -> String,
        val onLeft: (() -> Unit)? = null,
        val onRight: (() -> Unit)? = null,
        val onCenter: (() -> Boolean)? = null, // يعيد true لطلب إغلاق اللوحة
    )

    companion object {
        const val WIDTH_DP = 860
        const val HEIGHT_DP = 520
        private const val GOLD = 0xFFFFB020.toInt()
        private const val GREEN = 0xFF5BD97B.toInt()
        private const val RED = 0xFFFF6B6B.toInt()
        private const val GRAY = 0xFF8A8F98.toInt()
        private const val WAVE_SECONDS = 10
        private const val WAVE_COLS = 100
    }

    private val app = App.instance
    private val d = resources.displayMetrics.density
    private fun dp(v: Float) = v * d

    private val rows: List<Row>
    private var sel = 0

    // المزامنة بنقرتين: 0 = جاهز، 1 = بانتظار الضغطة الثانية
    private var tapStep = 0
    private var tapT1 = 0L

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT }
    private val rect = RectF()

    private fun fmtDelay(ms: Int) = String.format(Locale.US, "%+.1f ث", ms / 1000.0)
    private fun fmtSec(ms: Int) = String.format(Locale.US, "%+.2f ث", ms / 1000.0)

    /** يعزل النص كي تبقى الأسهم في مكانها داخل واجهة RTL. */
    private fun arrows(s: String) = "⁦◂  $s  ▸⁩"

    init {
        val p = app.player
        val e = app.autoSync
        rows = listOf(
            Row("تشغيل", { if (p.ui.value.playing) "يعمل" else "متوقف" }, onCenter = { p.toggle(); false }),
            Row(
                "المصدر", { arrows(p.ui.value.source?.name ?: "—") },
                onLeft = { p.cycleSource(-1) }, onRight = { p.cycleSource(1) },
            ),
            Row(
                "المزامنة التلقائية", { if (e.ui.value.enabled) "مفعّلة" else "متوقفة" },
                onCenter = { if (e.ui.value.enabled) e.stop() else e.start(); false },
            ),
            Row("مزامنة الآن", { "اضغط" }, onCenter = { e.resync(); false }),
            Row(
                "مزامنة بنقرتين",
                { if (tapStep == 0) "اضغط (1/2)" else "عند التعليق (2/2)" },
                onCenter = { tapSync(e); false },
            ),
            Row(
                "تأخير صوتك", { arrows(fmtDelay(p.ui.value.delayMs)) },
                onLeft = { p.nudgeDelay(-500) }, onRight = { p.nudgeDelay(500) },
                onCenter = { p.setDelay(0); false },
            ),
            Row(
                "ضبط دقيق", { arrows(fmtDelay(p.ui.value.delayMs)) },
                onLeft = { p.nudgeDelay(-100) }, onRight = { p.nudgeDelay(100) },
            ),
            Row("الصوت", { if (p.ui.value.myAudio) "صوتي" else "صوت القناة" }, onCenter = { p.toggleMyAudio(); false }),
            Row(
                "تجربة تأخير الصورة", { "اضغط" },
                onCenter = { e.control.startDelayTest(1200); true }, // تُغلق اللوحة ثم تُرسل الأزرار للقناة
            ),
            Row(
                "تصفير تأخير الصورة", { "اضغط" },
                onCenter = { e.resetVideoDelay(); true },
            ),
            Row("إغلاق", { "" }, onCenter = { true }),
        )
    }

    /** النقرة الأولى عند سماع الحدث في القناة، والثانية عند سماعه في التعليق. */
    private fun tapSync(e: app.sadasync.sync.AutoSyncEngine) {
        val now = System.nanoTime()
        if (tapStep == 1 && (now - tapT1) > 60_000_000_000L) tapStep = 0 // انتهت المهلة
        if (tapStep == 0) {
            tapT1 = now
            tapStep = 1
        } else {
            tapStep = 0
            e.manualOffset(((now - tapT1) / 1_000_000L).toInt())
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(dp(WIDTH_DP.toFloat()).toInt(), dp(HEIGHT_DP.toFloat()).toInt())
    }

    fun refresh() = invalidate()

    fun move(dir: Int) {
        sel = (sel + dir + rows.size) % rows.size
        invalidate()
    }

    fun adjust(dir: Int) {
        val r = rows[sel]
        (if (dir < 0) r.onLeft else r.onRight)?.invoke()
        invalidate()
    }

    /** يعيد true إذا طُلب إغلاق اللوحة. */
    fun activate(): Boolean {
        val close = rows[sel].onCenter?.invoke() ?: false
        invalidate()
        return close
    }

    // ------------------------------------------------------------ الرسم

    private fun drawText(c: Canvas, s: String, x: Float, y: Float, sizeDp: Float, color: Int, right: Boolean, bold: Boolean = false) {
        text.textSize = dp(sizeDp)
        text.color = color
        text.isFakeBoldText = bold
        text.textAlign = if (right) Paint.Align.RIGHT else Paint.Align.LEFT
        c.drawText(s, x, y, text)
    }

    private fun fit(s: String, maxW: Float, sizeDp: Float): String {
        text.textSize = dp(sizeDp)
        if (text.measureText(s) <= maxW) return s
        var t = s
        while (t.length > 3 && text.measureText("$t…") > maxW) t = t.dropLast(1)
        return "$t…"
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val pad = dp(22f)

        // الخلفية
        paint.style = Paint.Style.FILL
        paint.color = 0xF0101214.toInt()
        rect.set(0f, 0f, w, h)
        c.drawRoundRect(rect, dp(20f), dp(20f), paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(1f)
        paint.color = 0x33FFFFFF
        rect.set(dp(0.5f), dp(0.5f), w - dp(0.5f), h - dp(0.5f))
        c.drawRoundRect(rect, dp(20f), dp(20f), paint)

        val p = app.player.ui.value
        val eng = app.autoSync.ui.value
        val info = eng.brain

        // ----- الرأس
        drawText(c, "صدى", w - pad, pad + dp(20f), 24f, GOLD, true, bold = true)
        val (chipText, chipColor) = chip(eng, info)
        text.textSize = dp(15f)
        val chipW = text.measureText(chipText) + dp(28f)
        paint.style = Paint.Style.FILL
        paint.color = (chipColor and 0x00FFFFFF) or 0x33000000
        rect.set(pad, pad, pad + chipW, pad + dp(32f))
        c.drawRoundRect(rect, dp(16f), dp(16f), paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(1.5f)
        paint.color = chipColor
        c.drawRoundRect(rect, dp(16f), dp(16f), paint)
        drawText(c, chipText, pad + dp(14f), pad + dp(22f), 15f, chipColor, false, bold = true)

        val top = pad + dp(52f)
        val menuW = dp(290f)
        val gap = dp(18f)

        // ----- القائمة (اليمين)
        val menuRight = w - pad
        val menuLeft = menuRight - menuW
        val rowH = dp(34f)
        rows.forEachIndexed { i, r ->
            val y = top + i * (rowH + dp(3f))
            rect.set(menuLeft, y, menuRight, y + rowH)
            if (i == sel) {
                paint.style = Paint.Style.FILL
                paint.color = 0x26FFFFFF
                c.drawRoundRect(rect, dp(12f), dp(12f), paint)
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = dp(2f)
                paint.color = Color.WHITE
                c.drawRoundRect(rect, dp(12f), dp(12f), paint)
            } else {
                paint.style = Paint.Style.FILL
                paint.color = 0x14FFFFFF
                c.drawRoundRect(rect, dp(12f), dp(12f), paint)
            }
            drawText(c, r.label, menuRight - dp(14f), y + dp(23f), 16f, Color.WHITE, true)
            val v = fit(r.value(), menuW * 0.5f, 15f)
            drawText(c, v, menuLeft + dp(14f), y + dp(23f), 15f, GOLD, false)
        }

        // ----- التفاصيل (اليسار)
        val detLeft = pad
        val detRight = menuLeft - gap
        val detW = detRight - detLeft
        var y = top

        // المصدر الحالي
        val srcName = p.source?.name ?: "لم تختر مصدرًا"
        drawText(c, fit(srcName, detW, 20f), detRight, y + dp(20f), 20f, Color.WHITE, true, bold = true)
        y += dp(30f)
        val statusLine = info.text
        drawText(c, fit(statusLine, detW, 15f), detRight, y + dp(14f), 15f, chipColor, true)
        y += dp(30f)

        // بطاقة الموجات
        val cardH = dp(150f)
        paint.style = Paint.Style.FILL
        paint.color = 0x14FFFFFF
        rect.set(detLeft, y, detRight, y + cardH)
        c.drawRoundRect(rect, dp(14f), dp(14f), paint)
        drawWave(c, "القناة", app.autoSync.chanEnv, detLeft, y + dp(6f), detW, dp(66f), 0xFFE8EAED.toInt())
        drawWave(c, "صوتك", app.player.refEnvelope, detLeft, y + dp(76f), detW, dp(66f), GOLD)
        y += cardH + dp(12f)

        // الأرقام
        val offText = info.offsetMs?.let { fmtSec(it) } ?: "—"
        drawText(c, "الفرق المقاس: $offText", detRight, y + dp(16f), 15f, Color.WHITE, true)
        drawText(c, "تأخير صوتك الحالي: ${fmtDelay(p.delayMs)}", detLeft, y + dp(16f), 15f, 0xB3FFFFFF.toInt(), false)
        y += dp(26f)
        val cap = if (eng.capturing) "الالتقاط: يعمل" else "الالتقاط: ${eng.capture}"
        drawText(c, fit(cap, detW * 0.5f, 13f), detRight, y + dp(12f), 13f, if (eng.capturing) GRAY else RED, true)
        drawText(c, fit("الريسيفر: ${eng.control}", detW * 0.5f, 13f), detLeft, y + dp(12f), 13f, GRAY, false)
        y += dp(22f)
        if (eng.lastAction.isNotEmpty()) {
            drawText(c, fit("آخر إجراء: ${eng.lastAction}", detW, 13f), detRight, y + dp(12f), 13f, GRAY, true)
        }

        // تلميح الأزرار
        text.textSize = dp(12f)
        text.color = 0x80FFFFFF.toInt()
        text.isFakeBoldText = false
        text.textAlign = Paint.Align.CENTER
        c.drawText("◂ ▸ تغيير      OK تنفيذ      رجوع إغلاق", w / 2f, h - dp(14f), text)
    }

    private fun chip(eng: EngineUi, info: SyncBrain.Info): Pair<String, Int> = when {
        !eng.enabled -> "المزامنة متوقفة" to GRAY
        info.chanSilent || info.extSilent -> "لا صوت" to RED
        info.phase == SyncBrain.Phase.LOCKED -> "متزامن ✓" to GREEN
        info.phase == SyncBrain.Phase.APPLYING -> "يطبّق…" to GOLD
        info.phase == SyncBrain.Phase.SEARCHING -> "يطابق…" to GOLD
        else -> "يستمع…" to GOLD
    }

    /** يرسم موجة آخر [WAVE_SECONDS] ثوانٍ (الأحدث على اليمين). يعيد المستوى الأقصى المرسوم. */
    private fun drawWave(
        c: Canvas, label: String, store: EnvelopeStore,
        left: Float, top: Float, width: Float, height: Float, color: Int,
    ): Float {
        val per = EnvelopeStore.BUCKETS_PER_SEC
        val n = WAVE_SECONDS * per
        val end = System.nanoTime()
        val raw = store.slice(end - n * EnvelopeStore.BUCKET_NS, n)
        val cols = FloatArray(WAVE_COLS)
        val step = n / WAVE_COLS
        var peak = 0f
        for (i in 0 until WAVE_COLS) {
            var m = 0f
            for (k in 0 until step) m = max(m, raw[i * step + k])
            val v = ln(1f + m) / ln(1f + 12000f)
            cols[i] = min(1f, v)
            peak = max(peak, m)
        }
        drawText(c, label, left + width - dp(12f), top + dp(14f), 12f, color, true)
        val x0 = left + dp(12f)
        val x1 = left + width - dp(12f)
        val bw = (x1 - x0) / WAVE_COLS
        val mid = top + dp(14f) + (height - dp(14f)) / 2f
        val half = (height - dp(14f)) / 2f
        paint.style = Paint.Style.FILL
        paint.color = color
        for (i in 0 until WAVE_COLS) {
            val a = max(dp(1f), cols[i] * half)
            // الأقدم يسارًا والأحدث يمينًا (محور زمن عادي)
            val x = x0 + i * bw
            rect.set(x + bw * 0.2f, mid - a, x + bw * 0.8f, mid + a)
            c.drawRoundRect(rect, bw * 0.3f, bw * 0.3f, paint)
        }
        // خط أساس
        paint.color = 0x22FFFFFF
        rect.set(x0, mid - dp(0.5f), x1, mid + dp(0.5f))
        c.drawRect(rect, paint)
        return peak
    }
}
