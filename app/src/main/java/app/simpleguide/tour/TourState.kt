package app.simpleguide.tour

import app.simpleguide.data.Place
import app.simpleguide.geo.LatLon
import app.simpleguide.geo.Side
import kotlinx.coroutines.flow.MutableStateFlow

/** Ort in der Liste "In der Nähe". */
data class NearbyItem(val place: Place, val distanceM: Int, val side: Side, val heard: Boolean, val inRange: Boolean)

/** Was gerade erzählt wird. */
data class NowPlaying(val place: Place?, val title: String, val hasMore: Boolean, val text: List<String> = emptyList())

/** Eine Ansage im Verlauf: was gesagt wurde (oder ohne Stimme gesagt worden wäre). */
data class Spoken(val title: String, val lines: List<String>, val at: Long, val voiced: Boolean)

/**
 * Zustand der laufenden Tour für die Oberfläche (gleicher Prozess, kein IPC nötig).
 */
object TourState {
    val running = MutableStateFlow(false)
    val position = MutableStateFlow<LatLon?>(null)
    val accuracyM = MutableStateFlow<Float?>(null)
    val heading = MutableStateFlow<Double?>(null)
    val street = MutableStateFlow<String?>(null)
    val nearby = MutableStateFlow<List<NearbyItem>>(emptyList())
    val speaking = MutableStateFlow<NowPlaying?>(null)
    /** Zuletzt erzählter oder übersprungener Ort, für "Nochmal". */
    val last = MutableStateFlow<Place?>(null)
    /** Kurze Statusmeldung (wartet auf GPS, lädt Artikel, keine Stimme …), als String-Ressource. */
    val status = MutableStateFlow<Status>(Status.IDLE)

    /** Alle Ansagen seit dem Start der App, neueste zuerst; bleibt nach dem Ende der Tour zum Nachlesen. */
    val history = MutableStateFlow<List<Spoken>>(emptyList())

    fun remember(title: String, lines: List<String>, voiced: Boolean) {
        history.value = (listOf(Spoken(title, lines, System.currentTimeMillis(), voiced)) + history.value).take(HISTORY_SIZE)
    }

    private const val HISTORY_SIZE = 60

    enum class Status { IDLE, WAITING_GPS, LOADING, READY, OFFLINE_EMPTY, NO_VOICE, NO_PERMISSION }

    fun reset() {
        running.value = false
        speaking.value = null
        nearby.value = emptyList()
        status.value = Status.IDLE
    }
}
