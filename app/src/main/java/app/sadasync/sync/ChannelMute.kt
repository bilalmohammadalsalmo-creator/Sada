package app.sadasync.sync

import android.content.Context
import android.media.AudioManager
import app.sadasync.data.Prefs
import app.sadasync.util.AppLog

/**
 * يكتم صوت القناة بإنزال صوت الوسائط (STREAM_MUSIC) إلى صفر، بينما يعمل التعليق على مسار المنبّه
 * فيبقى مسموعًا. مستوى الصوت السابق يُحفظ ليُستعاد حتى لو أُغلق التطبيق وهو كاتم.
 */
class ChannelMute(ctx: Context, private val prefs: Prefs) {
    private val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    /** يعيد false إن لم يستطع الجهاز تنفيذ الطلب. */
    fun set(muted: Boolean): Boolean = try {
        val stream = AudioManager.STREAM_MUSIC
        if (muted) {
            if (am.isVolumeFixed) {
                false
            } else {
                val cur = am.getStreamVolume(stream)
                if (cur > 0) {
                    prefs.savedChannelVolume = cur
                    am.setStreamVolume(stream, 0, 0)
                }
                am.getStreamVolume(stream) == 0
            }
        } else {
            val saved = prefs.savedChannelVolume
            if (saved > 0) {
                if (am.getStreamVolume(stream) == 0) am.setStreamVolume(stream, saved, 0)
                prefs.savedChannelVolume = -1
            }
            true
        }
    } catch (e: Exception) {
        AppLog.d("channel mute failed: $e")
        false
    }
}
