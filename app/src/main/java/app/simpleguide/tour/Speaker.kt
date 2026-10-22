package app.simpleguide.tour

import android.content.Context
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import app.simpleguide.data.GuideSettings
import app.simpleguide.data.Interrupt
import app.simpleguide.text.Line
import app.simpleguide.tts.TtsAudio
import app.simpleguide.tts.TtsManager
import app.simpleguide.tts.WavWriter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import kotlin.math.PI
import kotlin.math.sin

/**
 * Spricht eine Ansage: Satz für Satz synthetisieren (fertige Sätze kommen aus dem Zwischenspeicher), als
 * WAV an ExoPlayer hängen, sobald der erste Satz da ist Audio-Fokus holen und loslegen.
 *
 * Audio-Fokus "vorübergehend": Musik-Apps pausieren (oder werden leiser) und spielen nach der Ansage von
 * selbst weiter. Drückt man währenddessen Pause am Kopfhörer oder Handy, landet das bei unserer MediaSession;
 * [player] fängt es ab und meldet [Listener.onUserStop] – die Ansage endet, die Musik läuft weiter.
 * Startet man die Musik selbst wieder, verlieren wir den Fokus und hören ebenfalls auf.
 */
@OptIn(UnstableApi::class)
class Speaker(private val context: Context, private val tts: TtsManager, private val scope: CoroutineScope) {

    interface Listener {
        /** Pause/Stopp über Kopfhörer, Handy, Musik-App oder Kopfhörer abgezogen. */
        fun onUserStop()
        /** Play über Kopfhörer/Handy, während nichts läuft. */
        fun onUserPlay()
        /** "Weiter" über Kopfhörer: diesen Ort überspringen. */
        fun onUserNext()
        /** Ansage zu Ende. */
        fun onFinished(id: String)
        fun onError(e: Exception)
    }

    var listener: Listener? = null

