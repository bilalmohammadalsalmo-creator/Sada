package app.sadasync.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import app.sadasync.App
import java.util.Locale

/**
 * اللوحة التي تظهر فوق القناة. تُبنى برمجيًا (Views) لأنها تعيش داخل نافذة Overlay
 * تابعة لخدمة الوصول، ويقودها الريموت عبر PanelAccessibilityService (لا تأخذ التركيز).
 */
class PanelView(ctx: Context) : LinearLayout(ctx) {

    private class Row(
        val label: String,
        val value: () -> String,
        val onLeft: (() -> Unit)? = null,
        val onRight: (() -> Unit)? = null,
        val onCenter: (() -> Boolean)? = null, // يعيد true لطلب إغلاق اللوحة
    )

    private val app = App.instance
    private val statusView = TextView(ctx)
    private val rows: List<Row>
    private val rowViews = mutableListOf<LinearLayout>()
    private val valueViews = mutableListOf<TextView>()
    private var sel = 0

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun fmtDelay(ms: Int) = String.format(Locale.US, "%+.1f ث", ms / 1000.0)

    /** يعزل النص كي تبقى الأسهم والإشارات في مكانها داخل واجهة RTL. */
    private fun arrows(s: String) = "⁦◂  $s  ▸⁩"

    init {
        orientation = VERTICAL
        layoutDirection = View.LAYOUT_DIRECTION_RTL
        setPadding(dp(20), dp(16), dp(20), dp(16))
        background = GradientDrawable().apply {
            setColor(Color.parseColor("#EE101214"))
            cornerRadius = dp(18).toFloat()
            setStroke(dp(1), Color.parseColor("#33FFFFFF"))
        }

        val p = app.player
        rows = listOf(
            Row("تشغيل", { if (p.ui.value.playing) "يعمل" else "متوقف" }, onCenter = { p.toggle(); false }),
            Row(
                "المصدر", { arrows(p.ui.value.source?.name ?: "—") },
                onLeft = { p.cycleSource(-1) }, onRight = { p.cycleSource(1) },
            ),
            Row(
                "التوقيت", { arrows(fmtDelay(p.ui.value.delayMs)) },
                onLeft = { p.nudgeDelay(-500) }, onRight = { p.nudgeDelay(500) },
                onCenter = { p.setDelay(0); false },
            ),
            Row(
                "ضبط دقيق", { arrows(fmtDelay(p.ui.value.delayMs)) },
                onLeft = { p.nudgeDelay(-100) }, onRight = { p.nudgeDelay(100) },
            ),
            Row(
                "مزامنة تلقائية", { if (app.autoSync.running) "جارٍ…" else "اضغط للبدء" },
                onCenter = { app.autoSync.start(); false },
            ),
            Row("الصوت", { if (p.ui.value.myAudio) "صوتي" else "صوت القناة" }, onCenter = { p.toggleMyAudio(); false }),
            Row("إغلاق", { "" }, onCenter = { true }),
        )

        addView(TextView(ctx).apply {
            text = "صدى"
            setTextColor(Color.parseColor("#FFB020"))
            textSize = 22f
            typeface = Typeface.DEFAULT_BOLD
        })
        statusView.apply {
            setTextColor(Color.parseColor("#B3FFFFFF"))
            textSize = 14f
            setPadding(0, dp(2), 0, dp(10))
        }
        addView(statusView)

        rows.forEach { r ->
            val label = TextView(ctx).apply {
                text = r.label
                setTextColor(Color.WHITE)
                textSize = 18f
            }
            val value = TextView(ctx).apply {
                setTextColor(Color.parseColor("#FFB020"))
                textSize = 18f
                gravity = Gravity.CENTER_VERTICAL
            }
            val row = LinearLayout(ctx).apply {
                orientation = HORIZONTAL
                setPadding(dp(14), dp(10), dp(14), dp(10))
                addView(label, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
                addView(value, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
            }
            rowViews += row
            valueViews += value
            addView(row, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })
        }
        refresh()
    }

    fun refresh() {
        val ui = app.player.ui.value
        statusView.text = listOf(ui.status, ui.syncStatus).filter { it.isNotEmpty() }.joinToString(" • ")
        rows.forEachIndexed { i, r ->
            valueViews[i].text = r.value()
            rowViews[i].background = if (i == sel) GradientDrawable().apply {
                setColor(Color.parseColor("#26FFFFFF"))
                cornerRadius = dp(10).toFloat()
                setStroke(dp(2), Color.WHITE)
            } else null
        }
    }

    fun move(d: Int) {
        sel = (sel + d + rows.size) % rows.size
        refresh()
    }

    fun adjust(d: Int) {
        val r = rows[sel]
        (if (d < 0) r.onLeft else r.onRight)?.invoke()
        refresh()
    }

    /** يعيد true إذا طُلب إغلاق اللوحة. */
    fun activate(): Boolean {
        val close = rows[sel].onCenter?.invoke() ?: false
        refresh()
        return close
    }
}
