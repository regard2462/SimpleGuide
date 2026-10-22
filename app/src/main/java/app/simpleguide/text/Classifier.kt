package app.simpleguide.text

/**
 * Art eines Ortes. [weight] ordnet, was zuerst erzählt wird; [radiusFactor] vergrößert den Auslöseradius
 * für Dinge, die groß sind oder weit sichtbar (Plätze, Parks, Kirchen); [visible]: man kann es von der
 * Straße aus sehen (für "rechts von dir siehst du …").
 */
enum class Category(val weight: Double, val defaultOn: Boolean, val radiusFactor: Double, val visible: Boolean) {
    SACRED(1.0, true, 1.3, true),
    HISTORIC(1.0, true, 1.3, true),
    MONUMENT(0.9, true, 1.0, true),
    CULTURE(0.9, true, 1.0, true),
    BUILDING(0.8, true, 1.0, true),
    NATURE(0.7, true, 1.6, true),
    PLACE(0.6, true, 1.4, true),
    OTHER(0.5, true, 1.0, false),
    ARTWORK(0.4, false, 0.8, false),
    DISTRICT(0.4, false, 2.5, false),
    TRANSPORT(0.3, false, 1.0, true),
    INSTITUTION(0.3, false, 1.0, false),
}

/**
 * Ordnet Wikipedia-Artikel nach der Kurzbeschreibung aus Wikidata ("Kirchengebäude in Freiburg"), sonst nach
 * der Aussage des ersten Satzes ("… ist ein spätgotisches Bürgerhaus …") und zuletzt nach dem Titel.
 * Entscheidend ist das Kopfwort vor der ersten Präposition, damit "Verein zur Erhaltung des Münsters" ein
 * Verein bleibt und keine Kirche wird.
 */
object Classifier {

    private val STREET_NAME = Regex(
        """(straße|strasse|gasse|allee|\w+weg|\w+ring|chaussee|damm|zeile|promenade|\bstreet|\broad|\bavenue|\blane|\bboulevard)$""",
        RegexOption.IGNORE_CASE,
    )

    /** Eine Straße (lang und schmal, ihr Wikipedia-Punkt ist nur die Mitte), kein Platz. */
    fun isStreet(name: String): Boolean = STREET_NAME.containsMatchIn(WikiText.cleanTitle(name).trim())

    fun classify(title: String, description: String?, firstSentence: String?, lang: String): Category {
        val rules = if (lang == "en") EN else DE
        val desc = description?.lowercase()?.trim().orEmpty()
        if (desc.isNotEmpty()) {
            match(head(desc, lang), rules)?.let { return it }
            // Ganze Beschreibung ohne Ortsangaben, sonst wird aus "… in Heidelberg" ein Berg
            match(PLACE_NAMES.replace(description!!, " ").lowercase(), rules)?.let { return it }
        }
        predicate(firstSentence, lang)?.let { p -> match(head(p, lang), rules)?.let { return it } }
        match(WikiText.cleanTitle(title).lowercase(), rules)?.let { return it }
        return Category.OTHER
    }

    /** Ortsangabe mit Eigennamen: "in Freiburg im Breisgau", "bei Köln", "of London". */
    private val PLACE_NAMES = Regex("""\s(?:in|im|am|an|bei|nahe|von|vom|aus|of|near|at)\s+(?:der\s+|dem\s+|den\s+|the\s+)?\p{Lu}[^,;]*""")

    private fun match(text: String, rules: List<Pair<Regex, Category>>): Category? =
        if (text.isBlank()) null else rules.firstOrNull { it.first.containsMatchIn(text) }?.second

    private val HEAD_END_DE = Regex("""\s(in|im|am|an|auf|bei|zur|zum|zu|des|der|von|vom|um|mit|aus|für|über|unter|nahe|zwischen|nach|seit|eines|einer|als)\s|,|;""")
    private val HEAD_END_EN = Regex("""\s(in|at|on|of|near|by|for|from|with|between|built|located|which|that)\s|,|;""")

    /** Kopf einer Beschreibung: alles vor der ersten Präposition oder dem ersten Komma. */
    internal fun head(text: String, lang: String): String {
        val rx = if (lang == "en") HEAD_END_EN else HEAD_END_DE
        val m = rx.find(text) ?: return text
        return text.substring(0, m.range.first).trim()
    }

    private val PREDICATE_DE = Regex("""\b(?:ist|war|sind|waren)\s+(?:heute\s+|eines?\s+der\s+|einer?\s+der\s+)?(?:ein|eine|einer|eines|der|die|das)\s+(.{3,120}?)(?:[.,;]|$)""", RegexOption.IGNORE_CASE)
    private val PREDICATE_EN = Regex("""\b(?:is|was|are|were)\s+(?:one\s+of\s+)?(?:a|an|the)\s+(.{3,120}?)(?:[.,;]|$)""", RegexOption.IGNORE_CASE)

