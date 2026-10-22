package app.simpleguide.text

/**
 * Klartext aus der Wikipedia (TextExtracts, explaintext, Abschnitte als "== Titel ==") in vorlesbare Sätze
 * zerlegen. Alles, was beim Zuhören stört, fällt weg: Klammern (Aussprache, Lebensdaten, Zweitnamen),
 * Verweise, Listen, Tabellenreste und Sätze, die fast nur aus Zahlen bestehen.
 */
object WikiText {

    data class Section(val title: String, val level: Int, val paragraphs: List<String>)

    private val HEADING = Regex("""^(={2,6})\s*(.*?)\s*\1\s*$""")

    /** Abschnitte in Reihenfolge; der erste (Titel "") ist die Einleitung. */
    fun sections(extract: String): List<Section> {
        val out = ArrayList<Section>()
        var title = ""
        var level = 1
        val paras = ArrayList<String>()
        fun flush() {
            out += Section(title, level, paras.toList())
            paras.clear()
        }
        for (raw in extract.replace("\r", "").split('\n')) {
            val line = raw.trim()
            val h = HEADING.matchEntire(line)
            when {
                h != null -> { flush(); title = h.groupValues[2]; level = h.groupValues[1].length }
                line.isNotEmpty() -> paras += line
            }
        }
        flush()
        return out.filter { it.title.isEmpty() || it.paragraphs.isNotEmpty() }
    }

    /** Abschnitte ohne Fließtext zum Vorlesen (Belege, Listen von Werken, Bilder). */
    fun skipSection(title: String): Boolean {
        val t = title.lowercase()
        return SKIP.any { t == it || t.startsWith("$it ") || t.startsWith(it) && it.length > 6 }
    }

    private val SKIP = listOf(
        // deutsch
        "literatur", "weblinks", "einzelnachweise", "siehe auch", "anmerkungen", "quellen", "galerie", "bilder",
        "filmografie", "diskografie", "werke", "schriften", "bibliografie", "bibliographie", "fußnoten", "belege",
        "auszeichnungen", "medien", "trivia", "rezeption in medien", "liste", "einwohnerentwicklung", "daten",
        "technische daten", "statistik", "fahrplan", "verkehrsanbindung", "linien", "mitglieder", "persönlichkeiten",
        "söhne und töchter", "partnerstädte", "spielstätten", "spielzeiten", "repertoire",
        // englisch
        "references", "external links", "see also", "notes", "further reading", "bibliography", "gallery", "sources",
        "citations", "works", "filmography", "discography", "footnotes", "awards", "in popular culture", "statistics",
        "services", "notable people", "list of", "twin towns",
    )

    // ---------------------------------------------------------------- Bereinigung

    private val PARENS = Regex("""\s*\([^()]*\)""")
    private val BRACKETS = Regex("""\s*\[[^\[\]]*]""")
    private val SPACE_BEFORE = Regex("""\s+([,.;:!?])""")
    private val DOUBLE_PUNCT = Regex("""([,;:])\s*([,;:.!?])""")
    private val WS = Regex("""\s+""")
    private val INVISIBLE = Regex("""[​-‏⁠﻿­]""")

    /**
     * Klammern samt Inhalt entfernen (auch verschachtelt), Verweispfeile, unsichtbare Zeichen und doppelte
     * Satzzeichen glätten. Der Satz bleibt sonst unverändert; Zahlen schreibt erst der Normalisierer aus.
     */
    fun clean(text: String): String {
        var t = INVISIBLE.replace(text, "")
        t = t.replace(' ', ' ')
        var prev: String
        do { prev = t; t = PARENS.replace(t, "") } while (t != prev)
        do { prev = t; t = BRACKETS.replace(t, "") } while (t != prev)
        // Klammerreste ohne Gegenstück
        t = t.replace(Regex("""[()\[\]]"""), "")
        t = t.replace(Regex("""\s*→\s*\S+"""), "")
        t = t.replace("„“", "").replace("\"\"", "")
        t = WS.replace(t, " ")
        t = SPACE_BEFORE.replace(t, "$1")
        t = DOUBLE_PUNCT.replace(t) { m -> if (m.groupValues[2] in listOf(".", "!", "?")) m.groupValues[2] else m.groupValues[1] }
        return t.trim().trim(',', ';').trim()
    }

    private val ENDS_SENTENCE = Regex("""[.!?…]["“”»«’']?$""")
    private val TOKEN = Regex("""\S+""")

    /**
     * Ist der Satz zum Vorlesen geeignet? Zu kurz, Listeneinleitung, Koordinaten, fast nur Zahlen oder
     * Formelreste klingen vorgelesen kaputt.
     */
    fun speakable(sentence: String): Boolean {
        val s = sentence.trim()
        if (!ENDS_SENTENCE.containsMatchIn(s)) return false
        val tokens = TOKEN.findAll(s).map { it.value }.toList()
        if (tokens.size < 4 || tokens.size > 75) return false
        if (s.contains('|') || s.contains('=') || s.contains('{') || s.contains('°') && Regex("""\d+°""").containsMatchIn(s)) return false
        val numeric = tokens.count { t -> t.count { it.isDigit() } * 2 >= t.length }
        if (numeric * 3 > tokens.size) return false
        if (s.count { it.isLetter() } < 20) return false
        return true
    }

    /** Absatz → bereinigte, vorlesbare Sätze. */
    fun sentences(paragraph: String, lang: String): List<String> {
        val cleaned = clean(paragraph)
        if (cleaned.isEmpty()) return emptyList()
        // Absätze ohne Satzende sind Listenpunkte oder Bildunterschriften
        if (!ENDS_SENTENCE.containsMatchIn(cleaned) && !cleaned.endsWith(":")) return emptyList()
        return Segmenter.split(cleaned, lang, keepLong = true).map { it.trim() }.filter { speakable(it) }
    }

    /** "Schwabentor (Freiburg im Breisgau)" → "Schwabentor". */
    fun cleanTitle(title: String): String = title.replace(Regex("""\s*\([^)]*\)\s*$"""), "").trim()

    fun wordCount(s: String): Int = TOKEN.findAll(s).count()
}