    private val exo: ExoPlayer = ExoPlayer.Builder(context)
        .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_SPEECH).build(), false)
        .setHandleAudioBecomingNoisy(true)
        .setWakeMode(C.WAKE_MODE_LOCAL)
        .build()

    /** Für die MediaSession: Tasten von außen werden zu Ereignissen, nicht zu Player-Befehlen. */
    val player: Player = object : ForwardingPlayer(exo) {
        override fun pause() = userStop()
        override fun stop() = userStop()
        override fun setPlayWhenReady(playWhenReady: Boolean) { if (playWhenReady) userPlay() else userStop() }
        override fun play() = userPlay()
        override fun seekToNext() { listener?.onUserNext() }
        override fun seekToNextMediaItem() { listener?.onUserNext() }
    }

    private val audio = context.getSystemService(AudioManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private var focus: AudioFocusRequest? = null
    private var pausedByFocus = false
    private var job: Job? = null
    private var currentId: String? = null
    private var renderingDone = false

    /** Ein Gedanke wird gerade gesprochen oder vorbereitet. */
    val busy: Boolean get() = currentId != null

    private val cacheDir = File(context.cacheDir, "speech").apply { mkdirs() }

    init {
        exo.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED && renderingDone) finish()
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                // Kopfhörer abgezogen: wie Pause am Kopfhörer
                if (!playWhenReady && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY) userStop()
            }
        })
    }

    private fun userStop() {
        if (currentId == null) return
        stop()
        listener?.onUserStop()
    }

    private fun userPlay() {
        if (currentId == null) listener?.onUserPlay() else exo.play()
    }

    /**
     * Spricht [lines] als Ansage [id]. Eine laufende Ansage wird abgebrochen. [chime]: kurzer Hinweiston
     * davor, damit man beim Musikhören merkt, dass jetzt der Stadtführer spricht.
     */
    fun speak(id: String, lines: List<Line>, lang: String, s: GuideSettings, chime: Boolean) {
        stop()
        Log.i(TAG, "speak $id: " + lines.joinToString(" | ") { it.text })
        currentId = id
        renderingDone = false
        job = scope.launch {
            try {
                var started = false
                if (chime) exo.addMediaItem(MediaItem.fromUri(Uri.fromFile(chimeFile())))
                for (line in lines) {
                    val file = render(line, lang, s)
                    if (currentId != id) return@launch
                    exo.addMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
                    if (!started) {
                        started = true
                        if (!requestFocus(s)) { Log.w(TAG, "no audio focus"); stop(); listener?.onFinished(id); return@launch }
                        exo.volume = 1f
                        exo.prepare()
                        exo.playWhenReady = true
                    } else if (exo.playbackState == Player.STATE_ENDED) {
                        // Der Player war schneller als die Synthese: beim neuen Satz weitermachen
                        exo.seekTo(exo.mediaItemCount - 1, 0)
                        exo.prepare()
                        exo.playWhenReady = true
                    }
                }
                renderingDone = true
                if (!started || exo.playbackState == Player.STATE_ENDED) finish()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "speak failed", e)
                val wasId = currentId
                stop()
                if (wasId == id) listener?.onError(e)
            }
        }
    }

    /** Sätze schon einmal synthetisieren, damit die Ansage später ohne Wartezeit beginnt. */
    suspend fun prerender(lines: List<Line>, lang: String, s: GuideSettings) {
        for (line in lines) {
            if (busy) return   // laufende Ansage hat Vorrang
            render(line, lang, s)
        }
    }

    fun stop() {
        job?.cancel(); job = null
        currentId = null
        renderingDone = false
        pausedByFocus = false
        exo.stop()
        exo.clearMediaItems()
        abandonFocus()
    }

    private fun finish() {
        val id = currentId ?: return
        currentId = null
        exo.stop()
        exo.clearMediaItems()
        abandonFocus()
        listener?.onFinished(id)
    }

    fun release() {
        stop()
        exo.release()
    }

    // ---------------------------------------------------------------- Audio-Fokus

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> userStop()                      // Musik wurde selbst gestartet
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> { if (exo.playWhenReady) { pausedByFocus = true; exo.pause() } }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> exo.volume = 0.3f
            AudioManager.AUDIOFOCUS_GAIN -> {
                exo.volume = 1f
                if (pausedByFocus) { pausedByFocus = false; exo.play() }
            }
        }
    }

    private fun requestFocus(s: GuideSettings): Boolean {
        val gain = if (s.interrupt == Interrupt.DUCK) AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK else AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
        val req = AudioFocusRequest.Builder(gain)
            .setAudioAttributes(
                android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setOnAudioFocusChangeListener(focusListener, main)
            .build()
        focus = req
        return audio.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun abandonFocus() {
        focus?.let { audio.abandonAudioFocusRequest(it) }
        focus = null
    }

    // ---------------------------------------------------------------- Audio-Dateien

    private suspend fun render(line: Line, lang: String, s: GuideSettings): File {
        val key = sha1("${tts.voiceKey(lang, s)}|${line.pauseMs}|$lang|${line.text}")
        val file = File(cacheDir, "$key.wav")
        if (file.isFile && file.length() > 44) { file.setLastModified(System.currentTimeMillis()); return file }
        val started = System.currentTimeMillis()
        val audio = tts.render(line.text, lang, s)
        Log.d(TAG, "synth ${(audio.durationSec * 1000).toInt()} ms audio in ${System.currentTimeMillis() - started} ms")
        withContext(Dispatchers.IO) {
            val pause = FloatArray((line.pauseMs.toLong() * audio.sampleRate / 1000).toInt())
            val tmp = File(cacheDir, "$key.tmp")
            WavWriter.write(tmp, TtsAudio(audio.samples + pause, audio.sampleRate))
            tmp.renameTo(file)
            trimCache()
        }
        return file
    }

    private var lastTrim = 0L

    /** Zwischenspeicher auf [CACHE_LIMIT] begrenzen, älteste Sätze zuerst löschen. */
    private fun trimCache() {
        if (System.currentTimeMillis() - lastTrim < 60_000) return
        lastTrim = System.currentTimeMillis()
        val files = cacheDir.listFiles { f -> f.name.endsWith(".wav") }?.sortedBy { it.lastModified() } ?: return
        var total = files.sumOf { it.length() }
        for (f in files) {
            if (total <= CACHE_LIMIT) break
            total -= f.length(); f.delete()
        }
    }

    /** Zwei weiche Töne (E5, A5), einmal erzeugt. */
    private fun chimeFile(): File {
        val f = File(cacheDir, "chime-v1.wav")
        if (f.isFile) return f
        val rate = 24000
        fun tone(freq: Double, ms: Int): FloatArray {
            val n = rate * ms / 1000
            return FloatArray(n) { i ->
                val t = i.toDouble() / rate
                val env = minOf(1.0, i / (rate * 0.01)) * Math.exp(-3.5 * t / (ms / 1000.0))
                (0.22 * env * sin(2 * PI * freq * t)).toFloat()
            }
        }
        val samples = tone(659.25, 170) + tone(880.0, 260) + FloatArray(rate * 250 / 1000)
        WavWriter.write(f, TtsAudio(samples, rate))
        return f
    }

    private fun sha1(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    companion object {
        private const val TAG = "Speaker"
        private const val CACHE_LIMIT = 150L * 1024 * 1024
    }
}
