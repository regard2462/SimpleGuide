package app.simpleguide.wiki

import app.simpleguide.data.GeoHit
import app.simpleguide.data.PageDetails
import app.simpleguide.geo.BBox
import app.simpleguide.geo.LatLon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URLEncoder

/**
 * Wikipedia-API (action=query). Alles, was die App aus dem Netz liest, geht hier durch:
 *  - Umkreis- und Rechtecksuche nach Artikeln mit Koordinaten (GeoData, höchstens 500 je Anfrage)
 *  - Kurzbeschreibung (Wikidata) und Länge, 50 Artikel je Anfrage
 *  - Einleitung als Klartext, 20 je Anfrage (Grenze von TextExtracts)
 *  - Volltext mit Abschnittsüberschriften, ein Artikel je Anfrage
 */
class WikiClient {

    private fun api(lang: String, params: Map<String, String>): String {
        val q = (params + mapOf("action" to "query", "format" to "json", "formatversion" to "2", "maxlag" to "5"))
            .entries.joinToString("&") { (k, v) -> "$k=" + URLEncoder.encode(v, "UTF-8") }
        return "https://$lang.wikipedia.org/w/api.php?$q"
    }

    private val busy: (String) -> Boolean = { it.contains("\"code\":\"cirrussearch-too-busy") || it.contains("\"code\":\"maxlag\"") }

    private suspend fun query(lang: String, params: Map<String, String>): JSONObject = withContext(Dispatchers.IO) {
        val json = JSONObject(Http.get(api(lang, params), busy = busy))
        json.optJSONObject("error")?.let { e -> throw ApiException(e.optString("code"), e.optString("info")) }
        json
    }

    class ApiException(val code: String, info: String) : Exception("$code: $info")

    /** Artikel im Umkreis von [radiusM] Metern (höchstens 10 km, 500 Treffer); der Standort geht gerundet hinaus. */
    suspend fun around(lang: String, center: LatLon, radiusM: Int, limit: Int = 500): List<GeoHit> =
        hits(query(lang, mapOf(
            "list" to "geosearch", "gscoord" to "${Osm.coarse(center.lat)}|${Osm.coarse(center.lon)}", "gsradius" to radiusM.coerceIn(10, 10_000).toString(),
            "gslimit" to limit.toString(), "gsnamespace" to "0", "gsprimary" to "primary",
        )))

    /**
     * Artikel im Rechteck. Liefert null, wenn das Rechteck für die Suche zu groß ist ("toobig");
     * der Aufrufer teilt es dann.
     */
    suspend fun inBox(lang: String, box: BBox, limit: Int = 500): List<GeoHit>? = try {
        hits(query(lang, mapOf(
            "list" to "geosearch", "gsbbox" to "${box.north}|${box.west}|${box.south}|${box.east}",
            "gslimit" to limit.toString(), "gsnamespace" to "0", "gsprimary" to "primary",
        )))
    } catch (e: ApiException) {
        if (e.code == "toobig") null else throw e
    }

    private fun hits(json: JSONObject): List<GeoHit> {
        val arr = json.optJSONObject("query")?.optJSONArray("geosearch") ?: return emptyList()
        return List(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            GeoHit(o.getLong("pageid"), o.getString("title"), o.getDouble("lat"), o.getDouble("lon"))
        }
    }

    /** Kurzbeschreibung und Länge für bis zu 50 Seiten. */
    suspend fun details(lang: String, ids: List<Long>): Map<Long, PageDetails> {
        require(ids.size <= 50)
        if (ids.isEmpty()) return emptyMap()
        val json = query(lang, mapOf(
            "pageids" to ids.joinToString("|"), "prop" to "description|info|pageprops", "ppprop" to "disambiguation",
        ))
        val pages = json.optJSONObject("query")?.optJSONArray("pages") ?: return emptyMap()
        return buildMap {
            for (i in 0 until pages.length()) {
                val p = pages.getJSONObject(i)
                val id = p.optLong("pageid", -1).takeIf { it > 0 } ?: continue
                put(id, PageDetails(
                    description = p.optString("description").takeIf { it.isNotBlank() },
                    length = p.optInt("length", 0),
                    disambiguation = p.optJSONObject("pageprops")?.has("disambiguation") == true,
                    missing = p.optBoolean("missing", false),
                ))
            }
        }
    }

    /** Einleitungen als Klartext für bis zu 20 Seiten. */
    suspend fun intros(lang: String, ids: List<Long>): Map<Long, String> {
        require(ids.size <= 20)
        if (ids.isEmpty()) return emptyMap()
        val out = HashMap<Long, String>()
        var cont: String? = null
        // TextExtracts liefert manchmal weniger als angefragt und setzt dann "excontinue"
        do {
            val params = mutableMapOf(
                "pageids" to ids.joinToString("|"), "prop" to "extracts", "exintro" to "1", "explaintext" to "1",
                "exlimit" to "20", "exsectionformat" to "plain",
            )
            cont?.let { params["excontinue"] = it }
            val json = query(lang, params)
            json.optJSONObject("query")?.optJSONArray("pages")?.let { pages ->
                for (i in 0 until pages.length()) {
                    val p = pages.getJSONObject(i)
                    val text = p.optString("extract")
                    if (text.isNotBlank()) out[p.getLong("pageid")] = text.trim()
                }
            }
            cont = json.optJSONObject("continue")?.optString("excontinue")?.takeIf { it.isNotEmpty() }
        } while (cont != null && out.size < ids.size)
        return out
    }

    /** Ganzer Artikel als Klartext mit "== Abschnitt ==", oder null. */
    suspend fun fullText(lang: String, id: Long): String? {
        val json = query(lang, mapOf(
            "pageids" to id.toString(), "prop" to "extracts", "explaintext" to "1", "exsectionformat" to "wiki",
        ))
        val p = json.optJSONObject("query")?.optJSONArray("pages")?.optJSONObject(0) ?: return null
        return p.optString("extract").takeIf { it.isNotBlank() }?.trim()
    }
}
