package app.simpleguide.text

import app.simpleguide.geo.Side
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class WikiTextTest {
    @Test
    fun klammernUndVerweiseFallenWeg() {
        assertEquals(
            "Das Schwabentor ist das jüngere der beiden noch erhaltenen Stadttore der mittelalterlichen Stadtbefestigung von Freiburg im Breisgau.",
            WikiText.clean("Das Schwabentor (im Mittelalter auch Obertor genannt) ist das jüngere der beiden noch erhaltenen Stadttore (→ Martinstor) der mittelalterlichen Stadtbefestigung von Freiburg im Breisgau."),
        )
        assertEquals("Berlin ist die Hauptstadt.", WikiText.clean("Berlin [bɛʁˈliːn] (anhören (ⓘ)) ist die Hauptstadt."))
        assertEquals("Er lebte in Rom.", WikiText.clean("Er (1500–1560 (genauer: 1561)) lebte in Rom ."))
    }

    @Test
    fun abschnitte() {
        val s = WikiText.sections("Einleitung eins.\n\n\n== Geschichte ==\n\nAbsatz A.\nAbsatz B.\n\n=== Unter ===\nTief.\n== Weblinks ==\n")
        assertEquals(listOf("", "Geschichte", "Unter"), s.map { it.title })
        assertEquals(listOf("Absatz A.", "Absatz B."), s[1].paragraphs)
        assertTrue(WikiText.skipSection("Einzelnachweise"))
        assertTrue(WikiText.skipSection("Literatur"))
        assertFalse(WikiText.skipSection("Geschichte"))
    }

    @Test
    fun unvorlesbares() {
        assertFalse(WikiText.speakable("Stützweiten: 25,50 + 30,00 + 30,45 m."))
        assertFalse(WikiText.speakable("Siehe unten"))
        assertTrue(WikiText.speakable("Der Brunnen ist einer der zentralen Punkte der Stadt."))
        assertEquals("Schwabentor", WikiText.cleanTitle("Schwabentor (Freiburg im Breisgau)"))
    }
}

class SubjectParserTest {
    private fun de(sentence: String, title: String) = SubjectParser.parse(sentence, title, "de")

    @Test
    fun maskulin() {
        val s = de("Der Bertoldsbrunnen ist ein Denkmal in der Altstadt von Freiburg im Breisgau.", "Bertoldsbrunnen")
        assertEquals("der Bertoldsbrunnen", s.phrase.nominative)
        assertEquals("den Bertoldsbrunnen", s.phrase.accusative)
        assertEquals("dem Bertoldsbrunnen", s.phrase.dative)
        assertEquals("Er ist ein Denkmal in der Altstadt von Freiburg im Breisgau.", s.sentenceWithPronoun)
    }

    @Test
    fun ortsangabeImSubjekt() {
        val s = de("Der Basler Hof in der Kaiser-Joseph-Straße in Freiburg ist ein spätgotisches Gebäude.", "Basler Hof")
        assertEquals("den Basler Hof", s.phrase.accusative)
        assertEquals("Er ist ein spätgotisches Gebäude.", s.sentenceWithPronoun)
    }

    @Test
    fun beifuegungMitKomma() {
        val s = de("Das Münster Unserer Lieben Frau zu Freiburg, kurz Freiburger Münster, ist die römisch-katholische Stadtpfarrkirche von Freiburg im Breisgau.", "Freiburger Münster")
        assertEquals("das Freiburger Münster", s.phrase.nominative)
        assertEquals("dem Freiburger Münster", s.phrase.dative)
        assertEquals("Es ist die römisch-katholische Stadtpfarrkirche von Freiburg im Breisgau.", s.sentenceWithPronoun)
    }

    @Test
    fun adjektiveWerdenGebeugt() {
        val s = de("Das Historische Kaufhaus ist eines der herausragenden Gebäude in Freiburg im Breisgau.", "Historisches Kaufhaus (Freiburg im Breisgau)")
        assertEquals("das Historische Kaufhaus", s.phrase.accusative)
        assertEquals("dem Historischen Kaufhaus", s.phrase.dative)
        val m = SubjectParser.germanPhrase("der", "Alte Turm", Gender.MASCULINE)
        assertEquals("den Alten Turm", m.accusative)
        val f = SubjectParser.germanPhrase("die", "Alte Brücke", Gender.FEMININE)
        assertEquals("die Alte Brücke", f.accusative)
        assertEquals("der Alten Brücke", f.dative)
        assertEquals("der Steinernen Brücke", SubjectParser.germanPhrase("die", "Steinerne Brücke", Gender.FEMININE).dative)
        assertEquals("dem Städtischen Museum", SubjectParser.germanPhrase("das", "Städtische Museum", Gender.NEUTER).dative)
        // Hauptwörter auf -e vor einem Zusatz sind keine Adjektive
        assertEquals("der Kirche St. Martin", SubjectParser.germanPhrase("die", "Kirche St. Martin", Gender.FEMININE).dative)
        assertEquals("der Kaserne Nord", SubjectParser.germanPhrase("die", "Kaserne Nord", Gender.FEMININE).dative)
    }