    /** "Das Haus zum Walfisch ist ein spätgotisches Bürgerhaus in …" → "spätgotisches bürgerhaus in …". */
    internal fun predicate(sentence: String?, lang: String): String? {
        sentence ?: return null
        val m = (if (lang == "en") PREDICATE_EN else PREDICATE_DE).find(sentence) ?: return null
        return m.groupValues[1].lowercase()
    }

    private fun rx(p: String) = Regex(p)

    // Reihenfolge zählt: Spezielles vor Allgemeinem.
    private val DE: List<Pair<Regex, Category>> = listOf(
        rx("""stadtteil|ortsteil|stadtbezirk|\bbezirk\b|stadtviertel|\bviertel\b|quartier|siedlung|großstadt|kleinstadt|mittelstadt|kreisstadt|\bstadt in\b|\bgemeinde in\b|\bdorf\b|stadtgebiet|landkreis|bundesland|\bregion\b""") to Category.DISTRICT,
        rx("""gemälde|altarbild|\baltar\b|hochaltar|flügelaltar|\borgel|glocke|bildteppich|wandteppich|musikinstrument|glasfenster|kirchenfenster|handschrift|kodex|reliquie|grabmal|epitaph|kanzel|taufstein|wandmalerei|fresko|mosaik""") to Category.ARTWORK,
        rx("""baudenkmal|kulturdenkmal|denkmalgeschützt""") to Category.BUILDING,
        rx("""kirche|kapelle|\bdom\b|domkirche|\w+dom\b|münster|kathedrale|basilika|kloster|abtei|\bstift\b|stiftskirche|damenstift|priorat|konvent|synagoge|moschee|tempel|pagode|wallfahrt|kirchengebäude|gotteshaus|kartause|komturei|kommende""") to Category.SACRED,
        rx("""bahnhof|haltestelle|\bstation\b|flughafen|parkhaus|\bhafen\b|straßenbahn|stadtbahn|\bu-bahn|\bs-bahn|tiefgarage|busbahnhof|seilbahn|\blinie\b""") to Category.TRANSPORT,
        rx("""denkmal|brunnen|skulptur|statue|\bplastik\b|mahnmal|gedenkstätte|gedenkstein|gedenktafel|stolperstein|monument|obelisk|ehrenmal|kriegerdenkmal|bildstock|wegkreuz|standbild|reiterstandbild|kunstwerk|installation""") to Category.MONUMENT,
        rx("""museum|theater|oper\b|opernhaus|galerie|bibliothek|kino\b|konzerthaus|konzertsaal|philharmonie|kunsthalle|kunstverein|archiv|planetarium|sternwarte|ausstellung|kulturzentrum""") to Category.CULTURE,
        rx("""\bburg\b|burgruine|schloss|palast|palais|residenz|stadttor|\btor\b|torbau|torturm|torhaus|\bturm\b|\w+turm\b|stadtmauer|stadtbefestigung|befestigung|festung|zitadelle|bastion|\bruine\b|wehrturm|wachturm|bollwerk|schanze""") to Category.HISTORIC,
        rx("""\bpark\b|\w+park\b|garten|gärten|friedhof|\bsee\b|\w+see\b|weiher|\bteich\b|\bfluss\b|\bbach\b|\w+bach\b|kanal|\bwald\b|\w+wald\b|\bberg\b|\w+berg\b|hügel|\binsel\b|\bzoo\b|tierpark|grünanlage|grünfläche|naturdenkmal|naturschutzgebiet|aussichtspunkt|\bquelle\b|wasserfall|\bhöhle\b|\bbaum\b|\blinde\b|\beiche\b""") to Category.NATURE,
        rx("""straße|strasse|gasse|\bplatz\b|\w+platz\b|allee|\w+weg\b|\bweg\b|\w+ring\b|\bufer\b|\w+ufer\b|promenade|boulevard|\bmarkt\b|\w+markt\b|fußgängerzone|passage|\bdamm\b|\w+damm\b|\bkai\b|chaussee|\bstiege\b|treppe""") to Category.PLACE,
        rx("""verein|verband|unternehmen|\bfirma\b|hersteller|organisation|stiftung|gesellschaft|\bpartei\b|schule|gymnasium|universität|hochschule|institut|behörde|gericht|\bamt\b|\w+amt\b|ministerium|\bbank\b|\w+bank\b|sparkasse|kreditinstitut|zeitung|verlag|studentenverbindung|\bcorps\b|burschenschaft|division|regiment|bataillon|initiative|kirchengemeinde|\bgemeinde\b|bistum|erzbistum|dekanat|orden\b|\bclub\b|\bklub\b|mannschaft|sportverein|fußballverein|\bband\b|ensemble|\bchor\b|orchester|genossenschaft|konzern|brauerei|seminar|einrichtung|veranstaltung|festival|messe\b|\bfest\b|ereignis|schlacht|brand\b|anschlag|unfall|person\b|familie|geschlecht\b|adelsgeschlecht|sender|rundfunk|projekt|wettbewerb""") to Category.INSTITUTION,
        rx("""gebäude|bauwerk|\bhaus\b|\w+haus\b|häuser|hochhaus|villa|rathaus|bürgerhaus|\bhof\b|\w+hof\b|brücke|\bsteg\b|\w+steg\b|hotel|kaufhaus|warenhaus|gaststätte|wirtshaus|\bmühle\b|kaserne|fabrik|\bhalle\b|\w+halle\b|speicher|bunker|stadion|arena|wohnanlage|bau\b|\w+bau\b|anlage|komplex|zentrum|\bpassage\b|tunnel|viadukt|aquädukt|wasserturm|leuchtturm|kran\b|pavillon""") to Category.BUILDING,
    )

