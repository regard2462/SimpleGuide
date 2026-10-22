package app.simpleguide.text

import app.simpleguide.geo.Side
import kotlin.random.Random

/** Ein gesprochener Satz und die Pause danach. */
data class Line(val text: String, val pauseMs: Int = 380)

/** Alles, was zur Ansage eines Ortes gebraucht wird. */
data class PlaceText(
    val title: String,
    val description: String?,
    val intro: String?,
    val full: String?,
    val category: Category,
)

/**
 * Formuliert Ansagen wie ein Gästeführer, nur mit Vorlagen und dem Text aus der Wikipedia:
 * "Wenn du nach rechts schaust, siehst du den Bertoldsbrunnen. Er ist ein Denkmal in der Altstadt …"
 * Die Vorlagen wechseln zufällig, damit es auf einem langen Spaziergang nicht eintönig wird.
 */
class Narration(private val lang: String, private val random: Random = Random.Default) {
    private val de = lang != "en"

    /** Erste Erzählung zu einem Ort und die Häppchen für "Mehr erzählen". */
    data class PlaceScript(val lines: List<Line>, val more: List<List<Line>>, val phrase: NounPhrase)

    fun place(p: PlaceText, side: Side, distanceM: Int, detail: Detail, inside: Boolean = false): PlaceScript {
        val story = Summarizer.story(p.intro, p.full, lang, detail)
        val subject = SubjectParser.parse(story.first, p.title, lang)
        val np = subject.phrase
        val lines = ArrayList<Line>()
        when {
            story.isEmpty -> {
                // Kein Text (offline ohne Einleitung): Name und Kurzbeschreibung
                lines += Line(announce(np, p.category, side, distanceM, inside))
                p.description?.takeIf { it.isNotBlank() }?.let { lines += Line(sentence(it)) }
            }
            subject.sentenceWithPronoun != null && (np.hasArticle || !de) -> {
                lines += Line(announce(np, p.category, side, distanceM, inside), 300)
                lines += Line(subject.sentenceWithPronoun)
            }
            else -> {
                lines += Line(attention(side, distanceM, inside), 250)
                lines += Line(story.first!!)
            }
        }
        story.lead.forEach { lines += Line(it) }
        return PlaceScript(lines, story.more.map { chunk -> chunk.map { Line(it) } }, np)
    }

    // ---------------------------------------------------------------- Ansage mit Namen

    /** "Rechts von dir siehst du den Bertoldsbrunnen." / "Du stehst jetzt auf dem Münsterplatz." */
    fun announce(np: NounPhrase, category: Category, side: Side, distanceM: Int, inside: Boolean): String {
        if (inside) return if (de) "Du bist jetzt ${locative(np)}." else "You are now at ${np.nominative}."
        val d = distancePhrase(distanceM)
        return if (de) germanAnnounce(np, category, side, distanceM, d) else englishAnnounce(np, side, d)
    }

    private fun germanAnnounce(np: NounPhrase, category: Category, side: Side, distanceM: Int, d: String?): String {
        if (distanceM < 20) return pick(
            "Du stehst jetzt direkt ${contract("bei", np.dative)}.",
            "Gleich neben dir: ${np.nominative}.",
        )
        val see = category.visible
        val acc = np.accusative
        val nom = np.nominative
        val dist = d?.let { ", $it," } ?: ""
        return when (side) {
            Side.RIGHT -> pick(
                if (see) "Wenn du nach rechts schaust, siehst du $acc." else "Rechts von dir$dist befindet sich $nom.",
                "Rechts von dir$dist liegt $nom.",
                if (see) "Zu deiner Rechten siehst du $acc." else "Zu deiner Rechten befindet sich $nom.",
            )
            Side.LEFT -> pick(
                if (see) "Wenn du nach links schaust, siehst du $acc." else "Links von dir$dist befindet sich $nom.",
                "Links von dir$dist liegt $nom.",
                if (see) "Zu deiner Linken siehst du $acc." else "Zu deiner Linken befindet sich $nom.",
            )
            Side.AHEAD -> pick(
                if (see) "Direkt vor dir siehst du $acc." else "Vor dir$dist befindet sich $nom.",
                "Vor dir$dist liegt $nom.",
                if (see) "Geradeaus siehst du $acc." else "Geradeaus$dist liegt $nom.",
            )
            Side.AHEAD_RIGHT -> pick(
                if (see) "Halb rechts vor dir siehst du $acc." else "Halb rechts vor dir befindet sich $nom.",
                "Rechts vor dir$dist liegt $nom.",
            )
            Side.AHEAD_LEFT -> pick(
                if (see) "Halb links vor dir siehst du $acc." else "Halb links vor dir befindet sich $nom.",
                "Links vor dir$dist liegt $nom.",
            )
            Side.BEHIND -> pick(
                if (see) "Wenn du dich umdrehst, siehst du $acc." else "Hinter dir$dist befindet sich $nom.",
                "Hinter dir$dist liegt $nom.",
            )
            Side.UNKNOWN -> pick(
                "Ganz in der Nähe$dist liegt $nom.",
                "Hier in der Nähe$dist befindet sich $nom.",
            )
        }.let(::capitalize)
    }

