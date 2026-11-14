package app.sadasync.sync

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import app.sadasync.App
import app.sadasync.util.AppLog

/**
 * شفافة وبلا واجهة: تطلب إذن تسجيل الصوت ثم إذن التقاط صوت النظام (MediaProjection)
 * وتسلّم النتيجة إلى CaptureService. تُفتح من الصفحة الرئيسية أو من اللوحة العائمة.
 */
class CaptureProjectionActivity : ComponentActivity() {

    private val projectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.data != null) {
                val i = Intent(this, CaptureService::class.java)
                    .putExtra(CaptureService.EXTRA_CODE, result.resultCode)
                    .putExtra(CaptureService.EXTRA_DATA, result.data)
                ContextCompat.startForegroundService(this, i)
            } else {
                App.instance.player.setSyncStatus("لم تُمنح صلاحية التقاط الصوت")
            }
            finish()
        }

    private val micLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) requestProjection() else {
                App.instance.player.setSyncStatus("يلزم إذن تسجيل الصوت")
                finish()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            App.instance.player.setSyncStatus("المزامنة التلقائية تتطلب أندرويد 10 أو أحدث")
            finish()
            return
        }
        val hasMic = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (hasMic) requestProjection() else micLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    private fun requestProjection() {
        try {
            val mpm = getSystemService(MediaProjectionManager::class.java)
            projectionLauncher.launch(mpm.createScreenCaptureIntent())
        } catch (e: Exception) {
            // بعض صناديق التلفاز لا تحتوي شاشة طلب الإذن
            AppLog.d("projection unsupported: $e")
            App.instance.player.setSyncStatus("جهازك لا يدعم التقاط الصوت؛ استخدم الضبط اليدوي")
            finish()
        }
    }
}
