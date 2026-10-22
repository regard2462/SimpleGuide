package app.simpleguide.data

import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.simpleguide.GuideApp
import app.simpleguide.R
import app.simpleguide.geo.BBox
import app.simpleguide.util.AppLanguage
import app.simpleguide.map.MapOffline
import app.simpleguide.map.MapStyles
import app.simpleguide.wiki.Osm
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Fortschritt laufender Downloads für die Oberfläche: Stadt-ID → (Anteil 0..1, Text). */
object Downloads {
    data class Progress(val fraction: Float, val label: String)
    val progress = MutableStateFlow<Map<Long, Progress>>(emptyMap())

    fun start(context: Context, cityId: Long) {
        val req = OneTimeWorkRequestBuilder<CityDownloadWorker>()
            .setInputData(workDataOf("city" to cityId))
            // Ohne Netz gar nicht erst anfangen; bricht es unterwegs ab, wartet WorkManager auf die Verbindung
            .setConstraints(Constraints(requiredNetworkType = NetworkType.CONNECTED))
            .setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork("city-$cityId", ExistingWorkPolicy.KEEP, req)
    }

    fun cancel(context: Context, cityId: Long) {
        WorkManager.getInstance(context).cancelUniqueWork("city-$cityId")
        progress.update { it - cityId }
    }
}

/**
 * Lädt alle Wikipedia-Artikel mit Koordinaten in einem Rechteck, dazu Kurzbeschreibungen, Einleitungen, auf
 * Wunsch die Volltexte und die Straßennamen aus OpenStreetMap. Was schon da ist, wird übersprungen; ein
 * abgebrochener Download macht beim nächsten Start dort weiter.
 *
 * Die Suche geht kachelweise: Liefert eine Kachel 500 Treffer (die Höchstzahl), wird sie geviertelt, bis jede
 * Kachel vollständig ist.
 */
class CityDownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    private val app get() = applicationContext as GuideApp
    private val db get() = app.repo.db
    private val wiki get() = app.repo.wiki
    private val res by lazy { AppLanguage.wrap(applicationContext) }
    private val cityId get() = inputData.getLong("city", -1)

    override suspend fun doWork(): Result {
        val id = inputData.getLong("city", -1)
        val city = db.city(id) ?: return Result.failure()
        val lang = city.lang
        return try {
            db.updateCity(id, state = "running", message = "")
            // Aus dem Hintergrund (etwa beim Wiederanlauf nach Netzverlust) darf ab Android 12 kein Vordergrunddienst
            // starten; dann läuft der Download eben ohne Benachrichtigung weiter
            runCatching { setForeground(foreground(city.name, 0f, res.getString(R.string.dl_searching))) }
                .onFailure { Log.w(TAG, "no foreground: $it") }

            // ---- 1. Artikel suchen (Kacheln)
            val found = LinkedHashMap<Long, GeoHit>()
            val queue = ArrayDeque(city.box.tiles(4000.0))
            var done = 0
            while (queue.isNotEmpty()) {
                val tile = queue.removeFirst()
                val hits = wiki.inBox(lang, tile)
                if ((hits == null || hits.size >= 500) && tile.heightM > 120) {
                    queue.addAll(tile.quarters())
                } else {
                    hits?.forEach { found[it.pageId] = it }
                    done++
                }
                report(id, city.name, 0.02f + 0.08f * done / (done + queue.size), res.getString(R.string.dl_found, found.size))
            }
            db.upsertBasic(lang, found.values.toList())
            db.linkCity(id, lang, found.keys)
            db.updateCity(id, places = found.size)

            // ---- 2. Kurzbeschreibungen (50 je Anfrage)
            val noDetails = db.cityMissing(id, "details")
            noDetails.chunked(50).forEachIndexed { i, ids ->
                db.updateDetails(lang, wiki.details(lang, ids))
                report(id, city.name, 0.1f + 0.1f * (i + 1) * 50 / noDetails.size.coerceAtLeast(1), res.getString(R.string.dl_details))
            }

            // ---- 3. Einleitungen (20 je Anfrage)
            val noIntro = db.cityMissing(id, "intro")
            noIntro.chunked(20).forEachIndexed { i, ids ->
                db.updateIntros(lang, wiki.intros(lang, ids))
                report(id, city.name, 0.2f + 0.2f * (i + 1) * 20 / noIntro.size.coerceAtLeast(1), res.getString(R.string.dl_intros, minOf((i + 1) * 20, noIntro.size), noIntro.size))
            }

            // ---- 4. Volltexte (einzeln, gemächlich, damit die Wikipedia nicht drosselt)
            if (city.withTexts) {
                val noFull = db.cityMissing(id, "full")
                noFull.forEachIndexed { i, pageId ->
                    wiki.fullText(lang, pageId)?.let { db.updateFull(lang, pageId, it) }
                    if (i % 5 == 0 || i == noFull.lastIndex) {
                        report(id, city.name, 0.4f + 0.4f * (i + 1) / noFull.size, res.getString(R.string.dl_texts, i + 1, noFull.size))
                        db.updateCity(id, texts = db.cityTextCount(id))
                    }
                }
            }

            // ---- 5. Kartenausschnitt (OpenFreeMap über MapLibre), hell und dunkel; die Kacheln teilen sich beide
            if (city.withMap) {
                try {
                    listOf(MapStyles.LIGHT, MapStyles.DARK).forEachIndexed { n, style ->
                        val label = res.getString(R.string.dl_map)
                        MapOffline.download(applicationContext, city.box, style, MapOffline.regionName(id, style)) { f ->
                            Downloads.progress.update { it + (id to Downloads.Progress(0.8f + 0.12f * (n + f) / 2, label)) }
                        }
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    // Ohne Karte bleibt die Stadt nutzbar; "Aktualisieren" versucht es erneut
                    Log.w(TAG, "map failed", e)
                    db.updateCity(id, message = res.getString(R.string.dl_map_failed))
                }
            }

            // ---- 6. Straßennamen (kachelweise, damit Großstädte nicht den Speicher sprengen)
            if (city.withStreets) {
                report(id, city.name, 0.92f, res.getString(R.string.dl_streets))
                try {
                    db.deleteStreets(id)
                    var count = 0
                    Osm.streets(city.box) { tile ->
                        db.addStreets(id, tile)
                        count += tile.size
                        db.updateCity(id, streets = count)
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    Log.w(TAG, "streets failed", e)
                    db.updateCity(id, message = res.getString(R.string.dl_streets_failed))
                }
            }

            db.updateCity(id, texts = db.cityTextCount(id), bytes = db.cityBytes(id), state = "done")
            Downloads.progress.update { it - id }
            Result.success()
        } catch (e: CancellationException) {
            db.updateCity(id, state = "paused", bytes = db.cityBytes(id))
            Downloads.progress.update { it - id }
            throw e
        } catch (e: IOException) {
            // Netz weg oder Server überlastet: WorkManager versucht es später wieder, der Stand bleibt erhalten
            if (runAttemptCount >= MAX_ATTEMPTS) return failed(id, e)
            Log.w(TAG, "download interrupted, retrying later", e)
            // "paused": Hält der Nutzer in der Wartezeit an, zeigt die Karte gleich den richtigen Stand
            db.updateCity(id, state = "paused", bytes = db.cityBytes(id))
            Downloads.progress.update { it + (id to Downloads.Progress(it[id]?.fraction ?: 0f, res.getString(R.string.dl_waiting_network))) }
            Result.retry()
        } catch (e: Exception) {
            failed(id, e)
        }
    }

    private fun failed(id: Long, e: Exception): Result {
        Log.e(TAG, "download failed", e)
        db.updateCity(id, state = "failed", message = e.message ?: e.javaClass.simpleName, bytes = db.cityBytes(id))
        Downloads.progress.update { it - id }
        return Result.failure()
    }

    private var lastNotify = 0L

    private suspend fun report(id: Long, name: String, fraction: Float, label: String) {
        Downloads.progress.update { it + (id to Downloads.Progress(fraction.coerceIn(0f, 1f), label)) }
        val now = System.currentTimeMillis()
        if (now - lastNotify > 1000) {
            lastNotify = now
            // Der Dienst läuft schon im Vordergrund; nur die Benachrichtigung auffrischen
            val info = foreground(name, fraction, label)
            runCatching { applicationContext.getSystemService(NotificationManager::class.java).notify(info.notificationId, info.notification) }
        }
    }

    private fun foreground(name: String, fraction: Float, label: String): ForegroundInfo {
        val n = NotificationCompat.Builder(applicationContext, GuideApp.CHANNEL_DOWNLOAD)
            .setSmallIcon(R.drawable.ic_stat_app)
            .setContentTitle(res.getString(R.string.dl_title, name))
            .setContentText(label)
            .setProgress(1000, (fraction * 1000).toInt(), false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
        return if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(NOTIFY_ID + cityId.toInt(), n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else ForegroundInfo(NOTIFY_ID + cityId.toInt(), n)
    }

    override suspend fun getForegroundInfo(): ForegroundInfo =
        foreground(db.city(inputData.getLong("city", -1))?.name ?: "", 0f, "")

    companion object {
        private const val TAG = "CityDownload"
        private const val NOTIFY_ID = 5000
        /** So oft wird nach Netzfehlern neu angesetzt, bevor der Download als fehlgeschlagen gilt. */
        private const val MAX_ATTEMPTS = 6
    }
}

/** Rechteck einer Stadt auf eine vernünftige Größe begrenzen (Nominatim liefert für Stadtstaaten das ganze Land). */
fun BBox.limited(maxSideM: Double = 30_000.0): BBox {
    val c = center
    val h = heightM; val w = widthM
    if (h <= maxSideM && w <= maxSideM) return this
    val fh = minOf(1.0, maxSideM / h); val fw = minOf(1.0, maxSideM / w)
    val dLat = (north - south) * fh / 2; val dLon = (east - west) * fw / 2
    return BBox(c.lat - dLat, c.lon - dLon, c.lat + dLat, c.lon + dLon)
}
