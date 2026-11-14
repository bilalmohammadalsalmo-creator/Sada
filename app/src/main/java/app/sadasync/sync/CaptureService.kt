package app.sadasync.sync

import android.app.Activity
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.ServiceCompat
import androidx.core.content.IntentCompat
import app.sadasync.R
import app.sadasync.util.AppLog
import app.sadasync.util.Notifications
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** يحتفظ بجلسة MediaProjection حيّة (مطلوبة لالتقاط صوت القناة). */
object CaptureHolder {
    private val _active = MutableStateFlow(false)

    /** true حين تكون جلسة الالتقاط جاهزة (تستخدمها الواجهة لعرض الحالة). */
    val active: StateFlow<Boolean> = _active

    @Volatile
    var projection: MediaProjection? = null
        set(value) {
            field = value
            _active.value = value != null
        }
}

class CaptureService : Service() {
    companion object {
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        const val ACTION_STOP = "stop"
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        val n = Notifications.build(
            this, getString(R.string.notif_capture_title), getString(R.string.notif_capture_text)
        )
        // يجب بدء الخدمة الأمامية قبل الحصول على MediaProjection (أندرويد 14+)
        ServiceCompat.startForeground(this, 2, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)

        val code = intent?.getIntExtra(EXTRA_CODE, Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
        val data = intent?.let { IntentCompat.getParcelableExtra(it, EXTRA_DATA, Intent::class.java) }
        if (code != Activity.RESULT_OK || data == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            val mpm = getSystemService(MediaProjectionManager::class.java)
            val mp = mpm.getMediaProjection(code, data)
            mp.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    AppLog.d("projection stopped")
                    CaptureHolder.projection = null
                    stopSelf()
                }
            }, Handler(Looper.getMainLooper()))
            CaptureHolder.projection = mp
            AppLog.d("projection ready")
        } catch (e: Exception) {
            AppLog.d("projection failed: $e")
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        CaptureHolder.projection?.stop()
        CaptureHolder.projection = null
        super.onDestroy()
    }
}
