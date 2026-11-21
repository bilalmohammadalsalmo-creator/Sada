package app.sadasync.data

import android.os.Build
import app.sadasync.BuildConfig
import app.sadasync.util.AppLog
import app.sadasync.util.Http
import org.json.JSONObject

/** يرسل بلاغ دعم (ملاحظة + معلومات الجهاز + آخر السجل). يفضّل تيليغرام، وإلا الخادم. */
object SupportReporter {
    suspend fun send(prefs: Prefs, note: String = ""): Boolean = try {
        if (BuildConfig.TG_BOT_TOKEN.isNotBlank() && BuildConfig.TG_CHAT_ID.isNotBlank()) {
            val text = buildString {
                append("بلاغ دعم جديد\n")
                append("المشكلة: ").append(note.ifBlank { "—" }).append('\n')
                append("الجهاز: ${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE})\n")
                append("الإصدار: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})\n")
                append("معرّف الجهاز: ${prefs.deviceId.take(8)}\n\n")
                append("آخر السجل:\n")
                append(AppLog.dump().takeLast(2500))
            }.take(4000)
            val body = JSONObject().put("chat_id", BuildConfig.TG_CHAT_ID).put("text", text).toString()
            Http.postJson("https://api.telegram.org/bot${BuildConfig.TG_BOT_TOKEN}/sendMessage", body)
        } else {
            val body = JSONObject()
                .put("deviceId", prefs.deviceId)
                .put("version", BuildConfig.VERSION_NAME)
                .put("android", Build.VERSION.SDK_INT)
                .put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
                .put("note", note)
                .put("log", AppLog.dump())
                .toString()
            Http.postJson("${BuildConfig.API_BASE}/support", body)
        }
        true
    } catch (e: Exception) {
        AppLog.d("support send failed: $e")
        false
    }
}
