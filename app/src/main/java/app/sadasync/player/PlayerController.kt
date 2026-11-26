@file:OptIn(UnstableApi::class)

package app.sadasync.player

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.MediaCodecAudioRenderer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import app.sadasync.data.AudioSource
import app.sadasync.data.Prefs
import app.sadasync.data.SourceGroups
import app.sadasync.data.SourceRepository
import app.sadasync.R
import androidx.media3.datasource.RawResourceDataSource
import app.sadasync.sync.EnvelopeStore
import app.sadasync.util.AppLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlin.math.min

data class PlayerUi(
    val playing: Boolean = false,
    val source: AudioSource? = null,
    val delayMs: Int = 0,
    val myAudio: Boolean = true,
    val status: String = "",
    val syncStatus: String = "",
    val volumePct: Int = 100,
)

/**
 * غلاف حول ExoPlayer. كل الاستدعاءات على الخيط الرئيسي (setDelay يحوّل نفسه إليه).
 * - الصوت يُشغَّل بدون طلب Audio Focus حتى لا يوقف صوت تطبيق القنوات.
 * - التأخير الموجب عبر DelayAudioProcessor، والسالب (تقديم) عبر seek في المصادر القابلة للتقديم.
 */
class PlayerController(
    private val app: Context,
    private val prefs: Prefs,
    private val sources: SourceRepository,
) {
    companion object {
        const val MIN_DELAY_MS = -10_000
        const val MAX_DELAY_MS = DelayAudioProcessor.MAX_DELAY_MS
    }

    /** يُستدعى عند أي اضطراب في صوتنا (بدء تشغيل، تحميل، استئناف) ليعيد المزامنة قياسه. */
    @Volatile
    var onDisturbed: (() -> Unit)? = null

    val delayProcessor = DelayAudioProcessor()
    val refEnvelope = EnvelopeStore()

    private val handler = Handler(Looper.getMainLooper())
    private var player: ExoPlayer? = null

    private val _ui = MutableStateFlow(
        PlayerUi(source = sources.find(prefs.lastSourceId), delayMs = prefs.delayMs, volumePct = prefs.myVolumePct)
    )
    val ui: StateFlow<PlayerUi> = _ui

    init {
        delayProcessor.envelopeSink = refEnvelope
        delayProcessor.delayMs = prefs.delayMs.coerceAtLeast(0)
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else handler.post(block)
    }

    private fun ensurePlayer(): ExoPlayer {
        player?.let { return it }

        val sink = DefaultAudioSink.Builder(app)
            .setAudioProcessors(arrayOf<AudioProcessor>(delayProcessor))
            .build()
        // مشغّل صوت فقط: لا نحتاج مصيّر فيديو
        val renderers = RenderersFactory { eventHandler, _, audioListener, _, _ ->
            arrayOf<Renderer>(
                MediaCodecAudioRenderer(app, MediaCodecSelector.DEFAULT, eventHandler, audioListener, sink)
            )
        }
        val http = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setUserAgent("SadaSync/1.0")
        val p = ExoPlayer.Builder(app, renderers)
            .setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(app, http)))
            .build()
        p.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                .build(),
            /* handleAudioFocus = */ false,
        )
        p.setWakeMode(C.WAKE_MODE_NETWORK)
        p.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                when (state) {
                    Player.STATE_BUFFERING -> {
                        _ui.update { it.copy(status = "جارٍ التحميل…") }
                        onDisturbed?.invoke()
                    }
                    Player.STATE_READY -> {
                        _ui.update { it.copy(status = "يعمل") }
                        onDisturbed?.invoke()
                    }
                    Player.STATE_ENDED -> {
                        _ui.update { it.copy(playing = false, status = "انتهى") }
                        stopService()
                    }
                    else -> Unit
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                AppLog.d("player error: ${error.errorCodeName} ${error.message}")
                _ui.update { it.copy(playing = false, status = "تعذّر التشغيل (${error.errorCodeName})") }
                stopService()
            }
        })
        player = p
        return p
    }

    // ---------- التحكم ----------

    fun play(src: AudioSource) = onMain {
        val p = ensurePlayer()
        AppLog.d("play ${src.name}")
        val silent = src.url == SourceGroups.SILENCE_URL
        p.setMediaItem(
            if (silent) MediaItem.fromUri(RawResourceDataSource.buildRawResourceUri(R.raw.silence))
            else MediaItem.fromUri(src.url)
        )
        p.repeatMode = if (silent) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
        p.volume = volFor(_ui.value.myAudio, _ui.value.volumePct)
        p.prepare()
        p.playWhenReady = true
        prefs.lastSourceId = src.id
        _ui.update { it.copy(playing = true, source = src, status = "جارٍ التحميل…") }
        onDisturbed?.invoke()
        startService()
    }

    fun stop() = onMain {
        player?.let {
            it.stop()
            it.clearMediaItems()
        }
        _ui.update { it.copy(playing = false, status = "متوقف") }
        stopService()
    }

    fun toggle() {
        if (_ui.value.playing) stop()
        else (_ui.value.source ?: sources.all.value.firstOrNull())?.let { play(it) }
    }

    /** يختار مصدرًا دون تشغيله (أو يبدّل التشغيل إليه إن كان يعمل). */
    fun select(src: AudioSource) {
        if (_ui.value.playing) {
            play(src)
        } else {
            prefs.lastSourceId = src.id
            _ui.update { it.copy(source = src) }
        }
    }

    fun cycleSource(dir: Int) {
        val list = sources.all.value
        if (list.isEmpty()) return
        val cur = list.indexOfFirst { it.id == _ui.value.source?.id }
        val next = if (cur < 0) 0 else (cur + dir + list.size) % list.size
        select(list[next])
    }

    fun nudgeDelay(deltaMs: Int) = setDelay(_ui.value.delayMs + deltaMs)

    fun setDelay(ms: Int) = onMain {
        val new = ms.coerceIn(MIN_DELAY_MS, MAX_DELAY_MS)
        val old = _ui.value.delayMs
        if (new == old) return@onMain

        // الجزء السالب من التأخير = تقديم الصوت بالقفز للأمام في المصدر
        val advance = -min(new, 0) - (-min(old, 0))
        val p = player
        if (advance != 0 && p != null) {
            if (p.isCurrentMediaItemSeekable) {
                p.seekTo((p.currentPosition + advance).coerceAtLeast(0))
            } else {
                _ui.update { it.copy(syncStatus = "هذا المصدر لا يدعم تقديم الصوت") }
            }
        }
        delayProcessor.delayMs = new.coerceAtLeast(0)
        prefs.delayMs = new
        _ui.update { it.copy(delayMs = new) }
    }

    /** منحنى مريح للأذن: مربّع النسبة (50% ≈ -12 dB). */
    private fun volFor(on: Boolean, pct: Int): Float = if (on) (pct / 100f) * (pct / 100f) else 0f

    fun setMyAudio(on: Boolean) = onMain {
        player?.volume = volFor(on, _ui.value.volumePct)
        _ui.update { it.copy(myAudio = on) }
    }

    fun setVolumePct(pct: Int) = onMain {
        val v = pct.coerceIn(0, 100)
        prefs.myVolumePct = v
        _ui.update { it.copy(volumePct = v, myAudio = v > 0 && (it.myAudio || it.volumePct != v)) }
        player?.volume = volFor(_ui.value.myAudio, v)
    }

    fun nudgeVolume(delta: Int) = setVolumePct(_ui.value.volumePct + delta)

    fun toggleMyAudio() = setMyAudio(!_ui.value.myAudio)

    fun setSyncStatus(text: String) {
        _ui.update { it.copy(syncStatus = text) }
    }

    private fun startService() {
        try {
            ContextCompat.startForegroundService(app, Intent(app, PlaybackService::class.java))
        } catch (e: Exception) {
            // بعض الأجهزة تمنع بدء خدمة أمامية من الخلفية؛ التشغيل يستمر بدونها
            AppLog.d("foreground service not started: $e")
        }
    }

    private fun stopService() {
        app.stopService(Intent(app, PlaybackService::class.java))
    }
}
