package app.simpleguide.text

/** Grammatisches Geschlecht des Ortsnamens; PLURAL für "Die Orgeln … sind". */
enum class Gender { MASCULINE, FEMININE, NEUTER, PLURAL }

/**
 * Der Name eines Ortes, wie man ihn in einen Satz einbaut: "der Bertoldsbrunnen" (Nominativ),
 * "den Bertoldsbrunnen" (Akkusativ), "dem Bertoldsbrunnen" (Dativ), dazu das Pronomen für den nächsten Satz.
 * Ohne Artikel (Eigennamen wie "St. Martin") sind alle Formen gleich und [pronoun] ist leer.
 */
data class NounPhrase(
    val nominative: String,
    val accusative: String,
    val dative: String,
    val gender: Gender?,
    /** "Er", "Sie", "Es", "It", "They" oder leer. */
    val pronoun: String,
) {
    val hasArticle get() = gender != null

    companion object {
        fun bare(name: String) = NounPhrase(name, name, name, null, "")
    }
}

/**
 * Ergebnis der Subjekt-Erkennung im ersten Satz: der Name als [NounPhrase] und, wenn es sicher geht, derselbe
 * Satz mit Pronomen statt Subjekt ("Er ist ein Denkmal in der Altstadt …"). So klingt die Ansage wie ein
 * Gästeführer und nicht wie ein vorgelesener Lexikonartikel.
 */
data class Subject(val phrase: NounPhrase, val sentenceWithPronoun: String?)

object SubjectParser {

    fun parse(firstSentence: String?, title: String, lang: String): Subject {
        val clean = WikiText.cleanTitle(title)
        val s = firstSentence?.trim().orEmpty()
        // Bruchstück ohne Satzanfang: kein Subjekt erkennbar
        if (s.firstOrNull()?.isUpperCase() != true) return Subject(NounPhrase.bare(clean), null)
        return (if (lang == "en") english(s, clean) else german(s, clean)) ?: Subject(NounPhrase.bare(clean), null)
    }

    /** Beginnt [subject] mit dem ganzen Wort [title]? ("Turm" ist nicht der Anfang von "Turmstraße".) */
    private fun startsWithWord(subject: String, title: String): Boolean =
        title.isNotEmpty() && subject.startsWith(title, ignoreCase = true) &&
            subject.getOrNull(title.length)?.isLetterOrDigit() != true

    // ================================================================ Deutsch

    private const val DE_VERBS = "ist|war|sind|waren|gilt|galt|befindet|befand|steht|stand|liegt|lag|bildet|bildete|bezeichnet|" +
        "nennt|heißt|hieß|wurde|wird|wurden|werden|gehört|gehörte|zählt|zählte|beherbergt|beherbergte|erinnert|verbindet|führt|" +
        "verläuft|umfasst|stellt|dient|diente|trägt|trug|zeigt|hat|hatte|besteht|bestand|entstand|erstreckt|prägt|beginnt|endet"

    private val DE_START = Regex("""^(Der|Die|Das)\s+(.+?),?\s+($DE_VERBS)\b(.*)$""", RegexOption.DOT_MATCHES_ALL)

    /** Nebensatz im Subjekt ("Das Haus, das 1500 erbaut wurde, ist …"): dann den Satz nicht umbauen. */
    private val RELATIVE = Regex(""",\s*(?:der|die|das|dem|den|dessen|deren|welche[rsm]?|wo|als|wie|nachdem|weil|obwohl|da)\b""")

    private val LOCATION_CUT = Regex("""\s(?:in|im|am|an|auf|bei|nahe|unweit|zwischen|von|vom|zu|zur|zum|an der|in der)\s""")

    private fun german(s: String, title: String): Subject? {
        val m = DE_START.matchEntire(s) ?: return null
        val article = m.groupValues[1]
        var subject = m.groupValues[2].trim().trimEnd(',').trim()
        val verb = m.groupValues[3]
        val rest = m.groupValues[4]
        if (subject.isEmpty() || subject.split(' ').size > 16) return null

        val plural = article == "Die" && verb in setOf("sind", "waren", "wurden", "werden")
        val gender = when (article) {
            "Der" -> Gender.MASCULINE
            "Das" -> Gender.NEUTER
            else -> if (plural) Gender.PLURAL else Gender.FEMININE
        }
        // Kern des Namens: Titel, wenn das Subjekt damit beginnt oder ihn als Beifügung enthält
        val name = when {
            startsWithWord(subject, title) -> subject.substring(0, title.length)
            subject.contains(',') && Regex("""\b${Regex.escape(title)}\b""", RegexOption.IGNORE_CASE).containsMatchIn(subject) -> title
            subject.contains(',') -> subject.substringBefore(',').trim()
            subject.split(' ').size > 6 -> LOCATION_CUT.find(subject)?.let { subject.substring(0, it.range.first) } ?: subject
            else -> subject
        }.trim()
        if (name.isEmpty()) return null

        val phrase = germanPhrase(article.lowercase(), name, gender)
        val canRewrite = !RELATIVE.containsMatchIn(subject) && rest.isNotBlank() && !rest.startsWith(",")
        val pronoun = phrase.pronoun
        val rewritten = if (canRewrite) "$pronoun $verb$rest".trim() else null
        return Subject(phrase, rewritten)
    }

