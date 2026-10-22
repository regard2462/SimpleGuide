package app.simpleguide.tour

import app.simpleguide.GuideApp
import app.simpleguide.data.GuideSettings
import app.simpleguide.data.Place
import app.simpleguide.geo.Side
import app.simpleguide.text.Classifier
import app.simpleguide.text.Narration
import app.simpleguide.text.PlaceText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Was zu einem Ort erzählt würde, zum Lesen statt Hören (und zum Prüfen der Textregeln): dieselbe Erzählung wie
 * in der Tour, dazu alle Häppchen für "Mehr erzählen". Lädt fehlende Einleitung und Volltext, wenn online erlaubt.
 */
object Preview {
    data class Text(val lines: List<String>, val more: List<List<String>>)

    suspend fun of(app: GuideApp, place: Place, side: Side, distanceM: Int, s: GuideSettings): Text {
        var pl = place
        if (pl.intro == null && s.online) pl = app.repo.ensureIntros(pl.lang, listOf(pl)).first()
        val full = app.repo.fullText(pl, s.online)
        return withContext(Dispatchers.Default) {
            // Wie in der Tour: Straßen als "Du bist jetzt in der …", sonst mit Richtung und Entfernung
            val inside = Classifier.isStreet(pl.title)
            val script = Narration(pl.lang).place(
                PlaceText(pl.title, pl.description, pl.intro, full, pl.category),
                side, distanceM, s.detail, inside,
            )
            Text(script.lines.map { it.text }, script.more.map { chunk -> chunk.map { it.text } })
        }
    }
}
