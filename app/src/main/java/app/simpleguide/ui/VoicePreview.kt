package app.simpleguide.ui

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import app.simpleguide.data.GuideSettings
import app.simpleguide.tts.Pcm
import app.simpleguide.tts.TtsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Hörprobe in den Einstellungen: ein Satz direkt über AudioTrack, ohne die Tour zu starten. */
object VoicePreview {
    private var track: AudioTrack? = null

    suspend fun play(tts: TtsManager, lang: String, s: GuideSettings) {
        val text = if (lang == "en") "On your right is St Paul's Cathedral. It was built between 1675 and 1710."
        else "Wenn du nach rechts schaust, siehst du den Bertoldsbrunnen. Er ist ein Denkmal in der Altstadt von Freiburg im Breisgau."
        val audio = tts.render(text, lang, s)
        withContext(Dispatchers.IO) {
            val pcm = Pcm.toInt16(audio.samples)
            stop()
            val t = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(audio.sampleRate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(pcm.size)
                .build()
            t.write(pcm, 0, pcm.size)
            t.play()
            track = t
        }
    }

    fun stop() {
        track?.let { runCatching { it.stop() }; it.release() }
        track = null
    }
}
