package app.simpleguide.guide

import app.simpleguide.data.Place
import app.simpleguide.geo.BBox
import app.simpleguide.geo.Geo
import app.simpleguide.geo.HeadingTracker
import app.simpleguide.geo.LatLon
import app.simpleguide.geo.Side
import app.simpleguide.text.Category
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GeoTest {
    private val muenster = LatLon(47.995556, 7.852778)
    private val bertold = LatLon(47.994722, 7.849167)

    @Test
    fun entfernungUndKurs() {
        val d = Geo.distance(muenster, bertold)
        assertTrue("$d", d in 280.0..300.0)
        val north = Geo.bearing(LatLon(48.0, 7.85), LatLon(48.01, 7.85))
        assertEquals(0.0, north, 0.01)
        val east = Geo.bearing(LatLon(48.0, 7.85), LatLon(48.0, 7.86))
        assertEquals(90.0, east, 0.1)
        assertEquals(-20.0, Geo.angleDiff(10.0, 350.0), 1e-9)
        assertEquals(20.0, Geo.angleDiff(350.0, 10.0), 1e-9)
    }

    @Test
    fun seite() {
        assertEquals(Side.RIGHT, Side.of(0.0, 90.0))
        assertEquals(Side.LEFT, Side.of(0.0, 270.0))
        assertEquals(Side.AHEAD, Side.of(350.0, 10.0))
        assertEquals(Side.BEHIND, Side.of(90.0, 270.0))
        assertEquals(Side.AHEAD_RIGHT, Side.of(0.0, 45.0))
        assertEquals(Side.UNKNOWN, Side.of(null, 45.0))
    }

    @Test
    fun kacheln() {
        val b = BBox(47.95, 7.75, 48.05, 7.95)
        val tiles = b.tiles(4000.0)
        assertTrue(tiles.all { it.heightM <= 4001 && it.widthM <= 4001 })
        assertEquals(4, b.quarters().size)
        val inner = Geo.bbox(muenster, 100.0)
        assertTrue(inner.contains(muenster))
        assertEquals(100.0, Geo.distance(muenster, LatLon(inner.north, muenster.lon)), 0.5)
    }

    @Test
    fun koordinatenFuerDieApiImmerMitPunkt() {
        val before = java.util.Locale.getDefault()
        java.util.Locale.setDefault(java.util.Locale.GERMANY)
        try {
            assertEquals("47.9956", app.simpleguide.wiki.Osm.coarse(47.995556))
        } finally { java.util.Locale.setDefault(before) }
    }

    @Test
    fun abstandZurStrecke() {
        val a = LatLon(48.0, 7.85); val b = LatLon(48.0, 7.86)
        val p = LatLon(48.0002, 7.855)   // gut 22 m nördlich der Mitte
        assertEquals(22.2, Geo.distanceToSegment(p, a, b), 0.5)
        assertEquals(Geo.distance(LatLon(48.0, 7.84), a), Geo.distanceToSegment(LatLon(48.0, 7.84), a, b), 1.0)
    }
}

class HeadingTrackerTest {
    @Test
    fun richtungErstNachEinigenMetern() {
        val t = HeadingTracker()
        var lat = 48.0
        t.add(HeadingTracker.Fix(LatLon(lat, 7.85), 0))
        lat += 0.00003 // gut 3 m
        t.add(HeadingTracker.Fix(LatLon(lat, 7.85), 2000))
        assertNull(t.heading(2000))
        for (i in 2..8) { lat += 0.00003; t.add(HeadingTracker.Fix(LatLon(lat, 7.85), i * 2000L)) }
        val h = t.heading(16_000)
        assertNotNull(h)
        assertEquals(0.0, Geo.angleDiff(0.0, h!!), 2.0)
        // Lange stehen: Richtung verfällt
        assertNull(t.heading(16_000 + 60_000))
    }

    @Test
    fun gpsKursBeiGehtempo() {
        val t = HeadingTracker()
        t.add(HeadingTracker.Fix(LatLon(48.0, 7.85), 0, 5f, 123f, 1.3f))
        assertEquals(123.0, t.heading(0)!!, 0.01)
    }
}