    @Test
    fun feminin() {
        val s = de("Die Kirche St. Martin ist eine römisch-katholische Pfarrkirche am Rathausplatz.", "St. Martin (Freiburg-Altstadt)")
        assertEquals("die Kirche St. Martin", s.phrase.accusative)
        assertEquals("Sie ist eine römisch-katholische Pfarrkirche am Rathausplatz.", s.sentenceWithPronoun)
    }

    @Test
    fun nebensatzImSubjektBleibtUnverändert() {
        val s = de("Das Haus, das 1500 erbaut wurde, ist ein Denkmal.", "Haus Sonne")
        assertNull(s.sentenceWithPronoun)
    }

    @Test
    fun ohneArtikel() {
        val s = de("St. Peter ist eine Kirche in Zürich.", "St. Peter (Zürich)")
        assertEquals("St. Peter", s.phrase.nominative)
        assertFalse(s.phrase.hasArticle)
    }

    @Test
    fun titelNurAlsGanzesWort() {
        // "Turm" ist nicht der Anfang von "Turmhof"
        val s = de("Der Turmhof ist ein Gebäude in der Altstadt.", "Turm (Freiburg)")
        assertEquals("den Turmhof", s.phrase.accusative)
    }

    @Test
    fun englisch() {
        val s = SubjectParser.parse("Tower Bridge is a Grade I listed combined bascule, suspension, and, until 1960, cantilever bridge in London.", "Tower Bridge", "en")
        assertEquals("Tower Bridge", s.phrase.nominative)
        assertTrue(s.sentenceWithPronoun!!.startsWith("It is a Grade I listed"))
    }
}

class ClassifierTest {
    private fun de(desc: String?, title: String = "X", first: String? = null) = Classifier.classify(title, desc, first, "de")

    @Test
    fun beschreibungen() {
        assertEquals(Category.SACRED, de("Kirchengebäude in Freiburg im Breisgau, Kathedralkirche des r.-k. Erzbistums Freiburg"))
        assertEquals(Category.INSTITUTION, de("Verein zur baulichen Erhaltung des Freiburger Münsters"))
        assertEquals(Category.HISTORIC, de("historisches Stadttor in Deutschland"))
        assertEquals(Category.MONUMENT, de("Skulptur von Nikolaus Röslmeir"))
        assertEquals(Category.ARTWORK, de("Gemälde von Hans Baldung"))
        assertEquals(Category.ARTWORK, de("Musikinstrument"))
        assertEquals(Category.PLACE, de("Straße in Freiburg im Breisgau, Baden-Württemberg"))
        assertEquals(Category.PLACE, de("Platz um das Freiburger Münster in Freiburg im Breisgau"))
        assertEquals(Category.DISTRICT, de("Stadtteil von Freiburg im Breisgau, Baden-Württemberg, Deutschland"))
        assertEquals(Category.DISTRICT, de("Großstadt in Baden-Württemberg, Deutschland"))
        assertEquals(Category.BUILDING, de("Fußgängerbrücke in Freiburg im Breisgau"))
        assertEquals(Category.BUILDING, de("Spätbarockes Künstlerhaus"))
        assertEquals(Category.BUILDING, de("Hotel in Deutschland"))
        assertEquals(Category.INSTITUTION, de("Landgericht in Baden-Württemberg"))
        assertEquals(Category.INSTITUTION, de("ehemaliger Automobilhersteller"))
        assertEquals(Category.INSTITUTION, de("Kreditinstitut mit Sitz in Freiburg im Breisgau"))
        assertEquals(Category.CULTURE, de("Museum in Freiburg im Breisgau"))
        assertEquals(Category.MONUMENT, de("Siegesdenkmal in Freiburg im Breisgau"))
        assertEquals(Category.MONUMENT, de("Rekonstruktion eines mittelalterlichen Brunnens"))
        assertEquals(Category.BUILDING, de("Bauwerk in der Herrenstraße von Freiburg im Breisgau"))
        assertEquals(Category.SACRED, de("Kloster in Deutschland"))
    }

    @Test
    fun ohneBeschreibung() {
        assertEquals(Category.BUILDING, de(null, "Kornhaus (Freiburg im Breisgau)", "Das Kornhaus ist ein Gebäude am Münsterplatz."))
        assertEquals(Category.HISTORIC, de(null, "Wachturm Freiburg"))
    }

    @Test
    fun englisch() {
        assertEquals(Category.SACRED, Classifier.classify("St Paul's Cathedral", "Anglican cathedral in London", null, "en"))
        assertEquals(Category.MONUMENT, Classifier.classify("Nelson's Column", "monument in Trafalgar Square, London", null, "en"))
        assertEquals(Category.PLACE, Classifier.classify("Trafalgar Square", "public square in the City of Westminster, London", null, "en"))
        assertEquals(Category.BUILDING, Classifier.classify("Tower Bridge", "bridge in London, England", null, "en"))
    }
}

class NarrationTest {
    private fun place(name: String, withFull: Boolean): PlaceText {
        val a = Fixtures.load(name)
        val lang = name.substring(0, 2)
        val first = WikiText.sentences(a.intro.lineSequence().first(), lang).firstOrNull()
        return PlaceText(a.title, a.description, a.intro, if (withFull) a.text else null, Classifier.classify(a.title, a.description, first, lang))
    }

