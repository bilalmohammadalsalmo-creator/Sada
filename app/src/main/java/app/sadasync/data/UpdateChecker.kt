package app.sadasync.data

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import app.sadasync.BuildConfig
import app.sadasync.util.Http
import org.json.JSONObject
import java.io.File

data class UpdateInfo(val versionCode: Int, val versionName: String, val apkUrl: String, val notes: String)

object UpdateChecker {
    /** يعيد معلومات التحديث إن وُجدت نسخة أحدث، وإلا null. */
    suspend fun check(): UpdateInfo? {
        val o = JSONObject(Http.get("${BuildConfig.API_BASE}/update.json"))
        val info = UpdateInfo(
            versionCode = o.optInt("versionCode", 0),
            versionName = o.optString("versionName"),
            apkUrl = o.optString("apkUrl"),
            notes = o.optString("notes"),
        )
        return if (info.versionCode > BuildConfig.VERSION_CODE && info.apkUrl.isNotEmpty()) info else null
    }

    fun downloadAndInstall(context: Context, info: UpdateInfo) {
        val app = context.applicationContext
        val dm = app.getSystemService(DownloadManager::class.java)
        val dest = File(app.getExternalFilesDir(null), "update.apk").also { it.delete() }
        val id = dm.enqueue(
            DownloadManager.Request(Uri.parse(info.apkUrl))
                .setDestinationUri(Uri.fromFile(dest))
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
        )
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                if (i.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1) != id) return
                c.unregisterReceiver(this)
                install(c, dest)
            }
        }
        ContextCompat.registerReceiver(
            app, receiver, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
            ContextCompat.RECEIVER_EXPORTED,
        )
    }

    private fun install(c: Context, file: File) {
        val uri = FileProvider.getUriForFile(c, "${c.packageName}.fileprovider", file)
        c.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
