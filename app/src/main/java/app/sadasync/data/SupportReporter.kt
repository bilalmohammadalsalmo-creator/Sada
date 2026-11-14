package app.sadasync.data

import android.os.Build
import app.sadasync.BuildConfig
import app.sadasync.util.AppLog
import app.sadasync.util.Http
import org.json.JSONObject

/** يرسل بلاغ دعم (سجل التطبيق + معلومات الجهاز) إلى الخادم بعد موافقة المستخدم. */
object SupportReporter {
    suspend fun send(prefs: Prefs, note: String = ""): Boolean = try {
        val body = JSONObject()
            .put("deviceId", prefs.deviceId)
            .put("version", BuildConfig.VERSION_NAME)
            .put("android", Build.VERSION.SDK_INT)
            .put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
            .put("note", note)
            .put("log", AppLog.dump())
            .toString()
        Http.postJson("${BuildConfig.API_BASE}/support", body)
        true
    } catch (e: Exception) {
        AppLog.d("support send failed: $e")
        false
    }
}
