package app.sadasync.data

import app.sadasync.BuildConfig
import app.sadasync.util.AppLog
import app.sadasync.util.Http
import org.json.JSONObject

/** تفعيل الجهاز برمز دعوة من 6 أرقام صالح لجهاز واحد (يتحقق منه الخادم). */
class InviteRepository(private val prefs: Prefs) {

    suspend fun activate(code: String): Result<Unit> = runCatching {
        require(code.length == 6) { "الرمز 6 أرقام" }

        if (BuildConfig.API_BASE.contains("example.com")) {
            // لم يُضبط الخادم بعد: نسمح بأي رمز في نسخة التطوير فقط
            check(BuildConfig.DEBUG) { "لم يتم ضبط عنوان الخادم في التطبيق" }
            prefs.activated = true
            return@runCatching
        }

        val body = JSONObject().put("code", code).put("deviceId", prefs.deviceId).toString()
        val res = JSONObject(Http.postJson("${BuildConfig.API_BASE}/activate", body))
        check(res.optBoolean("ok")) { res.optString("error", "رمز غير صحيح") }
        AppLog.d("activated")
        prefs.activated = true
    }
}
