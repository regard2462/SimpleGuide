package app.simpleguide.data

import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.simpleguide.text.Category
import app.simpleguide.text.Detail
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.Locale

private val Context.dataStore by preferencesDataStore("settings")

/** Was mit Musik anderer Apps passiert, während erzählt wird. */
enum class Interrupt { PAUSE, DUCK }

/** Wann der Straßenname angesagt wird. */
enum class StreetMode { OFF, WITH_PLACES, ALWAYS }

data class GuideSettings(
    // ---- Inhalt
    /** Sprache der Wikipedia und der Ansagen (de, en). */
    val contentLang: String = if (Locale.getDefault().language == "en") "en" else "de",
    val detail: Detail = Detail.MEDIUM,
    /** Grundradius in Metern, in dem ein Ort angesagt wird (größere Orte bekommen mehr). */
    val radiusM: Int = 60,
    /** Mindestpause zwischen zwei Erzählungen in Sekunden. */
    val gapSec: Int = 15,
    /** Bereits gehörte Orte so lange nicht wiederholen (Stunden, 0 = nur in dieser Tour). */
    val repeatAfterHours: Int = 24,
    val categories: Set<Category> = Category.entries.filter { it.defaultOn }.toSet(),
    val streets: StreetMode = StreetMode.WITH_PLACES,
    /** Fehlende Artikel unterwegs aus dem Netz laden. */
    val online: Boolean = true,
    // ---- Wiedergabe
    val interrupt: Interrupt = Interrupt.PAUSE,
    val chime: Boolean = true,
    /** Kleine Karte auf der Tour-Seite. */
    val showMap: Boolean = true,
    // ---- Stimmen
    val packDe: String = "vits-piper-de_DE-thorsten-medium-int8",
    val packEn: String = "kokoro-int8-multi-lang-v1_0",
    val sidDe: Int = 0,
    val sidEn: Int = 3,
    val voiceSpeed: Float = 1.0f,
    val threads: Int = 2,
)

class SettingsStore(private val context: Context) {
    private object K {
        val LANG = stringPreferencesKey("content_lang")
        val DETAIL = stringPreferencesKey("detail")
        val RADIUS = intPreferencesKey("radius_m")
        val GAP = intPreferencesKey("gap_sec")
        val REPEAT = intPreferencesKey("repeat_hours")
        val CATEGORIES = stringPreferencesKey("categories")
        val STREETS = stringPreferencesKey("streets")
        val ONLINE = booleanPreferencesKey("online")
        val INTERRUPT = stringPreferencesKey("interrupt")
        val CHIME = booleanPreferencesKey("chime")
        val SHOW_MAP = booleanPreferencesKey("show_map")
        val PACK_DE = stringPreferencesKey("pack_de")
        val PACK_EN = stringPreferencesKey("pack_en")
        val SID_DE = intPreferencesKey("sid_de")
        val SID_EN = intPreferencesKey("sid_en")
        val SPEED = floatPreferencesKey("voice_speed")
        val THREADS = intPreferencesKey("threads")
    }

    private inline fun <reified E : Enum<E>> enumOr(v: String?, d: E): E = v?.let { runCatching { enumValueOf<E>(it) }.getOrNull() } ?: d

    private fun read(p: Preferences): GuideSettings {
        val d = GuideSettings()
        return GuideSettings(
            contentLang = p[K.LANG] ?: d.contentLang,
            detail = enumOr(p[K.DETAIL], d.detail),
            radiusM = p[K.RADIUS] ?: d.radiusM,
            gapSec = p[K.GAP] ?: d.gapSec,
            repeatAfterHours = p[K.REPEAT] ?: d.repeatAfterHours,
            categories = p[K.CATEGORIES]?.split(',')?.mapNotNull { n -> Category.entries.firstOrNull { it.name == n } }?.toSet() ?: d.categories,
            streets = enumOr(p[K.STREETS], d.streets),
            online = p[K.ONLINE] ?: d.online,
            interrupt = enumOr(p[K.INTERRUPT], d.interrupt),
            chime = p[K.CHIME] ?: d.chime,
            showMap = p[K.SHOW_MAP] ?: d.showMap,
            packDe = p[K.PACK_DE] ?: d.packDe,
            packEn = p[K.PACK_EN] ?: d.packEn,
            sidDe = p[K.SID_DE] ?: d.sidDe,
            sidEn = p[K.SID_EN] ?: d.sidEn,
            voiceSpeed = p[K.SPEED] ?: d.voiceSpeed,
            threads = p[K.THREADS] ?: d.threads,
        )
    }

    private fun write(p: MutablePreferences, n: GuideSettings) {
        p[K.LANG] = n.contentLang; p[K.DETAIL] = n.detail.name; p[K.RADIUS] = n.radiusM; p[K.GAP] = n.gapSec
        p[K.REPEAT] = n.repeatAfterHours; p[K.CATEGORIES] = n.categories.joinToString(",") { it.name }
        p[K.STREETS] = n.streets.name; p[K.ONLINE] = n.online; p[K.INTERRUPT] = n.interrupt.name; p[K.CHIME] = n.chime; p[K.SHOW_MAP] = n.showMap
        p[K.PACK_DE] = n.packDe; p[K.PACK_EN] = n.packEn; p[K.SID_DE] = n.sidDe; p[K.SID_EN] = n.sidEn
        p[K.SPEED] = n.voiceSpeed; p[K.THREADS] = n.threads
    }

    val flow: Flow<GuideSettings> = context.dataStore.data.map { read(it) }

    suspend fun current(): GuideSettings = flow.first()

    suspend fun update(block: (GuideSettings) -> GuideSettings) {
        context.dataStore.edit { p -> write(p, block(read(p))) }
    }
}
