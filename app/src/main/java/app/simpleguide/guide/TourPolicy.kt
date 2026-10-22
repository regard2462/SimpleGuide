package app.simpleguide.guide

import app.simpleguide.data.Place
import app.simpleguide.geo.Geo
import app.simpleguide.geo.LatLon
import app.simpleguide.geo.Side
import app.simpleguide.text.Category
import app.simpleguide.text.Classifier

/** Ein Ort aus Sicht des Gehenden. */
data class Candidate(val place: Place, val distanceM: Double, val bearing: Double, val side: Side, val onStreet: Boolean = false) {
    companion object {
        fun of(place: Place, from: LatLon, heading: Double?): Candidate {
            val b = Geo.bearing(from, place.pos)
            return Candidate(place, Geo.distance(from, place.pos), b, Side.of(heading, b))
        }
    }
}

/**
 * Entscheidet, welcher Ort als Nächstes erzählt wird. Ohne Android-Abhängigkeiten, damit testbar.
 *
 * - Jeder Ort hat einen Auslöseradius: Grundradius × Art (Plätze, Parks und Kirchen größer) × Bekanntheit
 *   (lange Artikel = große, weithin sichtbare Bauwerke).
 * - Was schon gehört oder übersprungen wurde, kommt nicht wieder.
 * - Was deutlich hinter einem liegt, ist vorbei: Wer weitergeht, will nicht zurückschauen. Große Bauwerke
 *   (großer Radius) dürfen etwas weiter hinten liegen; ihr Koordinatenpunkt ist oft nur eine Ecke, etwa der Turm.
 * - Unter mehreren Kandidaten gewinnt der bedeutendste, bei gleicher Bedeutung der nähere.
 */
class TourPolicy(
    private val baseRadiusM: Int,
    private val categories: Set<Category>,
) {
    fun radiusFor(p: Place): Double {
        val fame = when {
            p.length > 60_000 -> 1.5
            p.length > 25_000 -> 1.25
            p.length > 10_000 -> 1.1
            else -> 1.0
        }
        return (baseRadiusM * p.category.radiusFactor * fame).coerceIn(20.0, 400.0)
    }

    fun allowed(p: Place) = p.category in categories

    /** Bis zu dieser Entfernung wird ein Ort hinter einem noch erzählt ("Wenn du dich umdrehst …"). */
    fun behindLimit(p: Place): Double = 30.0 + (radiusFor(p) - baseRadiusM).coerceAtLeast(0.0) * 0.6

    fun eligible(c: Candidate, heard: Set<Long>): Boolean {
        if (!allowed(c.place) || c.place.pageId in heard) return false
        // Artikelstummel haben nichts zu erzählen
        if (c.place.hasDetails && c.place.length in 1 until MIN_ARTICLE) return false
        if (c.distanceM > radiusFor(c.place)) return false
        // Straßen nur, wenn man auf ihnen geht: "50 Meter entfernt befindet sich die Hauptstraße" klingt falsch
        if (!c.onStreet && Classifier.isStreet(c.place.title) && c.distanceM > STREET_NEAR_M) return false
        // Schon vorbeigegangen: liegt hinter einem und nicht mehr direkt daneben
        if (c.side == Side.BEHIND && c.distanceM > behindLimit(c.place)) return false
        return true
    }

    fun score(c: Candidate): Double {
        val r = radiusFor(c.place)
        val closeness = 1.0 - (c.distanceM / r).coerceIn(0.0, 1.0) * 0.6
        val ahead = when (c.side) {
            Side.AHEAD, Side.AHEAD_LEFT, Side.AHEAD_RIGHT -> 1.05
            Side.LEFT, Side.RIGHT -> 1.1   // gerade auf Höhe: jetzt oder nie
            else -> 1.0
        }
        return c.place.importance * closeness * ahead
    }

    companion object {
        /** Ohne bekannten Straßennamen gilt man so nah am Artikelpunkt als "auf der Straße". */
        const val STREET_NEAR_M = 30.0
        /** Kürzere Artikel (Zeichen im Quelltext) werden nicht von selbst erzählt. */
        const val MIN_ARTICLE = 1200
    }

    fun pick(candidates: List<Candidate>, heard: Set<Long>): Candidate? =
        candidates.filter { eligible(it, heard) }.maxByOrNull { score(it) }

    /** Die nächsten Kandidaten, die demnächst drankommen könnten (zum Vorbereiten von Text und Audio). */
    fun upcoming(candidates: List<Candidate>, heard: Set<Long>, n: Int): List<Candidate> =
        candidates.filter { allowed(it.place) && it.place.pageId !in heard && it.distanceM <= radiusFor(it.place) * 2.2 }
            .sortedByDescending { score(it.copy(distanceM = (it.distanceM - radiusFor(it.place)).coerceAtLeast(0.0))) }
            .take(n)
}