    private val EN: List<Pair<Regex, Category>> = listOf(
        rx("""neighbou?rhood|\bdistrict\b|\bborough\b|\bquarter\b|\bsuburb\b|\bcity in\b|\btown in\b|\bvillage\b|municipality|\bcounty\b|\bregion\b|\bward\b|\barea of\b""") to Category.DISTRICT,
        rx("""painting|altarpiece|\baltar\b|\borgan\b|\bbell\b|tapestry|musical instrument|stained glass|manuscript|codex|relic|\btomb\b|epitaph|pulpit|\bfont\b|mural|fresco|mosaic""") to Category.ARTWORK,
        rx("""church|chapel|cathedral|minster|basilica|abbey|monastery|priory|convent|synagogue|mosque|temple|pagoda|shrine|place of worship|friary|nunnery""") to Category.SACRED,
        rx("""railway station|train station|\bstation\b|tram stop|bus stop|\bstop\b|airport|car park|parking|\bport\b|harbou?r|metro|subway|underground|tramway|\bline\b|cable car""") to Category.TRANSPORT,
        rx("""monument|memorial|fountain|sculpture|statue|obelisk|cenotaph|war memorial|plaque|artwork|installation|column\b""") to Category.MONUMENT,
        rx("""museum|theatre|theater|opera|gallery|library|cinema|concert hall|arts centre|art center|archive|planetarium|observatory|exhibition|cultural centre""") to Category.CULTURE,
        rx("""castle|palace|city gate|\bgate\b|gatehouse|\btower\b|city wall|town wall|fortification|fortress|fort\b|citadel|bastion|\bruins?\b|keep\b|stronghold|rampart""") to Category.HISTORIC,
        rx("""\bpark\b|garden|cemetery|graveyard|\blake\b|\bpond\b|\briver\b|\bstream\b|\bcanal\b|\bforest\b|\bwood\b|\bhill\b|mountain|\bisland\b|\bzoo\b|nature reserve|viewpoint|\bspring\b|waterfall|\bcave\b|\btree\b|green space|\bcommon\b""") to Category.NATURE,
        rx("""\bstreet\b|\bsquare\b|\bavenue\b|\broad\b|\blane\b|\bplaza\b|boulevard|promenade|\bmarket\b|pedestrian zone|arcade|embankment|\bquay\b|\bsteps\b|\balley\b|\bcrescent\b|\bterrace\b|\bmews\b|\bplace\b""") to Category.PLACE,
        rx("""association|\bclub\b|company|corporation|manufacturer|organi[sz]ation|foundation|\bsociety\b|political party|\bschool\b|university|college|institute|agency|\bcourt\b|ministry|\bbank\b|newspaper|publisher|fraternity|division|regiment|battalion|initiative|parish|diocese|archdiocese|\border\b|\bteam\b|\bband\b|ensemble|\bchoir\b|orchestra|cooperative|brewery|seminary|institution|\bevent\b|festival|\bfair\b|\bbattle\b|\bfire\b|attack|accident|\bperson\b|\bfamily\b|broadcaster|radio station|project|competition""") to Category.INSTITUTION,
        rx("""building|\bhouse\b|skyscraper|tower block|\bvilla\b|town hall|city hall|\bhall\b|\bbridge\b|footbridge|\bhotel\b|department store|\bpub\b|\binn\b|\bmill\b|barracks|factory|warehouse|bunker|stadium|arena|complex|centre|center|tunnel|viaduct|aqueduct|water tower|lighthouse|pavilion|mansion|manor|residence|structure""") to Category.BUILDING,
    )
}
