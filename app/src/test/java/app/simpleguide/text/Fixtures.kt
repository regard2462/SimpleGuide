package app.simpleguide.text

/** Echte Wikipedia-Artikel (Klartext mit Abschnitten), Stand Oktober 2026. Datei: Beschreibung, Titel, Text. */
object Fixtures {
    data class Article(val description: String?, val title: String, val text: String) {
        /** Nur die Einleitung, wie sie die App ohne Volltext hat. */
        val intro: String get() = text.substringBefore("\n==").trim()
    }

    fun load(name: String): Article {
        val raw = javaClass.getResourceAsStream("/wiki/$name.txt")!!.readBytes().toString(Charsets.UTF_8).replace("\r", "")
        val lines = raw.split('\n', limit = 3)
        return Article(lines[0].ifBlank { null }, lines[1], lines[2])
    }

    val GERMAN = listOf(
        "de_Bertoldsbrunnen", "de_Schwabentor_Freiburg_im_Breisgau", "de_Freiburger_Muenster", "de_Haus_zum_Walfisch",
        "de_St_Martin_Freiburg_Altstadt", "de_Muensterplatz_Freiburg_im_Breisgau", "de_Wentzingerhaus",
        "de_Historisches_Kaufhaus_Freiburg_im_Breisgau", "de_Siegesdenkmal_Freiburg_im_Breisgau",
        "de_Kaiser_Joseph_Strasse_Freiburg_im_Breisgau", "de_Basler_Hof", "de_Martinstor",
    )
    val ENGLISH = listOf("en_Tower_Bridge", "en_St_Paul_s_Cathedral", "en_Nelson_s_Column", "en_Trafalgar_Square")
}
