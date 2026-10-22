package app.simpleguide.text

/** Wie viel zu einem Ort erzählt wird. */
enum class Detail(val leadWords: Int) { SHORT(35), MEDIUM(80), LONG(150) }

/**
 * Was zu einem Ort vorgelesen wird: [first] ist der erste Satz (daraus entsteht die Ansage mit Pronomen),
 * [lead] die Sätze danach bis zur gewählten Länge, [more] weitere Häppchen für "Mehr erzählen".
 */
data class Story(val first: String?, val lead: List<String>, val more: List<List<String>>) {
    val isEmpty get() = first == null
}

/**
 * Wählt ohne Sprachmodell aus, was vorgelesen wird. Die Einleitung eines Wikipedia-Artikels ist bereits eine
 * Zusammenfassung; sie wird in Reihenfolge gelesen, bis die gewünschte Länge erreicht ist (am liebsten an
 * einem Absatzende). Aus den übrigen Abschnitten kommen die Sätze mit dem meisten "Stoff für Zuhörer":
 * Jahreszahlen, Superlative, Bau- und Zerstörungsgeschichte, Legenden. Sätze, die mit einem Rückverweis
 * beginnen ("Dabei …", "Er …"), bleiben mit ihrem Vorgänger zusammen, damit nichts aus dem Zusammenhang fällt.
 */
object Summarizer {
    private const val CHUNK_WORDS = 110
    private const val MAX_CHUNKS = 4
    private const val MIN_SCORE = 1.5

    fun story(intro: String?, full: String?, lang: String, detail: Detail): Story {
        val sections = WikiText.sections(full?.takeIf { it.isNotBlank() } ?: intro.orEmpty())
        if (sections.isEmpty()) return Story(null, emptyList(), emptyList())
        // Ist der Volltext da, enthält er die Einleitung als ersten Abschnitt
        val introParas = sections.first().paragraphs.map { WikiText.sentences(it, lang) }.filter { it.isNotEmpty() }
        // Maßangaben-Sätze ("Gesamtlänge 125,83 m, Innenhöhe 25,70 m …") sind zum Zuhören zu trocken
        val introFlat = introParas.flatten().filterNot { measurements(it) }
        // Der erste Satz muss ein richtiger Satzanfang sein
        val first = introFlat.firstOrNull { startsProperly(it) } ?: return Story(null, emptyList(), emptyList())

        // ---- Einleitung bis zum Budget, bevorzugt bis zu einem Absatzende
        val lead = ArrayList<String>()
        var words = WikiText.wordCount(first)
        var index = introFlat.indexOf(first) + 1
        var paraEnd = introParas.first().count { !measurements(it) }
        var paraNo = 0
        while (index < introFlat.size) {
            val s = introFlat[index]
            val w = WikiText.wordCount(s)
            if (words + w > detail.leadWords) break
            // Bei mittlerer Länge an einem Absatzende aufhören, wenn schon genug gesagt ist
            if (index == paraEnd && detail != Detail.LONG && words >= detail.leadWords * 0.55) break
            lead += s
            words += w
            index++
            if (index > paraEnd && paraNo + 1 < introParas.size) { paraNo++; paraEnd += introParas[paraNo].count { !measurements(it) } }
        }
        val more = ArrayList<List<String>>()
        chunk(introFlat.drop(index)).take(2).forEach { more += it }

        // ---- Höhepunkte aus den übrigen Abschnitten
        val units = ArrayList<Piece>()
        var pos = 0
        sections.drop(1).filterNot { WikiText.skipSection(it.title) }.forEach { sec ->
            val weight = sectionWeight(sec.title, lang)
            sec.paragraphs.forEach { p ->
                var current: Piece? = null
                for (s in WikiText.sentences(p, lang)) {
                    val score = score(s, lang) * weight
                    val cur = current
                    if (cur != null && startsWithReference(s, lang)) {
                        current = Piece(cur.pos, cur.sentences + s, maxOf(cur.score, score) - 0.3)
                        units[units.lastIndex] = current
                    } else {
                        current = Piece(pos++, listOf(s), score)
                        units += current
                    }
                }
            }
        }
        val picked = units.filter { it.score >= MIN_SCORE && it.words <= CHUNK_WORDS }
            .sortedByDescending { it.score }
        val chunks = ArrayList<MutableList<Piece>>()
        var budget = 0
        for (u in picked) {
            if (chunks.size >= MAX_CHUNKS && budget + u.words > CHUNK_WORDS) break
            if (chunks.isEmpty() || budget + u.words > CHUNK_WORDS) {
                if (chunks.size >= MAX_CHUNKS) break
                chunks += mutableListOf(u); budget = u.words
            } else { chunks.last() += u; budget += u.words }
        }
        chunks.forEach { c -> more += c.sortedBy { it.pos }.flatMap { it.sentences } }

        // Ausführlich: der erste Höhepunkt gehört schon zur ersten Erzählung
        if (detail == Detail.LONG && more.isNotEmpty()) {
            lead += more.removeAt(0)
        }
        return Story(first, lead, more)
    }

    private class Piece(val pos: Int, val sentences: List<String>, val score: Double) {
        val words = sentences.sumOf { WikiText.wordCount(it) }
    }

    private fun chunk(sentences: List<String>): List<List<String>> {
        val out = ArrayList<List<String>>()
        var cur = ArrayList<String>()
        var w = 0
        for (s in sentences) {
            val n = WikiText.wordCount(s)
            if (cur.isNotEmpty() && w + n > CHUNK_WORDS) { out += cur; cur = ArrayList(); w = 0 }
            cur += s; w += n
        }
        if (cur.isNotEmpty()) out += cur
        return out
    }

    // ---------------------------------------------------------------- Bewertung

