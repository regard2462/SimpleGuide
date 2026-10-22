package app.simpleguide.tts

import android.util.Log
import app.simpleguide.data.GuideSettings
import app.simpleguide.speech.Normalizer
import app.simpleguide.text.Segmenter
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

/**
 * Wählt die Stimme für die Sprache der Inhalte, lädt das Modell bei Bedarf und serialisiert alle
 * Synthese-Aufrufe auf einen Thread. Ein Satz wird vor der Synthese normalisiert (Zahlen, Daten, Abkürzungen)
 * und danach von der Stille befreit, die das Modell um ihn legt; die Pausen setzt der Sprecher selbst.
 */
class TtsManager(private val models: ModelManager) {
    private val dispatcher = Executors.newSingleThreadExecutor { r -> Thread(r, "sherpa-tts") }.asCoroutineDispatcher()
    private val mutex = Mutex()
    private var engine: TtsEngine? = null

    private val _loading = MutableStateFlow<String?>(null)
    /** Name der Stimme, die gerade geladen wird, sonst null. */
    val loading: StateFlow<String?> = _loading

    companion object {
        /** Pause zwischen den Stücken eines geteilten Satzes, etwa ein Komma. */
        const val JOIN_GAP_MS = 140
    }

    class NoModelException : Exception("No voice model installed")

    data class Voice(val pack: VoicePack, val sid: Int)

    /** Gewählte Stimme der Sprache; ist sie nicht installiert, ein anderes installiertes Paket derselben Sprache. */
    fun voiceFor(lang: String, s: GuideSettings): Voice? {
        val installed = models.installed.value
        val (packId, sid) = if (lang == "en") s.packEn to s.sidEn else s.packDe to s.sidDe
        VoicePacks.byId(packId)?.takeIf { it.id in installed && it.supports(lang) }?.let { return Voice(it, sid.coerceIn(0, it.voices.lastIndex)) }
        return VoicePacks.forLang(lang).filter { it.id in installed }.sortedByDescending { it.recommended }.firstOrNull()?.let { Voice(it, 0) }
    }

    /** Schlüssel, unter dem fertiges Audio zwischengespeichert werden darf (ändert sich mit Stimme und Tempo). */
    fun voiceKey(lang: String, s: GuideSettings): String =
        voiceFor(lang, s)?.let { "${it.pack.id}|${it.sid}|${s.voiceSpeed}" } ?: "none"

    /** Einen Satz sprechen: normalisieren, synthetisieren, Modellstille abschneiden. */
    suspend fun render(text: String, lang: String, s: GuideSettings): TtsAudio = mutex.withLock {
        val voice = voiceFor(lang, s) ?: throw NoModelException()
        val spoken = Normalizer.normalize(text, lang, espeak = voice.pack.engine != Engine.SUPERTONIC)
        withContext(dispatcher) {
            val e = engineFor(voice, s)
            val speakLang = if (voice.pack.supports(lang)) lang else voice.pack.langs.first()
            // Sehr lange Sätze an Kommas teilen (Kokoro hat ein Token-Limit, alle Stimmen klingen kürzer besser)
            val parts = Segmenter.splitLong(spoken).map { piece ->
                val a = e.synthesize(piece, s.voiceSpeed, voice.sid, speakLang)
                TtsAudio(Pcm.trimSilence(a.samples, a.sampleRate), a.sampleRate)
            }
            val rate = parts.first().sampleRate
            val gap = FloatArray(rate * JOIN_GAP_MS / 1000)
            val joined = parts.map { p -> if (p.sampleRate == rate) p.samples else Pcm.resample(p.samples, p.sampleRate, rate) }
                .reduce { acc, next -> acc + gap + next }
            TtsAudio(joined, rate)
        }
    }

    private fun engineFor(v: Voice, s: GuideSettings): TtsEngine {
        val key = "${v.pack.id}|${s.threads}"
        engine?.takeIf { it.key == key }?.let { return it }
        // Nur ein Modell im Speicher: unterwegs zählt jedes Megabyte
        engine?.release(); engine = null
        val files = models.resolve(v.pack) ?: throw NoModelException()
        _loading.value = v.pack.name
        Log.i("TtsManager", "Loading $key")
        try {
            return SherpaTtsEngine(v.pack, files, s.threads, key).also { engine = it }
        } finally {
            _loading.value = null
        }
    }

    suspend fun release() = mutex.withLock {
        withContext(dispatcher) { engine?.release(); engine = null }
    }
}
