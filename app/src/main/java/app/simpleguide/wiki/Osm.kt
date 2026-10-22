package app.simpleguide.wiki

import android.util.JsonReader
import android.util.JsonToken
import app.simpleguide.data.Street
import app.simpleguide.geo.BBox
import app.simpleguide.geo.LatLon
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.Request
import org.json.JSONArray
import java.io.IOException
import java.net.URLEncoder
import java.util.Locale

/**
 * OpenStreetMap: Nominatim für die Suche nach Städten (Rechteck zum Herunterladen) und für den Straßennamen
 * unterwegs, Overpass für alle Straßennamen einer Stadt zum Offline-Gebrauch.
 * Nominatim erlaubt höchstens eine Anfrage pro Sekunde; das hält [throttle] ein.
 */
object Osm {
    data class Found(val name: String, val detail: String, val box: BBox, val center: LatLon)
    data class Address(val street: String?, val district: String?)

    private val lock = Mutex()
    private var lastCall = 0L

    private suspend fun throttle() {
        val wait = lastCall + 1100 - System.currentTimeMillis()
        if (wait > 0) delay(wait)
        lastCall = System.currentTimeMillis()
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    /** Koordinate auf vier Nachkommastellen (etwa 10 m): für Straßennamen und Umkreissuche genau genug. */
    fun coarse(v: Double): String = String.format(Locale.ROOT, "%.4f", v)

    /** Orte zum Herunterladen suchen ("Freiburg", "Kreuzberg Berlin"). */
    suspend fun search(query: String, lang: String): List<Found> = lock.withLock {
        throttle()
        withContext(Dispatchers.IO) {
            val body = Http.get(
                "https://nominatim.openstreetmap.org/search?format=jsonv2&limit=20&addressdetails=0&q=${enc(query)}&accept-language=$lang",
                attempts = 2,
            )
            val arr = JSONArray(body)
            val all = List(arr.length()) { arr.getJSONObject(it) }
            // Nur Orte und Gebiete, keine Straßen, Haltestellen, Hausadressen oder Geschäfte gleichen Namens
            val areas = all.filter { o ->
                val cat = o.optString("category"); val type = o.optString("type"); val at = o.optString("addresstype")
                (cat == "boundary" && type == "administrative") || (cat == "place" && type in AREA_TYPES) || at in AREA_TYPES
            }
            (areas.ifEmpty { all }).take(8).map { o ->
                val bb = o.getJSONArray("boundingbox") // [south, north, west, east] als Text
                val display = o.optString("display_name")
                Found(
                    name = o.optString("name").ifBlank { display.substringBefore(',') },
                    detail = display,
                    box = BBox(bb.getString(0).toDouble(), bb.getString(2).toDouble(), bb.getString(1).toDouble(), bb.getString(3).toDouble()),
                    center = LatLon(o.getString("lat").toDouble(), o.getString("lon").toDouble()),
                )
            }
        }
    }

    /** Straße und Stadtteil an einem Punkt (online). Die Koordinate geht auf etwa 10 m gerundet hinaus. */
    suspend fun reverse(p: LatLon, lang: String): Address = lock.withLock {
        throttle()
        withContext(Dispatchers.IO) {
            val body = Http.get(
                "https://nominatim.openstreetmap.org/reverse?format=jsonv2&zoom=17&lat=${coarse(p.lat)}&lon=${coarse(p.lon)}&accept-language=$lang",
                attempts = 2,
            )
            val a = org.json.JSONObject(body).optJSONObject("address") ?: return@withContext Address(null, null)
            fun s(vararg keys: String) = keys.firstNotNullOfOrNull { k -> a.optString(k).takeIf { it.isNotBlank() } }
            Address(
                street = s("road", "pedestrian", "footway", "square", "path", "place"),
                district = s("suburb", "city_district", "quarter", "neighbourhood"),
            )
        }
    }

    private val AREA_TYPES = setOf(
        "city", "town", "village", "hamlet", "suburb", "quarter", "neighbourhood", "borough", "city_district",
        "municipality", "county", "island", "district", "state_district",
    )

    private val OVERPASS = listOf("https://overpass-api.de/api/interpreter", "https://overpass.kumi.systems/api/interpreter")

    /** Kantenlänge der Kacheln für Overpass; so bleibt jede Antwort klein genug für den Arbeitsspeicher. */
    private const val STREET_TILE_M = 8000.0

    /**
     * Alle benannten Wege im Rechteck, auf denen man gehen kann (Straßen, Plätze, Fußwege). Große Gebiete
     * werden kachelweise abgefragt und je Kachel an [sink] gegeben, damit nie die ganze Stadt auf einmal im
     * Speicher liegt; ein Weg über eine Kachelgrenze zählt nur einmal. Jede Antwort wird gestreamt gelesen.
     */
    suspend fun streets(box: BBox, sink: suspend (List<Street>) -> Unit) {
        val seen = HashSet<Long>()
        for (tile in box.tiles(STREET_TILE_M)) sink(streetsIn(tile, seen))
    }

    private suspend fun streetsIn(box: BBox, seen: MutableSet<Long>): List<Street> = withContext(Dispatchers.IO) {
        val q = """[out:json][timeout:240];
            (way["highway"~"^(primary|secondary|tertiary|residential|living_street|pedestrian|unclassified|footway|path|steps|service|cycleway|track|trunk)$"]["name"](${box.south},${box.west},${box.north},${box.east});
             way["place"="square"]["name"](${box.south},${box.west},${box.north},${box.east}););
            out tags geom qt;"""
        var last: Exception? = null
        for (url in OVERPASS) {
            try {
                val req = Request.Builder().url(url).post(FormBody.Builder().add("data", q).build()).build()
                Http.client.newCall(req).execute().use { r ->
                    if (!r.isSuccessful) throw IOException("Overpass HTTP ${r.code}")
                    return@withContext parseStreets(JsonReader(r.body!!.charStream()), seen)
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                last = e
            }
        }
        throw last ?: IOException("Overpass failed")
    }

    private fun parseStreets(r: JsonReader, seen: MutableSet<Long>): List<Street> {
        val out = ArrayList<Street>()
        r.beginObject()
        while (r.hasNext()) {
            if (r.nextName() != "elements") { r.skipValue(); continue }
            r.beginArray()
            while (r.hasNext()) {
                var name: String? = null
                var id = -1L
                val pts = ArrayList<LatLon>()
                r.beginObject()
                while (r.hasNext()) {
                    when (r.nextName()) {
                        "id" -> id = r.nextLong()
                        "tags" -> {
                            r.beginObject()
                            while (r.hasNext()) { if (r.nextName() == "name") name = r.nextString() else r.skipValue() }
                            r.endObject()
                        }
                        "geometry" -> {
                            r.beginArray()
                            while (r.hasNext()) {
                                if (r.peek() == JsonToken.NULL) { r.nextNull(); continue }
                                var lat = 0.0; var lon = 0.0
                                r.beginObject()
                                while (r.hasNext()) when (r.nextName()) { "lat" -> lat = r.nextDouble(); "lon" -> lon = r.nextDouble(); else -> r.skipValue() }
                                r.endObject()
                                pts += LatLon(lat, lon)
                            }
                            r.endArray()
                        }
                        else -> r.skipValue()
                    }
                }
                r.endObject()
                if (name != null && pts.size >= 2 && (id < 0 || seen.add(id))) out += Street(name!!, pts)
            }
            r.endArray()
        }
        r.endObject()
        return out
    }
}
