package app.simpleguide.data

import android.content.Context
import android.util.Log
import app.simpleguide.geo.Geo
import app.simpleguide.geo.LatLon
import app.simpleguide.wiki.Osm
import app.simpleguide.wiki.WikiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Orte rund um den Standort: zuerst aus der Datenbank (heruntergeladene Städte, früher Geladenes), fehlt etwas
 * und ist Netz da, aus der Wikipedia nachgeladen und gespeichert. Einmal geladen, geht alles offline.
 */
class Repository(context: Context) {
    val db = PlaceDb(context)
    val wiki = WikiClient()
    private val areaLock = Mutex()
    private val textLock = Mutex()

    private val _online = MutableStateFlow<OnlineState>(OnlineState.Idle)
    /** Für die Anzeige: lädt gerade, Fehler, offline. */
    val onlineState: StateFlow<OnlineState> = _online

    sealed class OnlineState {
        data object Idle : OnlineState()
        data object Loading : OnlineState()
        data class Failed(val message: String) : OnlineState()
    }

    /**
     * Orte im Umkreis. Unveränderte Orte aus [previous] (nach pageId) werden wiederverwendet, damit ihre schon
     * berechnete Art und Bedeutung nicht bei jeder Auffrischung neu bestimmt werden muss.
     */
    suspend fun around(lang: String, p: LatLon, radiusM: Double, previous: Map<Long, Place> = emptyMap()): List<Place> =
        withContext(Dispatchers.IO) {
            db.near(lang, p, radiusM).map { fresh -> previous[fresh.pageId]?.takeIf { it == fresh } ?: fresh }.onEach { it.prepare() }
        }

    /**
     * Sorgt dafür, dass die Umgebung von [p] in der Datenbank ist: Umkreissuche mit [SEARCH_RADIUS] Metern,
     * neu, sobald man sich dem Rand des letzten Suchkreises nähert. In heruntergeladenen Städten nichts tun.
     * Liefert true, wenn neue Orte dazukamen.
     */
    suspend fun ensureArea(lang: String, p: LatLon): Boolean = areaLock.withLock {
        withContext(Dispatchers.IO) {
            if (db.coveredByCity(lang, p) || db.areaCovers(lang, p, AREA_MARGIN, AREA_MAX_AGE)) return@withContext false
            _online.value = OnlineState.Loading
            try {
                val hits = wiki.around(lang, p, SEARCH_RADIUS.toInt())
                db.upsertBasic(lang, hits)
                val missing = hits.map { it.pageId }.filter { id -> db.place(lang, id)?.hasDetails != true }
                missing.chunked(50).forEach { db.updateDetails(lang, wiki.details(lang, it)) }
                // Bei 500 Treffern (dichte Innenstadt) reicht die Suche nur bis zum fernsten Treffer
                val reached = if (hits.size >= 500) hits.maxOf { Geo.distance(p, LatLon(it.lat, it.lon)) } else SEARCH_RADIUS
                db.recordArea(lang, p, reached)
                _online.value = OnlineState.Idle
                hits.isNotEmpty()
            } catch (e: Exception) {
                Log.w(TAG, "ensureArea failed", e)
                _online.value = OnlineState.Failed(e.message ?: e.javaClass.simpleName)
                false
            }
        }
    }

    /** Fehlende Einleitungen der [places] nachladen (online, in Paketen zu 20). Liefert die aufgefrischten Orte. */
    suspend fun ensureIntros(lang: String, places: List<Place>): List<Place> = textLock.withLock {
        withContext(Dispatchers.IO) {
            val missing = places.filter { it.intro == null }.map { it.pageId }
            if (missing.isEmpty()) return@withContext places
            try {
                missing.chunked(20).forEach { ids -> db.updateIntros(lang, wiki.intros(lang, ids)) }
            } catch (e: Exception) {
                Log.w(TAG, "ensureIntros failed", e)
                return@withContext places
            }
            places.map { p -> if (p.intro == null) db.place(lang, p.pageId) ?: p else p }.onEach { it.prepare() }
        }
    }

    /** Volltext aus der Datenbank, sonst (online) laden und speichern. */
    suspend fun fullText(place: Place, allowNetwork: Boolean): String? = withContext(Dispatchers.IO) {
        db.full(place.lang, place.pageId)?.let { return@withContext it }
        if (!allowNetwork) return@withContext null
        try {
            wiki.fullText(place.lang, place.pageId)?.also { db.updateFull(place.lang, place.pageId, it) }
        } catch (e: Exception) {
            Log.w(TAG, "fullText failed", e)
            null
        }
    }

    private var lastReverse = 0L
    private var lastReversePos: LatLon? = null
    private var lastAddress: Osm.Address? = null

    /** Straße und Stadtteil, ermittelt am Punkt [at]. */
    data class StreetInfo(val address: Osm.Address, val at: LatLon)

    /**
     * Straßenname am Standort: offline aus den Straßen der heruntergeladenen Stadt, sonst über Nominatim
     * (höchstens alle 20 s und erst nach 25 m Weg, um den Dienst zu schonen). [StreetInfo.at] sagt, wo die
     * Angabe gilt; der Aufrufer entscheidet, ob sie noch frisch genug ist.
     */
    suspend fun streetAt(p: LatLon, lang: String, allowNetwork: Boolean): StreetInfo? = withContext(Dispatchers.IO) {
        if (db.hasStreetsNear(p)) return@withContext StreetInfo(Osm.Address(db.nearestStreet(p, 30.0), null), p)
        if (!allowNetwork) return@withContext null
        val cached = lastAddress?.let { a -> lastReversePos?.let { StreetInfo(a, it) } }
        val moved = lastReversePos?.let { Geo.distance(it, p) } ?: Double.MAX_VALUE
        if (System.currentTimeMillis() - lastReverse < 20_000 || moved < 25) return@withContext cached
        lastReverse = System.currentTimeMillis()
        try {
            Osm.reverse(p, lang).let { lastAddress = it; lastReversePos = p; StreetInfo(it, p) }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.w(TAG, "reverse failed", e)
            cached
        }
    }

    companion object {
        private const val TAG = "Repository"
        const val SEARCH_RADIUS = 1500.0
        /** Neu suchen, wenn man näher als 600 m an den Rand des letzten Kreises kommt. */
        const val AREA_MARGIN = 600.0
        const val AREA_MAX_AGE = 30L * 24 * 3600 * 1000
    }
}
