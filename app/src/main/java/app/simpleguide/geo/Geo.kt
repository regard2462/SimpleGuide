package app.simpleguide.geo

import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class LatLon(val lat: Double, val lon: Double)

/** Rechteck in Grad; [south] < [north], [west] < [east] (keine Datumsgrenze, für Städte reicht das). */
data class BBox(val south: Double, val west: Double, val north: Double, val east: Double) {
    fun contains(p: LatLon) = p.lat in south..north && p.lon in west..east
    val center get() = LatLon((south + north) / 2, (west + east) / 2)
    val heightM get() = Geo.distance(LatLon(south, west), LatLon(north, west))
    val widthM get() = Geo.distance(LatLon(center.lat, west), LatLon(center.lat, east))

    /** In vier gleich große Viertel teilen (für die Kachelsuche). */
    fun quarters(): List<BBox> {
        val c = center
        return listOf(
            BBox(c.lat, west, north, c.lon), BBox(c.lat, c.lon, north, east),
            BBox(south, west, c.lat, c.lon), BBox(south, c.lon, c.lat, east),
        )
    }

    /** In ein Gitter aus Kacheln zerlegen, deren Seiten höchstens [maxSideM] lang sind. */
    fun tiles(maxSideM: Double): List<BBox> {
        val rows = maxOf(1, Math.ceil(heightM / maxSideM).toInt())
        val cols = maxOf(1, Math.ceil(widthM / maxSideM).toInt())
        val dLat = (north - south) / rows
        val dLon = (east - west) / cols
        return (0 until rows).flatMap { r ->
            (0 until cols).map { c -> BBox(south + r * dLat, west + c * dLon, south + (r + 1) * dLat, west + (c + 1) * dLon) }
        }
    }
}

object Geo {
    const val EARTH_RADIUS = 6_371_008.8

    private fun rad(d: Double) = Math.toRadians(d)

    /** Großkreisentfernung in Metern. */
    fun distance(a: LatLon, b: LatLon): Double {
        val dLat = rad(b.lat - a.lat)
        val dLon = rad(b.lon - a.lon)
        val h = sin(dLat / 2).let { it * it } + cos(rad(a.lat)) * cos(rad(b.lat)) * sin(dLon / 2).let { it * it }
        return 2 * EARTH_RADIUS * asin(sqrt(h.coerceIn(0.0, 1.0)))
    }

    /** Kurs von [from] nach [to] in Grad, 0 = Norden, 90 = Osten. */
    fun bearing(from: LatLon, to: LatLon): Double {
        val y = sin(rad(to.lon - from.lon)) * cos(rad(to.lat))
        val x = cos(rad(from.lat)) * sin(rad(to.lat)) - sin(rad(from.lat)) * cos(rad(to.lat)) * cos(rad(to.lon - from.lon))
        return (Math.toDegrees(atan2(y, x)) + 360) % 360
    }

    /** Winkel von [from] nach [to] im Bereich (-180, 180]; positiv = im Uhrzeigersinn (rechts). */
    fun angleDiff(from: Double, to: Double): Double {
        var d = (to - from) % 360
        if (d <= -180) d += 360
        if (d > 180) d -= 360
        return d
    }

    /** Rechteck um [c] mit [radiusM] Abstand zu jeder Seite. */
    fun bbox(c: LatLon, radiusM: Double): BBox {
        val dLat = Math.toDegrees(radiusM / EARTH_RADIUS)
        val dLon = Math.toDegrees(radiusM / (EARTH_RADIUS * cos(rad(c.lat)).coerceAtLeast(0.01)))
        return BBox(c.lat - dLat, c.lon - dLon, c.lat + dLat, c.lon + dLon)
    }

    /**
     * Abstand von [p] zur Strecke [a]–[b] in Metern. Für Straßen auf wenigen hundert Metern genügt eine
     * ebene Näherung um [p].
     */
    fun distanceToSegment(p: LatLon, a: LatLon, b: LatLon): Double {
        val k = cos(rad(p.lat))
        fun x(q: LatLon) = rad(q.lon - p.lon) * k * EARTH_RADIUS
        fun y(q: LatLon) = rad(q.lat - p.lat) * EARTH_RADIUS
        val ax = x(a); val ay = y(a); val bx = x(b); val by = y(b)
        val dx = bx - ax; val dy = by - ay
        val len2 = dx * dx + dy * dy
        val t = if (len2 == 0.0) 0.0 else ((-ax) * dx + (-ay) * dy) / len2
        val tc = t.coerceIn(0.0, 1.0)
        val cx = ax + tc * dx; val cy = ay + tc * dy
        return sqrt(cx * cx + cy * cy)
    }
}

/** Wo etwas vom Gehenden aus liegt. */
enum class Side { AHEAD, AHEAD_RIGHT, RIGHT, BEHIND, LEFT, AHEAD_LEFT, UNKNOWN;

    companion object {
        /** [heading]: Gehrichtung; null, wenn sie nicht bekannt ist (Stehen, kein GPS-Kurs). */
        fun of(heading: Double?, bearingToPlace: Double): Side {
            heading ?: return UNKNOWN
            val d = Geo.angleDiff(heading, bearingToPlace)
            return when {
                abs(d) <= 25 -> AHEAD
                d in 25.0..65.0 -> AHEAD_RIGHT
                d in -65.0..-25.0 -> AHEAD_LEFT
                d in 65.0..130.0 -> RIGHT
                d in -130.0..-65.0 -> LEFT
                else -> BEHIND
            }
        }
    }
}
