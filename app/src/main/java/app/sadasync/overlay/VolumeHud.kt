package app.sadasync.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View

/** شريط صوت صغير هادئ يظهر لحظة الضغط على Vol +/- ثم يختفي. */
class VolumeHud(ctx: Context) : View(ctx) {

    companion object {
        const val W_DP = 240
        const val H_DP = 48
        private const val BG = 0xE60B1230.toInt()
        private const val TRACK = 0xFF2A3566.toInt()
        private const val TEAL = 0xFF33DDC0.toInt()
        private const val DIM = 0xFF8A8F98.toInt()
    }

    var pct = 100
        set(v) { field = v; invalidate() }
    var muted = false
        set(v) { field = v; invalidate() }

    private val d = resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()
    private val path = Path()

    override fun onMeasure(w: Int, h: Int) {
        setMeasuredDimension((W_DP * d).toInt(), (H_DP * d).toInt())
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        p.style = Paint.Style.FILL
        p.color = BG
        r.set(0f, 0f, w, h)
        c.drawRoundRect(r, h / 2, h / 2, p)

        val on = !muted && pct > 0
        val col = if (on) TEAL else DIM

        // أيقونة مكبّر الصوت
        val cx = 28 * d
        val cy = h / 2
        p.color = col
        path.reset()
        path.moveTo(cx - 8 * d, cy - 4 * d)
        path.lineTo(cx - 3 * d, cy - 4 * d)
        path.lineTo(cx + 3 * d, cy - 9 * d)
        path.lineTo(cx + 3 * d, cy + 9 * d)
        path.lineTo(cx - 3 * d, cy + 4 * d)
        path.lineTo(cx - 8 * d, cy + 4 * d)
        path.close()
        c.drawPath(path, p)
        p.style = Paint.Style.STROKE
        p.strokeWidth = 2 * d
        p.strokeCap = Paint.Cap.ROUND
        if (on) {
            r.set(cx - 1 * d, cy - 6 * d, cx + 9 * d, cy + 6 * d)
            c.drawArc(r, -50f, 100f, false, p)
            if (pct > 50) {
                r.set(cx - 4 * d, cy - 11 * d, cx + 13 * d, cy + 11 * d)
                c.drawArc(r, -50f, 100f, false, p)
            }
        } else {
            c.drawLine(cx + 7 * d, cy - 5 * d, cx + 15 * d, cy + 5 * d, p)
            c.drawLine(cx + 15 * d, cy - 5 * d, cx + 7 * d, cy + 5 * d, p)
        }

        // المسار
        val x0 = 52 * d
        val x1 = w - 56 * d
        val th = 6 * d
        p.style = Paint.Style.FILL
        p.color = TRACK
        r.set(x0, cy - th / 2, x1, cy + th / 2)
        c.drawRoundRect(r, th / 2, th / 2, p)
        val fill = if (muted) 0f else (pct.coerceIn(0, 100) / 100f)
        if (fill > 0f) {
            p.color = TEAL
            r.set(x0, cy - th / 2, x0 + (x1 - x0) * fill, cy + th / 2)
            c.drawRoundRect(r, th / 2, th / 2, p)
        }

        // الرقم
        p.color = if (on) 0xFFFFFFFF.toInt() else DIM
        p.textSize = 17 * d
        p.typeface = Typeface.DEFAULT_BOLD
        p.textAlign = Paint.Align.RIGHT
        c.drawText(if (muted) "0" else pct.toString(), w - 18 * d, cy + 6 * d, p)
    }
}