    private fun englishAnnounce(np: NounPhrase, side: Side, d: String?): String {
        val n = np.nominative
        val dist = d?.let { ", $it," } ?: ""
        return when (side) {
            Side.RIGHT -> pick("On your right$dist is $n.", "Look to your right: that's $n.")
            Side.LEFT -> pick("On your left$dist is $n.", "Look to your left: that's $n.")
            Side.AHEAD -> pick("Straight ahead$dist is $n.", "Right in front of you is $n.")
            Side.AHEAD_RIGHT -> "Ahead on your right$dist is $n."
            Side.AHEAD_LEFT -> "Ahead on your left$dist is $n."
            Side.BEHIND -> pick("Behind you$dist is $n.", "If you turn around, you'll see $n.")
            Side.UNKNOWN -> pick("Close by$dist is $n.", "Nearby$dist is $n.")
        }.let(::capitalize)
    }

    // ---------------------------------------------------------------- Ansage ohne Namen

    /** "Schau mal nach rechts, etwa 50 Meter entfernt." – davor der unveränderte erste Satz des Artikels. */
    fun attention(side: Side, distanceM: Int, inside: Boolean): String {
        val d = distancePhrase(distanceM)
        if (de) {
            if (inside) return "Du bist jetzt hier:"
            val where = when (side) {
                Side.RIGHT -> "Schau mal nach rechts"
                Side.LEFT -> "Schau mal nach links"
                Side.AHEAD -> "Schau mal geradeaus"
                Side.AHEAD_RIGHT -> "Schau mal halb rechts nach vorn"
                Side.AHEAD_LEFT -> "Schau mal halb links nach vorn"
                Side.BEHIND -> "Dreh dich mal um"
                Side.UNKNOWN -> "Ganz in der Nähe"
            }
            return if (d != null) "$where, $d." else "$where."
        }
        if (inside) return "You are now here:"
        val where = when (side) {
            Side.RIGHT -> "Have a look to your right"
            Side.LEFT -> "Have a look to your left"
            Side.AHEAD -> "Have a look straight ahead"
            Side.AHEAD_RIGHT -> "Have a look ahead to the right"
            Side.AHEAD_LEFT -> "Have a look ahead to the left"
            Side.BEHIND -> "Turn around for a moment"
            Side.UNKNOWN -> "Close by"
        }
        return if (d != null) "$where, $d." else "$where."
    }

    /** "etwa 50 Meter entfernt"; nah dran gibt es keine Zahl. */
    fun distancePhrase(m: Int): String? {
        if (m < 35) return null
        val rounded = if (m < 100) (m + 5) / 10 * 10 else (m + 25) / 50 * 50
        return if (de) "etwa $rounded Meter entfernt" else "about $rounded metres away"
    }

    // ---------------------------------------------------------------- Straßen, Überblick, Tour

    /** "Du bist jetzt in der Gerberau." / "Du bist jetzt auf dem Münsterplatz." */
    fun street(name: String, district: String? = null): String {
        if (!de) return "You are now on $name" + (district?.let { " in $it" } ?: "") + "."
        return "Du bist jetzt ${streetLocative(name)}" + (district?.let { " in $it" } ?: "") + "."
    }

    /**
     * Überblick auf Knopfdruck: "Hier in der Nähe gibt es rechts den Bertoldsbrunnen, vor dir das Historische
     * Kaufhaus und links die Kirche St. Martin."
     */
    fun overview(items: List<Pair<NounPhrase, Side>>): String {
        if (items.isEmpty()) return if (de) "Hier in der Nähe kenne ich gerade nichts Besonderes." else "I don't know of anything special close by."
        val parts = items.map { (np, side) ->
            val where = sideWord(side)
            val name = if (de) np.accusative else np.nominative
            if (where.isEmpty()) name else "$where $name"
        }
        val joined = if (parts.size == 1) parts[0] else parts.dropLast(1).joinToString(", ") + (if (de) " und " else " and ") + parts.last()
        return if (de) "Hier in der Nähe gibt es $joined." else "Close by there is $joined."
    }

