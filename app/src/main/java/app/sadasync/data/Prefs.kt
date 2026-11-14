package app.sadasync.data

import android.content.Context
import android.view.KeyEvent
import java.util.UUID

class Prefs(ctx: Context) {
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
}
