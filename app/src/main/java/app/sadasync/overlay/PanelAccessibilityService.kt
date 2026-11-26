package app.sadasync.overlay

import android.accessibilityservice.AccessibilityService
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.KeyEvent
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import app.sadasync.App
import app.sadasync.util.AppLog

/**
 * الجسر بين الريموت وفوق القناة:
 *  - يلتقط ضغطات الريموت (flagRequestFilterKeyEvents) حتى والتطبيق في الخلفية.
 *  - ضغطتان سريعتان على زر التفعيل (الأحمر افتراضيًا) تفتحان اللوحة.
 *  - أثناء ظهور اللوحة تُستهلك أزرار الاتجاهات/OK/Back وتقود اللوحة؛ بقية الأزرار (الصوت…) تمر للنظام.
 *  - تغيير القناة (CH+/CH- أو الأرقام) يُبلغ محرك المزامنة ليعيد القياس تلقائيًا.
 * لا يقرأ محتوى الشاشة (canRetrieveWindowContent=false).
 */
class PanelAccessibilityService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var wm: WindowManager
    private var panel: PanelView? = null
    private var lastTrigger = 0L
    private var lastInteraction = 0L
    private var swallowUp = -1

    private val ticker = object : Runnable {
        override fun run() {
            val p = panel ?: return
            if (SystemClock.uptimeMillis() - lastInteraction > IDLE_HIDE_MS) {
                hidePanel()
                return
            }
            p.refresh()
            handler.postDelayed(this, 250)
        }
    }

    /** بعد تغيير القناة بثوانٍ: الريسيفر صفّر تأخير الصورة والمحتوى تغيّر، فنعيد القياس من جديد. */
    private val resync = Runnable {
        val app = App.instance
        if (app.prefs.autoResyncOnChannelChange && app.player.ui.value.playing) {
            if (app.autoSync.running) app.autoSync.onChannelChange() else if (app.prefs.autoSyncEnabled) app.autoSync.start()
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        AppLog.d("accessibility service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        removeHud()
        hidePanel()
        super.onDestroy()
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        val app = App.instance
        if (!app.prefs.activated) return false
        val code = event.keyCode

        // نبتلع حدث الرفع (UP) المقابل لضغطة استهلكناها سابقًا
        if (event.action == KeyEvent.ACTION_UP && code == swallowUp) {
            swallowUp = -1
            return true
        }

        if (isVolumeKey(code) && app.prefs.volKeysForApp && app.player.ui.value.playing) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                when (code) {
                    KeyEvent.KEYCODE_VOLUME_UP -> app.player.nudgeVolume(+5)
                    KeyEvent.KEYCODE_VOLUME_DOWN -> app.player.nudgeVolume(-5)
                    else -> app.player.toggleMyAudio()
                }
                showVolume()
            }
            return true
        }

        if (panel != null) return handlePanelKey(event)

        if (code == app.prefs.triggerKey) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                val now = SystemClock.uptimeMillis()
                if (now - lastTrigger < DOUBLE_PRESS_MS) {
                    lastTrigger = 0
                    swallowUp = code
                    showPanel()
                    return true
                }
                lastTrigger = now
            }
            return false
        }

        if (event.action == KeyEvent.ACTION_UP && isChannelKey(code)) {
            handler.removeCallbacks(resync)
            handler.postDelayed(resync, RESYNC_DELAY_MS)
        }
        return false
    }

    private fun handlePanelKey(event: KeyEvent): Boolean {
        val p = panel ?: return false
        val code = event.keyCode
        val handled = when (code) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> true
            else -> code == App.instance.prefs.triggerKey
        }
        if (!handled) return false

        if (event.action == KeyEvent.ACTION_DOWN) {
            lastInteraction = SystemClock.uptimeMillis()
            when (code) {
                KeyEvent.KEYCODE_DPAD_UP -> p.move(-1)
                KeyEvent.KEYCODE_DPAD_DOWN -> p.move(1)
                KeyEvent.KEYCODE_DPAD_LEFT -> p.adjust(-1)
                KeyEvent.KEYCODE_DPAD_RIGHT -> p.adjust(1)
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER ->
                    if (p.activate()) closeByKey(code)
                KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> if (!p.onBack()) closeByKey(code)
                else -> Unit
            }
        }
        return true
    }

    private fun closeByKey(code: Int) {
        swallowUp = code
        hidePanel()
    }

    private fun isVolumeKey(code: Int) =
        code == KeyEvent.KEYCODE_VOLUME_UP || code == KeyEvent.KEYCODE_VOLUME_DOWN || code == KeyEvent.KEYCODE_VOLUME_MUTE

    private var hud: VolumeHud? = null
    private val hideHud = Runnable { removeHud() }

    private fun showVolume() {
        val ui = App.instance.player.ui.value
        // القيمة الجديدة تصل بعد لحظة؛ نعرض المتوقعة فورًا بدل انتظار تحديث الحالة
        handler.post {
            val v = hud ?: VolumeHud(this).also {
                val d = resources.displayMetrics.density
                val lp = WindowManager.LayoutParams(
                    (VolumeHud.W_DP * d).toInt(), (VolumeHud.H_DP * d).toInt(),
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT,
                ).apply {
                    gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                    y = (56 * d).toInt()
                }
                try { wm.addView(it, lp); hud = it } catch (e: Exception) { AppLog.d("hud addView failed: $e"); return@post }
            }
            handler.postDelayed({
                val u = App.instance.player.ui.value
                v.pct = u.volumePct
                v.muted = !u.myAudio
            }, 60)
            v.pct = ui.volumePct
            v.muted = !ui.myAudio
            handler.removeCallbacks(hideHud)
            handler.postDelayed(hideHud, 1600)
        }
    }

    private fun removeHud() {
        hud?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        hud = null
    }

    private fun isChannelKey(code: Int) =
        code == KeyEvent.KEYCODE_CHANNEL_UP || code == KeyEvent.KEYCODE_CHANNEL_DOWN ||
            code in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9

    private fun showPanel() {
        if (panel != null) return
        val v = PanelView(this)
        val lp = WindowManager.LayoutParams(
            (PanelView.WIDTH_DP * resources.displayMetrics.density).toInt(),
            (PanelView.HEIGHT_DP * resources.displayMetrics.density).toInt(),
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.RIGHT
            x = (32 * resources.displayMetrics.density).toInt()
            y = (32 * resources.displayMetrics.density).toInt()
        }
        try {
            wm.addView(v, lp)
        } catch (e: Exception) {
            AppLog.d("addView failed: $e")
            return
        }
        panel = v
        lastInteraction = SystemClock.uptimeMillis()
        handler.postDelayed(ticker, 250)
    }

    private fun hidePanel() {
        handler.removeCallbacks(ticker)
        panel?.let {
            try {
                wm.removeView(it)
            } catch (e: Exception) {
                AppLog.d("removeView failed: $e")
            }
        }
        panel = null
    }

    companion object {
        private const val DOUBLE_PRESS_MS = 700L
        private const val IDLE_HIDE_MS = 20_000L
        private const val RESYNC_DELAY_MS = 2_500L
    }
}