    private fun sectionWeight(title: String, lang: String): Double {
        val t = title.lowercase()
        val table = if (lang == "en") EN_SECTIONS else DE_SECTIONS
        return table.firstOrNull { (k, _) -> t.contains(k) }?.second ?: 0.6
    }

    private val DE_SECTIONS = listOf(
        "geschichte" to 1.0, "baugeschichte" to 1.0, "architektur" to 1.0, "baubeschreibung" to 1.0, "beschreibung" to 0.95,
        "bauwerk" to 0.95, "gebäude" to 0.95, "ausstattung" to 0.8, "legende" to 1.1, "sage" to 1.1, "name" to 0.85,
        "lage" to 0.7, "nutzung" to 0.75, "heute" to 0.75, "entstehung" to 1.0, "bedeutung" to 0.9, "zerstörung" to 1.0,
        "wiederaufbau" to 1.0, "renovierung" to 0.6, "sanierung" to 0.5, "rezeption" to 0.6, "kunst" to 0.8,
    )
    private val EN_SECTIONS = listOf(
        "history" to 1.0, "architecture" to 1.0, "description" to 0.95, "building" to 0.95, "design" to 0.9,
        "legend" to 1.1, "name" to 0.85, "etymology" to 0.85, "location" to 0.7, "use" to 0.75, "today" to 0.75,
        "construction" to 1.0, "significance" to 0.9, "destruction" to 1.0, "restoration" to 0.6, "interior" to 0.8,
    )

    private val YEAR = Regex("""\b(1[0-9]{3}|20[0-2][0-9]|[5-9][0-9]{2})\b""")
    private val NUMBER_UNIT = Regex("""\d[\d.,]*\s?(?:m|km|cm|m²|m³|Meter|Euro|€|DM|Mark|%|Prozent|t|Tonnen|ha|ft|feet|foot|inches|miles|yards)(?![\p{L}])|[£$€]\s?\d""")

    private val DE_GOOD = Regex(
        """\b(älteste[nrs]?|größte[nrs]?|höchste[nrs]?|längste[nrs]?|bekannteste[nrs]?|berühmteste[nrs]?|bedeutendste[nrs]?|""" +
            """schönste[nrs]?|wichtigste[nrs]?|erste[nrs]?|einzige[nrs]?|einzigartig\w*|wahrzeichen|weltweit|europaweit|""" +
            """legende|sage|soll|sollen|angeblich|erzählt|überliefert|berühmt|bekannt)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val DE_HISTORY = Regex(
        """\b(erbaut|errichtet|gegründet|gebaut|zerstört|wiederaufgebaut|wieder aufgebaut|eingeweiht|geweiht|eröffnet|""" +
            """umgebaut|abgerissen|entworfen|gestiftet|benannt|niedergebrannt|brannte|bombardiert|verlegt|vollendet|begonnen|""" +
            """architekt\w*|baumeister|stil|gotik|gotisch\w*|romanisch\w*|barock\w*|renaissance|jugendstil|klassizis\w*|mittelalter\w*)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val EN_GOOD = Regex(
        """\b(oldest|largest|tallest|highest|longest|best-known|most famous|most important|first|only|unique|landmark|""" +
            """legend|said to|reputedly|allegedly|famous|renowned|world)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val EN_HISTORY = Regex(
        """\b(built|erected|founded|constructed|destroyed|rebuilt|consecrated|opened|remodell?ed|demolished|designed|""" +
            """named|burned|bombed|completed|architect\w*|style|gothic|romanesque|baroque|renaissance|art nouveau|neoclassical|medieval)\b""",
        RegexOption.IGNORE_CASE,
    )

    private fun measurements(s: String) = NUMBER_UNIT.findAll(s).count() >= 3

    private fun startsProperly(s: String) = s.firstOrNull()?.let { it.isUpperCase() || it.isDigit() || it in "„\"»“" } == true

    internal fun score(s: String, lang: String): Double {
        var score = 0.0
        if (YEAR.containsMatchIn(s)) score += 1.0
        val good = if (lang == "en") EN_GOOD else DE_GOOD
        val history = if (lang == "en") EN_HISTORY else DE_HISTORY
        score += minOf(2, good.findAll(s).count()) * 1.2
        score += minOf(2, history.findAll(s).count()) * 0.8
        val units = NUMBER_UNIT.findAll(s).count()
        if (units >= 2) score -= 1.0 * (units - 1)
        val words = WikiText.wordCount(s)
        if (words > 40) score -= 1.0
        if (words < 7) score -= 0.8
        if (startsWithReference(s, lang)) score -= 0.5
        return score
    }

    private val DE_REF = setOf(
        "er", "sie", "es", "dies", "diese", "dieser", "dieses", "diesem", "diesen", "dabei", "daher", "dann", "dort", "damit",
        "davon", "darin", "darauf", "dazu", "dafür", "deshalb", "deswegen", "allerdings", "stattdessen", "außerdem", "zudem",
        "jedoch", "somit", "so", "hier", "auch", "ebenso", "letzteres", "letztere", "ersteres", "sein", "seine", "seinen",
        "ihr", "ihre", "ihren", "dessen", "deren", "trotzdem", "danach", "daraufhin", "dagegen", "darüber",
    )
    private val EN_REF = setOf(
        "it", "they", "he", "she", "this", "these", "that", "those", "there", "however", "also", "thus", "therefore", "its",
        "their", "his", "her", "instead", "moreover", "furthermore", "later", "here", "then", "nevertheless", "afterwards",
    )

    internal fun startsWithReference(s: String, lang: String): Boolean {
        val w = s.trimStart().takeWhile { it.isLetter() }.lowercase()
        return w in (if (lang == "en") EN_REF else DE_REF)
    }
}