class TourPolicyTest {
    private val me = LatLon(47.9950, 7.8520)

    private fun place(id: Long, desc: String, northM: Double, eastM: Double, length: Int = 8000): Place {
        val p = LatLon(me.lat + northM / 111_195.0, me.lon + eastM / (111_195.0 * Math.cos(Math.toRadians(me.lat))))
        return Place("de", id, "Ort $id", p.lat, p.lon, desc, length, hasDetails = true)
    }

    private val policy = TourPolicy(60, Category.entries.filter { it.defaultOn }.toSet())

    @Test
    fun naheOrteKommenDran() {
        val kirche = place(1, "Kirchengebäude in Freiburg", 0.0, 40.0)
        val verein = place(2, "Verein in Freiburg", 0.0, -10.0)
        val fern = place(3, "Brunnen in Freiburg", 300.0, 0.0)
        val c = listOf(kirche, verein, fern).map { Candidate.of(it, me, 0.0) }
        assertEquals(1L, policy.pick(c, emptySet())?.place?.pageId)
        assertEquals(Side.RIGHT, c[0].side)
        // Schon gehört: nichts mehr
        assertNull(policy.pick(c, setOf(1L)))
    }

    @Test
    fun vorbeiIstVorbei() {
        val hinten = place(1, "Brunnen in Freiburg", -50.0, 0.0)
        assertNull(policy.pick(listOf(Candidate.of(hinten, me, 0.0)), emptySet()))
        // Ohne bekannte Gehrichtung zählt nur die Entfernung
        assertEquals(1L, policy.pick(listOf(Candidate.of(hinten, me, null)), emptySet())?.place?.pageId)
    }

    @Test
    fun grosseBauwerkeAuchKnappDahinter() {
        val muenster = place(1, "Kirchengebäude in Freiburg", -50.0, -20.0, length = 147_000)
        val brunnen = place(2, "Brunnen in Freiburg", -50.0, -20.0)
        val c = listOf(muenster, brunnen).map { Candidate.of(it, me, 65.0) }
        assertEquals(Side.BEHIND, c[0].side)
        assertEquals(1L, policy.pick(c, emptySet())?.place?.pageId)
        assertNull(policy.pick(c.drop(1), emptySet()))
    }

    @Test
    fun strassenNurWennManDraufIst() {
        val strasse = Place("de", 9, "Kaiser-Joseph-Straße (Freiburg im Breisgau)", me.lat + 0.0004, me.lon, "Straße in Freiburg", 12000, hasDetails = true)
        val weit = Candidate.of(strasse, me, 0.0)
        assertNull(policy.pick(listOf(weit), emptySet()))
        val drauf = Candidate(strasse, 0.0, 0.0, Side.UNKNOWN, onStreet = true)
        assertEquals(9L, policy.pick(listOf(drauf), emptySet())?.place?.pageId)
        val platz = place(10, "Platz in Freiburg", 40.0, 0.0)
        assertEquals(10L, policy.pick(listOf(Candidate.of(platz.copy(title = "Münsterplatz"), me, 0.0)), emptySet())?.place?.pageId)
    }

    @Test
    fun stummelNicht() {
        val stummel = place(1, "Turm in Freiburg", 0.0, 20.0, length = 614)
        assertNull(policy.pick(listOf(Candidate.of(stummel, me, 0.0)), emptySet()))
    }

    @Test
    fun beruehmtesGewinnt() {
        val klein = place(1, "Brunnen in Freiburg", 0.0, 20.0, length = 3000)
        val muenster = place(2, "Kirchengebäude in Freiburg", 70.0, 0.0, length = 147_000)
        val pick = policy.pick(listOf(klein, muenster).map { Candidate.of(it, me, 0.0) }, emptySet())
        assertEquals(2L, pick?.place?.pageId)
        // Das Münster hat einen größeren Auslöseradius
        assertTrue(policy.radiusFor(muenster) > policy.radiusFor(klein))
    }
}
