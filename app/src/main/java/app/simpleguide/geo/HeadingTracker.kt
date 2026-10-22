package app.simpleguide.geo

/**
 * Gehrichtung aus den letzten Standorten. Das Handy steckt beim Gehen meist in der Tasche, ein Kompass
 * zeigt dann irgendwohin; verlässlich ist nur die Richtung, in die man sich bewegt.
 *
 * Gerechnet wird vom jüngsten Punkt zurück bis zu einem Punkt, der mindestens [minDistanceM] entfernt ist,
 * damit das GPS-Zittern im Stehen keine Richtung vortäuscht. Liefert das GPS selbst einen Kurs bei
 * Gehgeschwindigkeit, zählt der.
 */
class HeadingTracker(
    private val minDistanceM: Double = 12.0,
    private val windowMs: Long = 45_000,
    private val keepHeadingMs: Long = 30_000,
) {
    data class Fix(val p: LatLon, val timeMs: Long, val accuracyM: Float = 10f, val gpsBearing: Float? = null, val speed: Float? = null)

    private val fixes = ArrayDeque<Fix>()
    private var lastHeading: Double? = null
    private var lastHeadingAt = 0L

    val latest: Fix? get() = fixes.lastOrNull()

    fun add(fix: Fix) {
        // Sehr ungenaue Punkte (Funkzellen-Ortung) verfälschen die Richtung
        if (fix.accuracyM > 60f && fixes.isNotEmpty()) return
        fixes.addLast(fix)
        while (fixes.size > 2 && fix.timeMs - fixes.first().timeMs > windowMs) fixes.removeFirst()
        compute(fix)?.let { lastHeading = it; lastHeadingAt = fix.timeMs }
    }

    private fun compute(now: Fix): Double? {
        if (now.gpsBearing != null && (now.speed ?: 0f) >= 0.9f) return now.gpsBearing.toDouble()
        val older = fixes.reversed().drop(1).firstOrNull { Geo.distance(it.p, now.p) >= minDistanceM } ?: return null
        return Geo.bearing(older.p, now.p)
    }

    /** Aktuelle Gehrichtung in Grad oder null (steht still, noch zu wenig Weg). */
    fun heading(nowMs: Long = latest?.timeMs ?: 0L): Double? =
        lastHeading?.takeIf { nowMs - lastHeadingAt <= keepHeadingMs }
}
