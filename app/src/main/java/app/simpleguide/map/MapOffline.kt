package app.simpleguide.map

import android.content.Context
import app.simpleguide.geo.BBox
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.maplibre.android.MapLibre
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.offline.OfflineManager
import org.maplibre.android.offline.OfflineRegion
import org.maplibre.android.offline.OfflineRegionError
import org.maplibre.android.offline.OfflineRegionStatus
import org.maplibre.android.offline.OfflineTilePyramidRegionDefinition
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Kartenstile von OpenFreeMap: Vektorkacheln aus OpenStreetMap, ohne Schlüssel und Konto. Wie in Vela Maps. */
object MapStyles {
    /**
     * MapLibre erst laden, wenn eine Karte gebraucht wird: Die native Bibliothek ist groß, beim App-Start auf dem
     * Hauptthread würde sie den Start spürbar verzögern. Mehrfacher Aufruf ist harmlos.
     */
    fun init(context: Context) { MapLibre.getInstance(context.applicationContext) }

    const val LIGHT = "https://tiles.openfreemap.org/styles/liberty"
    const val DARK = "https://tiles.openfreemap.org/styles/dark"
    /** Schrift aus dem Glyphen-Server von OpenFreeMap für eigene Beschriftungen. */
    const val FONT = "Noto Sans Bold"
}

/**
 * Kartenausschnitt einer Stadt für unterwegs ohne Netz, über den Offline-Speicher von MapLibre: Stil, Schrift,
 * Symbole und Vektorkacheln eines Rechtecks landen in dessen SQLite-Datei, die Karte zeigt das Gebiet dann ohne
 * Verbindung. Aufbau nach `OfflineMaps.kt` aus Vela Maps (GPL-3.0, github.com/PimpinPumpkin/Vela).
 */
object MapOffline {
    /** Zoomstufen: 11 zeigt die ganze Stadt, 16 einzelne Häuser und Fußwege; mehr braucht ein Spaziergang nicht. */
    const val MIN_ZOOM = 11.0
    const val MAX_ZOOM = 16.0
    private const val TILE_LIMIT = 60_000L
    private const val KEY_NAME = "name"
    private val TRANSIENT = setOf(OfflineRegionError.REASON_CONNECTION, OfflineRegionError.REASON_SERVER)

    fun regionName(cityId: Long, style: String) = "city-$cityId-${if (style == MapStyles.DARK) "dark" else "light"}"

    /**
     * Lädt den Ausschnitt für [box] in [style] und meldet den Fortschritt (0..1). Abbruch der Coroutine hält den
     * Download an; ein späterer Aufruf mit demselben Namen setzt die vorhandene Region fort.
     */
    suspend fun download(context: Context, box: BBox, style: String, name: String, onProgress: (Float) -> Unit) =
        withContext(Dispatchers.Main) {
            MapStyles.init(context)
            val manager = OfflineManager.getInstance(context)
            manager.setOfflineMapboxTileCountLimit(TILE_LIMIT)
            val region = find(context, name) ?: create(context, box, style, name)
            suspendCancellableCoroutine<Unit> { cont ->
                region.setObserver(object : OfflineRegion.OfflineRegionObserver {
                    var done = false
                    override fun onStatusChanged(status: OfflineRegionStatus) {
                        if (done) return
                        if (status.isComplete) {
                            done = true
                            region.setDownloadState(OfflineRegion.STATE_INACTIVE)
                            cont.resume(Unit)
                        } else if (status.requiredResourceCount > 0) {
                            onProgress(status.completedResourceCount.toFloat() / status.requiredResourceCount)
                        }
                    }
                    override fun onError(error: OfflineRegionError) {
                        // MapLibre versucht vorübergehende Netzfehler selbst erneut; endgültig ist nur "Not found" o. ä.
                        if (done || error.reason in TRANSIENT) return
                        done = true
                        region.setDownloadState(OfflineRegion.STATE_INACTIVE)
                        cont.resumeWithException(IOException("map: ${error.reason} ${error.message}"))
                    }
                    override fun mapboxTileCountLimitExceeded(limit: Long) {
                        if (done) return
                        done = true
                        region.setDownloadState(OfflineRegion.STATE_INACTIVE)
                        cont.resumeWithException(IOException("map: more than $limit tiles"))
                    }
                })
                region.setDownloadState(OfflineRegion.STATE_ACTIVE)
                cont.invokeOnCancellation { region.setDownloadState(OfflineRegion.STATE_INACTIVE) }
            }
        }

    /** Kartenausschnitte einer Stadt löschen (beide Stile) und den Speicher freigeben. */
    suspend fun delete(context: Context, cityId: Long) = withContext(Dispatchers.Main) {
        MapStyles.init(context)
        for (style in listOf(MapStyles.LIGHT, MapStyles.DARK)) {
            val region = find(context, regionName(cityId, style)) ?: continue
            suspendCancellableCoroutine<Unit> { cont ->
                region.delete(object : OfflineRegion.OfflineRegionDeleteCallback {
                    override fun onDelete() = cont.resume(Unit)
                    override fun onError(error: String) = cont.resume(Unit)
                })
            }
        }
        // Gelöschte Regionen geben ihren Platz erst nach dem Zusammenpacken frei (wie in Vela)
        suspendCancellableCoroutine<Unit> { cont ->
            runCatching {
                OfflineManager.getInstance(context).packDatabase(object : OfflineManager.FileSourceCallback {
                    override fun onSuccess() = cont.resume(Unit)
                    override fun onError(message: String) = cont.resume(Unit)
                })
            }.onFailure { cont.resume(Unit) }
        }
    }

    private suspend fun find(context: Context, name: String): OfflineRegion? = suspendCancellableCoroutine { cont ->
        OfflineManager.getInstance(context).listOfflineRegions(object : OfflineManager.ListOfflineRegionsCallback {
            override fun onList(regions: Array<OfflineRegion>?) = cont.resume(regions.orEmpty().firstOrNull { nameOf(it) == name })
            override fun onError(error: String) = cont.resume(null)
        })
    }

    private suspend fun create(context: Context, box: BBox, style: String, name: String): OfflineRegion =
        suspendCancellableCoroutine { cont ->
            val bounds = LatLngBounds.Builder().include(LatLng(box.north, box.east)).include(LatLng(box.south, box.west)).build()
            val definition = OfflineTilePyramidRegionDefinition(style, bounds, MIN_ZOOM, MAX_ZOOM, context.resources.displayMetrics.density)
            val metadata = JSONObject().put(KEY_NAME, name).toString().toByteArray()
            OfflineManager.getInstance(context).createOfflineRegion(definition, metadata, object : OfflineManager.CreateOfflineRegionCallback {
                override fun onCreate(region: OfflineRegion) = cont.resume(region)
                override fun onError(error: String) = cont.resumeWithException(IOException("map: $error"))
            })
        }

    private fun nameOf(region: OfflineRegion): String =
        runCatching { JSONObject(String(region.metadata)).optString(KEY_NAME) }.getOrNull().orEmpty()
}