    /** Gibt alle Ansagen aus (zum Nachlesen im Testbericht) und prüft Grundsätzliches. */
    @Test
    fun alleFixturesErgebenSinnvolleAnsagen() {
        for (name in Fixtures.GERMAN + Fixtures.ENGLISH) {
            val lang = name.substring(0, 2)
            for (detail in Detail.entries) {
                val p = place(name, withFull = detail == Detail.LONG)
                val script = Narration(lang, Random(1)).place(p, Side.RIGHT, 60, detail)
                val words = script.lines.sumOf { WikiText.wordCount(it.text) }
                println("---- $name [$detail, ${p.category}] $words Wörter, ${script.more.size} mehr")
                script.lines.forEach { println("  » ${it.text}") }
                assertTrue(name, script.lines.size >= 2)
                assertTrue("$name zu lang: $words", words <= detail.leadWords + 160)
                script.lines.forEach { l ->
                    assertFalse("$name: Klammer in \"${l.text}\"", l.text.contains('(') || l.text.contains('['))
                    assertTrue("$name: leer", l.text.isNotBlank())
                }
            }
        }
    }

    @Test
    fun bertoldsbrunnen() {
        val script = Narration("de", Random(0)).place(place("de_Bertoldsbrunnen", false), Side.RIGHT, 40, Detail.SHORT)
        val first = script.lines[0].text
        assertTrue(first, first.contains("den Bertoldsbrunnen") || first.contains("der Bertoldsbrunnen"))
        assertTrue(script.lines[1].text, script.lines[1].text.startsWith("Er ist ein Denkmal"))
    }

    @Test
    fun mehrErzaehlenHatHoehepunkte() {
        val script = Narration("de", Random(0)).place(place("de_Schwabentor_Freiburg_im_Breisgau", true), Side.LEFT, 80, Detail.MEDIUM)
        assertTrue(script.more.isNotEmpty())
        println(script.more.joinToString("\n\n") { c -> c.joinToString(" ") { it.text } })
    }

    @Test
    fun drinnenUndStrasse() {
        val n = Narration("de", Random(0))
        val platz = SubjectParser.parse("Der Münsterplatz in der Freiburger Altstadt ist die gepflasterte Fläche.", "Münsterplatz (Freiburg im Breisgau)", "de").phrase
        assertEquals("Du bist jetzt auf dem Münsterplatz.", n.announce(platz, Category.PLACE, Side.UNKNOWN, 10, inside = true))
        val brunnen = SubjectParser.germanPhrase("der", "Bertoldsbrunnen", Gender.MASCULINE)
        assertEquals("Du bist jetzt am Bertoldsbrunnen.", n.announce(brunnen, Category.MONUMENT, Side.UNKNOWN, 5, inside = true))
        // Ganz nah dran, aber nicht "drin": "bei dem" wird zu "beim"
        val nah = n.announce(brunnen, Category.MONUMENT, Side.RIGHT, 10, inside = false)
        assertTrue(nah, nah == "Du stehst jetzt direkt beim Bertoldsbrunnen." || nah == "Gleich neben dir: der Bertoldsbrunnen.")
        val kirche = SubjectParser.germanPhrase("die", "Kirche St. Martin", Gender.FEMININE)
        assertEquals("Du bist jetzt in der Kirche St. Martin.", n.announce(kirche, Category.SACRED, Side.UNKNOWN, 5, inside = true))
        assertEquals("Du bist jetzt in der Kaiser-Joseph-Straße.", n.street("Kaiser-Joseph-Straße"))
        assertEquals("Du bist jetzt auf dem Rathausplatz.", n.street("Rathausplatz"))
        assertEquals("Du bist jetzt im Rotteckring.", n.street("Rotteckring"))
        assertEquals("Du bist jetzt An der Mehlwaage.", n.street("An der Mehlwaage"))
        assertEquals("Du bist jetzt im Kaufhausgässle.", n.street("Kaufhausgässle"))
    }

    @Test
    fun ueberblick() {
        val n = Narration("de", Random(0))
        val a = SubjectParser.germanPhrase("der", "Bertoldsbrunnen", Gender.MASCULINE)
        val b = SubjectParser.germanPhrase("das", "Historische Kaufhaus", Gender.NEUTER)
        val c = NounPhrase.bare("St. Martin")
        assertEquals(
            "Hier in der Nähe gibt es rechts den Bertoldsbrunnen, vor dir das Historische Kaufhaus und links St. Martin.",
            n.overview(listOf(a to Side.RIGHT, b to Side.AHEAD, c to Side.LEFT)),
        )
    }

    @Test
    fun entfernung() {
        val n = Narration("de")
        assertNull(n.distancePhrase(20))
        assertEquals("etwa 50 Meter entfernt", n.distancePhrase(47))
        assertEquals("etwa 150 Meter entfernt", n.distancePhrase(160))
        assertNotNull(Narration("en").distancePhrase(80))
    }
}