    private fun sideWord(side: Side): String = if (de) when (side) {
        Side.RIGHT -> "rechts"; Side.LEFT -> "links"; Side.AHEAD -> "vor dir"; Side.BEHIND -> "hinter dir"
        Side.AHEAD_RIGHT -> "halb rechts"; Side.AHEAD_LEFT -> "halb links"; Side.UNKNOWN -> ""
    } else when (side) {
        Side.RIGHT -> "on your right"; Side.LEFT -> "on your left"; Side.AHEAD -> "ahead"; Side.BEHIND -> "behind you"
        Side.AHEAD_RIGHT -> "ahead to the right"; Side.AHEAD_LEFT -> "ahead to the left"; Side.UNKNOWN -> ""
    }

    fun tourStart(street: String?): String {
        val where = street?.let { " " + street(it) }.orEmpty()
        return if (de) "Los geht's.$where Ich melde mich, sobald du an etwas Interessantem vorbeikommst."
        else "Let's go.$where I'll speak up whenever you pass something interesting."
    }

    fun nothingYet(): String = if (de) "Zu dieser Gegend habe ich noch keine Artikel. Ich lade sie, sobald Internet da ist."
    else "I don't have articles for this area yet. I'll load them when there's a connection."

    // ---------------------------------------------------------------- Hilfen

    /** Ortsangabe im Dativ mit passender Präposition: "auf dem Münsterplatz", "in der Gerberau", "im Stadtgarten". */
    fun locative(np: NounPhrase): String {
        val name = np.nominative.lowercase()
        val prep = when {
            Regex("""(platz|markt|steg|brücke|damm|berg|hügel|wiese|insel)\b""").containsMatchIn(name) -> "auf"
            Regex("""(ufer|kanal|see|teich|weiher|fluss|bach|tor|turm|brunnen|denkmal)\b""").containsMatchIn(name) -> "an"
            else -> "in"
        }
        if (!np.hasArticle) return "$prep ${np.nominative}"
        return contract(prep, np.dative)
    }

    /** Präposition mit Dativ, verschmolzen wo üblich: "in dem" → "im", "an dem" → "am", "bei dem" → "beim". */
    private fun contract(prep: String, dative: String): String {
        if (!dative.startsWith("dem ")) return "$prep $dative"
        val rest = dative.removePrefix("dem ")
        return when (prep) {
            "in" -> "im $rest"
            "an" -> "am $rest"
            "bei" -> "beim $rest"
            "zu" -> "zum $rest"
            "von" -> "vom $rest"
            else -> "$prep $dative"
        }
    }

    /** Straßenname aus OpenStreetMap (ohne Artikel) mit Präposition und Artikel nach seiner Endung. */
    internal fun streetLocative(name: String): String {
        val n = name.lowercase()
        return when {
            Regex("""(straße|strasse|gasse|allee|promenade|chaussee|zeile|achse|passage|stiege|treppe)$""").containsMatchIn(n) -> "in der $name"
            Regex("""(brücke)$""").containsMatchIn(n) -> "auf der $name"
            Regex("""(platz|markt|steg|damm|anger|plan)$""").containsMatchIn(n) -> "auf dem $name"
            Regex("""(weg|ring|pfad|steig|graben|wall|winkel|garten|park|kai|tal|hof|grund|gässle|gäßle|gässele|gässchen|gäßchen|gässli|gässlein|stäffele|gängle)$""").containsMatchIn(n) -> "im $name"
            Regex("""(ufer|kanal)$""").containsMatchIn(n) -> "am $name"
            else -> if (n.startsWith("an ") || n.startsWith("am ") || n.startsWith("auf ") || n.startsWith("im ") || n.startsWith("in ") || n.startsWith("zum ") || n.startsWith("zur ") || n.startsWith("unter ") || n.startsWith("hinter ") || n.startsWith("vor ")) name
            else "hier: $name"
        }
    }

    /** Wikidata-Kurzbeschreibung als eigener Satz: "historisches Stadttor in Deutschland" → "Historisches Stadttor in Deutschland." */
    private fun sentence(s: String): String = capitalize(s.trim().trimEnd('.')) + "."

    private fun capitalize(s: String) = s.replaceFirstChar { it.uppercaseChar() }

    private fun pick(vararg options: String): String = options[random.nextInt(options.size)]
}
