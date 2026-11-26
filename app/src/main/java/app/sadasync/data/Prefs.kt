package app.sadasync.data

import android.content.Context
import android.view.KeyEvent
import java.util.UUID

class Prefs(ctx: Context) {
    companion object {
        const val CONTROL_AUTO = 0
        const val CONTROL_ROOT = 1
        const val CONTROL_ADB = 2
        const val CONTROL_MEDIA = 3
    }

    private val sp = ctx.getSharedPreferences("sada", Context.MODE_PRIVATE)

    var activated: Boolean
        get() = sp.getBoolean("activated", false)
        set(v) = sp.edit().putBoolean("activated", v).apply()

    val deviceId: String
        get() = sp.getString("device_id", null)
            ?: UUID.randomUUID().toString().also { sp.edit().putString("device_id", it).apply() }

    /** تأخير الصوت بالمللي ثانية. موجب = نؤخّر الصوت، سالب = نقدّمه (للمصادر القابلة للتقديم). */
    var delayMs: Int
        get() = sp.getInt("delay_ms", 0)
        set(v) = sp.edit().putInt("delay_ms", v).apply()

    var lastSourceId: String?
        get() = sp.getString("last_source", null)
        set(v) = sp.edit().putString("last_source", v).apply()

    /** الزر الذي يفتح اللوحة عند الضغط عليه مرتين. الافتراضي: الزر الأحمر. */
    var triggerKey: Int
        get() = sp.getInt("trigger_key", KeyEvent.KEYCODE_PROG_RED)
        set(v) = sp.edit().putInt("trigger_key", v).apply()

    var autoResyncOnChannelChange: Boolean
        get() = sp.getBoolean("auto_resync", true)
        set(v) = sp.edit().putBoolean("auto_resync", v).apply()

    var userSourcesJson: String
        get() = sp.getString("user_sources", "[]") ?: "[]"
        set(v) = sp.edit().putString("user_sources", v).apply()

    var officialCacheJson: String
        get() = sp.getString("official_cache", "[]") ?: "[]"
        set(v) = sp.edit().putString("official_cache", v).apply()

    /** المزامنة التلقائية المستمرة مفعّلة (تبدأ مع تشغيل صوتك وتعمل حتى تُطفأ). */
    var autoSyncEnabled: Boolean
        get() = sp.getBoolean("auto_sync", true)
        set(v) = sp.edit().putBoolean("auto_sync", v).apply()

    /** طريقة التحكم بالريسيفر: CONTROL_AUTO / ROOT / ADB / MEDIA. */
    var controlMode: Int
        get() = sp.getInt("control_mode", CONTROL_AUTO)
        set(v) = sp.edit().putInt("control_mode", v).apply()

    /** عنوان ومنفذ ADB اللذان نجح الوصول إليهما (يُحفظان بعد «طلب وصول الريسيفر»). */
    var adbHost: String
        get() = sp.getString("adb_host", "127.0.0.1") ?: "127.0.0.1"
        set(v) = sp.edit().putString("adb_host", v).apply()
    var adbPortFound: Int
        get() = sp.getInt("adb_port_found", 0)
        set(v) = sp.edit().putInt("adb_port_found", v).apply()

    /** تأخير الصورة عبر قائمة الريسيفر (Audio مرتين ← الأحمر) بدل الإيقاف المؤقت. */
    var videoDelayViaMenu: Boolean
        get() = sp.getBoolean("vd_menu", true)
        set(v) = sp.edit().putBoolean("vd_menu", v).apply()

    /** آخر تأخير صورة طبّقه التطبيق في الريسيفر (م.ث). الخطوة 500 م.ث. */
    var videoDelayMs: Int
        get() = sp.getInt("vd_ms", 0)
        set(v) = sp.edit().putInt("vd_ms", v).apply()

    /** زر الإيقاف المؤقت والاستئناف المرسلان للريسيفر. الافتراضي: تشغيل/إيقاف (يعمل كمبدّل). */
    var pauseKey: Int
        get() = sp.getInt("pause_key", KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
        set(v) = sp.edit().putInt("pause_key", v).apply()

    var resumeKey: Int
        get() = sp.getInt("resume_key", KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
        set(v) = sp.edit().putInt("resume_key", v).apply()

    /** معايرة ثابتة (م.ث) تُضاف للفرق المقاس: لتعويض تأخر خرج الصوت عن التقاطه. */
    var calibMs: Int
        get() = sp.getInt("calib_ms", 0)
        set(v) = sp.edit().putInt("calib_ms", v).apply()

    /** مستوى صوت التعليق 0..100 (يتحكم به زرا الصوت + و-). */
    var myVolumePct: Int
        get() = sp.getInt("my_volume_pct", 100)
        set(v) = sp.edit().putInt("my_volume_pct", v).apply()

    /** زرا Vol +/- في الريموت يتحكمان بصوت التطبيق (التعليق) أثناء تشغيله. */
    var volKeysForApp: Boolean
        get() = sp.getBoolean("vol_keys_app", true)
        set(v) = sp.edit().putBoolean("vol_keys_app", v).apply()

    /** مصدر التقاط صوت القناة: false = صوت النظام (MediaProjection)، true = الميكروفون. */
    var captureFromMic: Boolean
        get() = sp.getBoolean("capture_mic", false)
        set(v) = sp.edit().putBoolean("capture_mic", v).apply()
}
