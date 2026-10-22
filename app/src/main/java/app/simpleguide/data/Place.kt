package app.simpleguide.data

import app.simpleguide.geo.BBox
import app.simpleguide.geo.LatLon
import app.simpleguide.text.Category
import app.simpleguide.text.Classifier
import app.simpleguide.text.WikiText

/**
 * Ein Wikipedia-Artikel mit Koordinaten. [intro] ist die Einleitung als Klartext (kann fehlen, solange sie
 * nicht geladen ist), der Volltext liegt komprimiert in der Datenbank ([hasFull]).
 */
data class Place(
    val lang: String,
    val pageId: Long,
    val title: String,
    val lat: Double,
    val lon: Double,
    val description: String? = null,
    val length: Int = 0,
    val intro: String? = null,
    val hasDetails: Boolean = false,
    val hasFull: Boolean = false,
) {
    val pos get() = LatLon(lat, lon)
    val key get() = "$lang:$pageId"
    val name get() = WikiText.cleanTitle(title)

    /** Art des Ortes; aus Beschreibung, erstem Satz und Titel, einmal je Objekt berechnet. */
    val category: Category by lazy {
        val first = intro?.let { WikiText.sections(it).firstOrNull()?.paragraphs?.firstOrNull() }
            ?.let { WikiText.sentences(it, lang).firstOrNull() }
        Classifier.classify(title, description, first, lang)
    }

    /** Wie bedeutend der Ort ist: Art und Artikellänge (lange Artikel = bekannte Orte). */
    val importance: Double by lazy {
        val len = if (length > 0) length else 3000
        category.weight * (1.0 + Math.log(len / 2000.0).coerceIn(-0.7, 3.0) * 0.35)
    }

    /**
     * Art und Bedeutung jetzt berechnen (Satzzerlegung der Einleitung), damit es nicht später auf dem
     * Hauptthread passiert. Aufrufen, wo Orte aus der Datenbank kommen.
     */
    fun prepare(): Place { category; importance; return this }

    fun withUrl() = "https://$lang.wikipedia.org/wiki/" + title.replace(' ', '_')
}

/** Ein heruntergeladenes Gebiet. */
data class City(
    val id: Long,
    val lang: String,
    val name: String,
    val box: BBox,
    val places: Int,
    val texts: Int,
    val streets: Int,
    val bytes: Long,
    /** "queued", "running", "done", "failed" */
    val state: String,
    val message: String,
    val withTexts: Boolean,
    val withStreets: Boolean,
    val withMap: Boolean,
)

/** Straßenabschnitt aus OpenStreetMap für die Ansage "Du bist jetzt in der …". */
class Street(val name: String, val points: List<LatLon>)

/** Treffer der Umkreissuche der Wikipedia (GeoData). */
data class GeoHit(val pageId: Long, val title: String, val lat: Double, val lon: Double)

/** Kurzbeschreibung und Länge eines Artikels; Begriffsklärungen und gelöschte Seiten fliegen raus. */
data class PageDetails(val description: String?, val length: Int, val disambiguation: Boolean = false, val missing: Boolean = false)
