package app.sadasync.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View
import app.sadasync.App
import app.sadasync.data.AudioSource
import app.sadasync.data.SourceGroups
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
        const val HEIGHT_DP = 545
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
                "الصوتية", { p.ui.value.source?.name ?: "—" },
                onCenter = { openDrop(); false },
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
            Row("صوت التعليق", { if (p.ui.value.myAudio) "مسموع" else "مكتوم" }, onCenter = { p.toggleMyAudio(); false }),
            Row(
                "صوت القناة", { if (e.channelIsMuted) "مكتوم" else "مسموع" },
                onCenter = { e.toggleChannelMute(); false },
            ),
            Row(
                "اختبار كتم صوت القناة", { "اضغط" },
                onCenter = { e.control.startTvTest(2500); true }, // تُغلق اللوحة ثم يعمل الاختبار والقناة ظاهرة
            ),
            Row(
                "تصفير تأخير الصورة", { "اضغط" },
                onCenter = { e.resetVideoDelay(); true },
            ),
            Row(
                "تسجيل صوت القناة وسماعه", { "اضغط" },
                // تُغلق اللوحة ثم يعمل الاختبار والقناة ظاهرة؛ النتيجة في «التشخيص»
                onCenter = { e.control.startEchoListen(2500, 10); true },
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
            e.holdForManual()
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
        if (dd > 0) {
            if (ddEntries.isNotEmpty()) ddSel = (ddSel + dir + ddEntries.size) % ddEntries.size
            invalidate()
            return
        }
        sel = (sel + dir + rows.size) % rows.size
        invalidate()
    }

    // ------------------------------------------------------------ القائمة المنسدلة للصوتيات

    private class DdEntry(val title: String, val sub: String, val src: AudioSource?, val group: String?)

    private var dd = 0 // 0 مغلقة، 1 مجموعات، 2 صوتيات مجموعة
    private var ddSel = 0
    private var ddScroll = 0
    private var ddGroup: String? = null
    private var ddEntries: List<DdEntry> = emptyList()

    private fun sourcesByGroup(): Map<String, List<AudioSource>> =
        app.sources.all.value.filter { it.id != SourceGroups.SILENCE_ID }.groupBy { SourceGroups.of(it) }

    private fun openDrop() {
        val by = sourcesByGroup()
        val list = ArrayList<DdEntry>()
        app.sources.all.value.firstOrNull { it.id == SourceGroups.SILENCE_ID }?.let {
            list += DdEntry(it.name, "", it, null)
        }
        for (g in SourceGroups.named) list += DdEntry(g, groupCount(by[g]?.size ?: 0), null, g)
        by[SourceGroups.OTHER]?.let { list += DdEntry(SourceGroups.OTHER, groupCount(it.size), null, SourceGroups.OTHER) }
        ddEntries = list
        dd = 1
        ddGroup = null
        val cur = app.player.ui.value.source
        val curGroup = cur?.let { if (it.id == SourceGroups.SILENCE_ID) null else SourceGroups.of(it) }
        ddSel = list.indexOfFirst { if (cur?.id == SourceGroups.SILENCE_ID) it.src?.id == cur.id else it.group == curGroup }.coerceAtLeast(0)
        ddScroll = 0
        invalidate()
    }

    private fun groupCount(n: Int) = if (n == 0) "فارغة" else "$n ◂"

    private fun openGroup(g: String) {
        val items = sourcesByGroup()[g].orEmpty()
        ddEntries = items.map { DdEntry(it.name, "", it, null) }
        dd = 2
        ddGroup = g
        val curId = app.player.ui.value.source?.id
        ddSel = items.indexOfFirst { it.id == curId }.coerceAtLeast(0)
        ddScroll = 0
    }

    private fun closeDrop() {
        dd = 0
        ddEntries = emptyList()
        invalidate()
    }

    /** زر الرجوع: يغلق/يرجع خطوة داخل القائمة المنسدلة؛ يعيد false إن لم تكن مفتوحة (فتُغلق اللوحة). */
    fun onBack(): Boolean {
        when (dd) {
            2 -> {
                val g = ddGroup
                openDrop()
                ddSel = ddEntries.indexOfFirst { it.group == g }.coerceAtLeast(0)
            }
            1 -> closeDrop()
            else -> return false
        }
        invalidate()
        return true
    }

    private fun drawDropdown(c: Canvas, l: Float, r: Float, top: Float, h: Float) {
        val bottom = h - dp(34f)
        paint.style = Paint.Style.FILL
        paint.color = 0xFF16191D.toInt()
        rect.set(l - dp(6f), top - dp(6f), r + dp(6f), bottom)
        c.drawRoundRect(rect, dp(16f), dp(16f), paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(1.5f)
        paint.color = GOLD
        c.drawRoundRect(rect, dp(16f), dp(16f), paint)
        drawText(c, if (dd == 2) (ddGroup ?: "") else "اختر الصوتية", r - dp(12f), top + dp(18f), 17f, GOLD, true, bold = true)
        if (dd == 2) drawText(c, "◂ رجوع", l + dp(12f), top + dp(18f), 13f, GRAY, false)
        val rh = dp(36f)
        val gp = dp(3f)
        val listTop = top + dp(32f)
        val vis = ((bottom - listTop - dp(8f)) / (rh + gp)).toInt().coerceAtLeast(3)
        if (ddEntries.isEmpty()) {
            drawText(c, "لا توجد صوتيات هنا بعد", r - dp(14f), listTop + dp(24f), 15f, GRAY, true)
            return
        }
        if (ddSel < ddScroll) ddScroll = ddSel
        if (ddSel >= ddScroll + vis) ddScroll = ddSel - vis + 1
        val curId = app.player.ui.value.source?.id
        for (k in 0 until vis) {
            val i = ddScroll + k
            if (i >= ddEntries.size) break
            val e = ddEntries[i]
            val y = listTop + k * (rh + gp)
            rect.set(l, y, r, y + rh)
            paint.style = Paint.Style.FILL
            if (i == ddSel) {
                paint.color = 0x33FFFFFF
                c.drawRoundRect(rect, dp(12f), dp(12f), paint)
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = dp(2f)
                paint.color = Color.WHITE
                c.drawRoundRect(rect, dp(12f), dp(12f), paint)
            } else {
                paint.color = 0x14FFFFFF
                c.drawRoundRect(rect, dp(12f), dp(12f), paint)
            }
            val isCur = e.src != null && e.src.id == curId
            drawText(c, fit(e.title, (r - l) - dp(70f), 16f), r - dp(14f), y + dp(24f), 16f, if (isCur) GREEN else Color.WHITE, true, bold = isCur)
            val sub = if (isCur) "✓" else e.sub
            if (sub.isNotEmpty()) drawText(c, sub, l + dp(14f), y + dp(24f), 14f, if (isCur) GREEN else GOLD, false)
        }
        if (ddScroll > 0) drawText(c, "▲", (l + r) / 2f, listTop - dp(2f), 11f, GRAY, false)
        if (ddScroll + vis < ddEntries.size) drawText(c, "▼", (l + r) / 2f, bottom - dp(4f), 11f, GRAY, false)
    }

    fun adjust(dir: Int) {
        if (dd > 0) return
        val r = rows[sel]
        (if (dir < 0) r.onLeft else r.onRight)?.invoke()
        invalidate()
    }

    /** يعيد true إذا طُلب إغلاق اللوحة. */
    fun activate(): Boolean {
        if (dd > 0) {
            val e = ddEntries.getOrNull(ddSel)
            if (e != null) {
                if (e.src != null) {
                    app.player.play(e.src)
                    closeDrop()
                } else if (e.group != null) {
                    openGroup(e.group)
                }
            }
            invalidate()
            return false
        }
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
        val rowH = dp(31f)
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
            drawText(c, r.label, menuRight - dp(14f), y + dp(21f), 16f, Color.WHITE, true)
            val v = fit(r.value(), menuW * 0.5f, 15f)
            drawText(c, v, menuLeft + dp(14f), y + dp(21f), 15f, GOLD, false)
        }

        if (dd > 0) drawDropdown(c, menuLeft, menuRight, top, h)

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
        c.drawText(
            if (dd > 0) "▲ ▼ تنقل      OK اختيار      رجوع للخلف" else "◂ ▸ تغيير      OK تنفيذ      رجوع إغلاق",
            w / 2f, h - dp(14f), text,
        )
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
