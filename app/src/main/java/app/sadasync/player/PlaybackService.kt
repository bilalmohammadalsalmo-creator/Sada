package app.sadasync.player

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.ServiceCompat
import app.sadasync.R
import app.sadasync.util.Notifications

/** خدمة أمامية فقط لإبقاء العملية حيّة أثناء التشغيل؛ المشغّل نفسه في PlayerController. */
class PlaybackService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val n = Notifications.build(
            this, getString(R.string.notif_playback_title), getString(R.string.notif_playback_text)
        )
        ServiceCompat.startForeground(this, 1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        return START_NOT_STICKY
    }
}