    /** Adjektive, die in Ortsnamen vor dem Hauptwort stehen und gebeugt werden ("der Alten Brücke"). */
    private val NAME_ADJECTIVES = setOf(
        "alte", "neue", "große", "kleine", "hohe", "lange", "breite", "tiefe", "enge", "weite", "obere", "untere",
        "vordere", "hintere", "innere", "äußere", "mittlere", "rechte", "linke", "erste", "zweite", "dritte", "letzte",
        "rote", "weiße", "schwarze", "grüne", "blaue", "gelbe", "graue", "braune", "goldene", "silberne", "eiserne",
        "steinerne", "hölzerne", "gläserne", "bronzene", "kupferne", "schöne", "schiefe", "krumme", "runde", "wilde",
        "stille", "freie", "reiche", "fromme", "nahe", "ferne", "frühe", "späte", "ehemalige", "heilige",
    )

    /** "der", "Alte Turm", MASKULIN → den Alten Turm / dem Alten Turm. */
    internal fun germanPhrase(article: String, name: String, gender: Gender): NounPhrase {
        val words = name.split(' ')
        // Adjektive vor dem Hauptwort ("Alte Brücke", "Historische Kaufhaus") werden gebeugt. Ein Hauptwort auf -e
        // vor einem Zusatz ("Kirche St. Martin", "Kaserne Nord") dagegen nicht; nur bekannte Adjektive und
        // Adjektivendungen (-ische, -liche, -ige, -sche) zählen.
        fun isAdjective(i: Int, w: String): Boolean {
            if (i == words.lastIndex || w.length < 4 || !w[0].isLetter() || words.drop(i + 1).none { it.firstOrNull()?.isUpperCase() == true }) return false
            val l = w.lowercase()
            return l in NAME_ADJECTIVES || l.endsWith("ische") || l.endsWith("liche") || l.endsWith("ige") || l.endsWith("sche")
        }
        fun inflected(): String = words.mapIndexed { i, w -> if (isAdjective(i, w)) w + "n" else w }.joinToString(" ")
        val nom = "$article $name"
        val (acc, dat) = when (gender) {
            Gender.MASCULINE -> "den ${inflected()}" to "dem ${inflected()}"
            Gender.NEUTER -> nom to "dem ${inflected()}"
            Gender.FEMININE -> nom to "der ${inflected()}"
            Gender.PLURAL -> nom to "den ${inflected()}"
        }
        val pronoun = when (gender) {
            Gender.MASCULINE -> "Er"
            Gender.FEMININE, Gender.PLURAL -> "Sie"
            Gender.NEUTER -> "Es"
        }
        return NounPhrase(nom, acc, dat, gender, pronoun)
    }

    // ================================================================ Englisch

    private const val EN_VERBS = "is|was|are|were|stands|stood|lies|lay|serves|served|houses|housed|has|had|forms|formed|dates|runs|ran|became|remains"
    private val EN_START = Regex("""^(The\s+)?(.+?),?\s+($EN_VERBS)\b(.*)$""", RegexOption.DOT_MATCHES_ALL)
    private val EN_RELATIVE = Regex(""",\s*(?:which|who|whose|where|when|that|as|because|although|since)\b""")

    private fun english(s: String, title: String): Subject? {
        val m = EN_START.matchEntire(s) ?: return null
        val the = m.groupValues[1].isNotEmpty()
        val subject = m.groupValues[2].trim().trimEnd(',').trim()
        val verb = m.groupValues[3]
        val rest = m.groupValues[4]
        if (subject.isEmpty() || subject.split(' ').size > 14) return null
        val name = when {
            startsWithWord(subject, title) -> subject.substring(0, title.length)
            subject.contains(',') && subject.contains(title, ignoreCase = true) -> title
            subject.contains(',') -> subject.substringBefore(',').trim()
            else -> subject
        }
        val plural = verb == "are" || verb == "were"
        val np = if (the) "the $name" else name
        val pronoun = if (plural) "They" else "It"
        val phrase = NounPhrase(np, np, np, if (plural) Gender.PLURAL else Gender.NEUTER, pronoun)
        val canRewrite = !EN_RELATIVE.containsMatchIn(subject) && rest.isNotBlank() && !rest.startsWith(",")
        return Subject(phrase, if (canRewrite) "$pronoun $verb$rest".trim() else null)
    }
}
