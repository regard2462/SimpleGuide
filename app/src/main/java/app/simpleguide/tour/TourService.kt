package app.simpleguide.tour

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.media3.session.MediaSession
import app.simpleguide.GuideApp
import app.simpleguide.MainActivity
import app.simpleguide.R
import app.simpleguide.data.GuideSettings
import app.simpleguide.data.Place
import app.simpleguide.data.StreetMode
import app.simpleguide.geo.Geo
import app.simpleguide.geo.HeadingTracker
import app.simpleguide.geo.LatLon
import app.simpleguide.geo.Side
import app.simpleguide.guide.Candidate
import app.simpleguide.guide.TourPolicy
import app.simpleguide.text.Category
import app.simpleguide.text.Classifier
import app.simpleguide.text.Detail
import app.simpleguide.text.Line
import app.simpleguide.text.Narration
import app.simpleguide.text.PlaceText
import app.simpleguide.text.SubjectParser
import app.simpleguide.text.WikiText
import app.simpleguide.tts.TtsManager
import app.simpleguide.util.AppLanguage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * Die laufende Tour: Vordergrunddienst mit Standort, der beim Gehen entscheidet, was erzählt wird, und es
 * über den [Speaker] sagt. Läuft mit ausgeschaltetem Bildschirm weiter; Musik anderer Apps wird für die
 * Ansage kurz pausiert (oder leiser) und spielt danach weiter.
 *
 * Pause am Kopfhörer oder am Handy bricht nur die laufende Ansage ab. Der Ort gilt als gehört, die Tour läuft
 * weiter, und über die Benachrichtigung (oder Play am Kopfhörer, solange keine Musik läuft) lässt er sich
 * noch einmal hören.
 */
class TourService : Service(), Speaker.Listener {

    companion object {
        private const val TAG = "TourService"
        const val CHANNEL = "tour"
        private const val NOTIFICATION_ID = 3003

        const val ACTION_START = "app.simpleguide.START"
        const val ACTION_STOP = "app.simpleguide.STOP"
        const val ACTION_SKIP = "app.simpleguide.SKIP"
        const val ACTION_MORE = "app.simpleguide.MORE"
        const val ACTION_HERE = "app.simpleguide.HERE"
        const val ACTION_REPLAY = "app.simpleguide.REPLAY"
        const val ACTION_PLAY = "app.simpleguide.PLAY"
        const val EXTRA_LANG = "lang"
        const val EXTRA_PAGE = "page"

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, TourService::class.java).setAction(ACTION_START))
        }

        /** Befehl an die laufende Tour (Knöpfe in der App). */
        fun send(context: Context, action: String, place: Place? = null) {
            if (!TourState.running.value) return
            val i = Intent(context, TourService::class.java).setAction(action)
            place?.let { i.putExtra(EXTRA_LANG, it.lang).putExtra(EXTRA_PAGE, it.pageId) }
            context.startService(i)
        }

        private val LIVE_CATEGORIES = setOf(Category.PLACE, Category.NATURE, Category.DISTRICT)
        /** So nah am Artikelpunkt eines Platzes oder Parks gilt man als "dort". */
        private const val INSIDE_M = 20.0
    }

    private val app get() = application as GuideApp
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var speaker: Speaker
    private var session: MediaSession? = null
    private val tracker = HeadingTracker()
    private var s = GuideSettings()
    private var lang = "de"
    private var started = false
    private var fgType = 0
    private var greeted = false
    private var wakeLock: PowerManager.WakeLock? = null
    private val locationManager by lazy { getSystemService(LocationManager::class.java) }

    // ---- Tour
    private val heard = HashSet<Long>()
    private var candidates: List<Place> = emptyList()
    private var candidatesAt: LatLon? = null
    private var candidatesTime = 0L
    private var candidatesJob: Job? = null
    private var candidatesDirty = true
    private var areaJob: Job? = null
    private var lastAreaCheck = 0L
    private var prefetchJob: Job? = null
    private var prerendered: Long? = null
    private val introRequested = HashSet<Long>()
    private var lastEnd = 0L
    private var lastEvaluate = 0L
    private var streetJob: Job? = null
    private var lastStreetCheck = 0L
    private var currentStreet: String? = null
    /** Wo [currentStreet] ermittelt wurde. */
    private var streetAt: LatLon? = null
    private var startedAt = 0L
    private var lastStreetSaid: String? = null
    private var current: Place? = null
    /** Bereitet die nächste Ansage vor (Texte laden, formulieren); nur eine zur Zeit. */
    private var narrateJob: Job? = null
    private var moreQueue = ArrayDeque<List<Line>>()
    private var moreHasFull = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(AppLanguage.wrap(base))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!started && intent?.action != ACTION_START && intent?.action != null) {
            // Knopf einer alten Benachrichtigung: keine Tour, also gleich wieder beenden
            stopSelf(startId)
            return START_NOT_STICKY
        }
        when (intent?.action) {
            ACTION_START, null -> begin()
            ACTION_STOP -> { end(); return START_NOT_STICKY }
            ACTION_SKIP -> if (started) skip()
            ACTION_MORE -> if (started) more()
            ACTION_HERE -> if (started) whatsHere()
            ACTION_REPLAY -> if (started) TourState.last.value?.let { narrate(it, auto = false) }
            ACTION_PLAY -> if (started) {
                val l = intent.getStringExtra(EXTRA_LANG); val id = intent.getLongExtra(EXTRA_PAGE, -1)
                if (l != null && id > 0) scope.launch {
                    withContext(Dispatchers.IO) { app.repo.db.place(l, id)?.prepare() }?.let { narrate(it, auto = false) }
                }
            }
        }
        return START_NOT_STICKY
    }

    // ================================================================ Start und Ende

    private fun begin() {
        if (started) {
            // Erneutes startForegroundService verlangt wieder startForeground
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(), fgType)
            return
        }
        val permitted = hasLocationPermission()
        // Ohne Standortrecht darf der Dienst nicht als Standortdienst starten (SecurityException ab Android 14)
        val type = when {
            Build.VERSION.SDK_INT < 29 -> 0
            permitted -> ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            else -> ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        }
        fgType = type
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(), type)
        if (!permitted) {
            TourState.status.value = TourState.Status.NO_PERMISSION
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        started = true
        speaker = Speaker(this, app.tts, scope).also { it.listener = this }
        val openApp = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        session = MediaSession.Builder(this, speaker.player).setId("tour").setSessionActivity(openApp).build()
        TourState.running.value = true
        TourState.status.value = TourState.Status.WAITING_GPS
        startedAt = System.currentTimeMillis()

        scope.launch {
            var loaded = false
            app.settings.flow.collect { n ->
                val langChanged = n.contentLang != lang || !loaded
                loaded = true
                s = n
                lang = n.contentLang
                if (langChanged) {
                    heard.clear()
                    if (n.repeatAfterHours > 0) heard += withContext(Dispatchers.IO) {
                        app.repo.db.heardSince(lang, System.currentTimeMillis() - n.repeatAfterHours * 3600_000L)
                    }
                    candidates = emptyList(); candidatesDirty = true
                }
            }
        }
        startLocation()
        // Auch ohne neue Standorte (Stehen) regelmäßig prüfen, etwa nach dem Ende einer Ansage
        scope.launch {
            while (isActive) {
                delay(4000)
                if (System.currentTimeMillis() - lastEvaluate > 3500) evaluate()
            }
        }
        updateNotification()
    }

    private fun end() {
        if (started) {
            runCatching { locationManager.removeUpdates(locationListener) }
            narrateJob?.cancel()
            // Erst die Sitzung, dann den Player (Reihenfolge laut media3)
            session?.release(); session = null
            speaker.release()
        }
        releaseWake()
        started = false
        TourState.reset()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        if (started) end()
        scope.cancel()
        super.onDestroy()
    }

    // ================================================================ Standort

    private fun hasLocationPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(l: Location) = onLocation(l)
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    }

    @SuppressLint("MissingPermission")
    private fun startLocation() {
        val providers = locationManager.allProviders
        for (p in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            if (p in providers) runCatching {
                locationManager.requestLocationUpdates(p, 2000L, 0f, locationListener, Looper.getMainLooper())
            }.onFailure { Log.w(TAG, "location $p", it) }
        }
        // Schneller Start mit der letzten bekannten Position, wenn sie frisch ist
        listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .mapNotNull { runCatching { locationManager.getLastKnownLocation(it) }.getOrNull() }
            .filter { System.currentTimeMillis() - it.time < 120_000 }
            .maxByOrNull { it.time }?.let { onLocation(it) }
    }

    private var lastGpsFix = 0L

    private fun onLocation(l: Location) {
        // Netzwerkortung nur, solange GPS schweigt
        val now = System.currentTimeMillis()
        if (l.provider == LocationManager.GPS_PROVIDER) lastGpsFix = now
        else if (now - lastGpsFix < 15_000) return
        tracker.add(
            HeadingTracker.Fix(
                LatLon(l.latitude, l.longitude), now,
                if (l.hasAccuracy()) l.accuracy else 30f,
                if (l.hasBearing()) l.bearing else null,
                if (l.hasSpeed()) l.speed else null,
            )
        )
        TourState.accuracyM.value = if (l.hasAccuracy()) l.accuracy else null
        if (now - lastEvaluate > 1500) evaluate()
    }

    // ================================================================ Entscheiden

    private fun evaluate() {
        if (!started) return
        lastEvaluate = System.currentTimeMillis()
        val fix = tracker.latest ?: run { TourState.status.value = TourState.Status.WAITING_GPS; return }
        val p = fix.p
        val now = System.currentTimeMillis()
        val heading = tracker.heading(fix.timeMs)
        TourState.position.value = p
        TourState.heading.value = heading

        if (s.online) ensureArea(p)
        refreshCandidates(p)
        updateStreet(p)

        val policy = TourPolicy(s.radiusM, s.categories)
        val cands = candidates.map { candidate(it, p, heading) }.sortedBy { it.distanceM }
        TourState.nearby.value = cands.filter { policy.allowed(it.place) && it.distanceM <= 600 }.take(40).map {
            NearbyItem(it.place, it.distanceM.roundToInt(), it.side, it.place.pageId in heard, it.distanceM <= policy.radiusFor(it.place))
        }
        TourState.status.value = when {
            app.tts.voiceFor(lang, s) == null -> TourState.Status.NO_VOICE
            candidates.isNotEmpty() -> TourState.Status.READY
            areaJob?.isActive == true || candidatesJob?.isActive == true -> TourState.Status.LOADING
            else -> TourState.Status.OFFLINE_EMPTY
        }

        prefetch(policy.upcoming(cands, heard, 6))

        if (speaker.busy || TourState.speaking.value != null) return
        // Begrüßung, sobald Orte geladen sind; auf den Straßennamen höchstens 15 s warten
        val streetKnown = currentStreet != null || s.streets == StreetMode.OFF || now - startedAt > 15_000
        if (!greeted && candidatesTime > 0 && streetKnown) { greet(); return }
        if (now - lastEnd < s.gapSec * 1000L) return
        val pick = policy.pick(cands, heard)
        if (pick != null) {
            Log.i(TAG, "pick ${pick.place.name} at ${p.lat},${p.lon} heading=${heading?.roundToInt()}: " +
                cands.filter { policy.eligible(it, heard) }.joinToString { c ->
                    "${c.place.name} ${c.distanceM.roundToInt()}m ${c.side} ${c.place.category} %.2f".format(policy.score(c))
                })
            narrate(pick.place, auto = true)
            return
        }
        // Nichts in Reichweite: auf Wunsch die neue Straße ansagen
        val street = freshStreet(p)
        if (s.streets == StreetMode.ALWAYS && street != null && street != lastStreetSaid) {
            lastStreetSaid = street
            speak("street", street, listOf(Line(Narration(lang).street(street))), chime = s.chime)
        }
    }

    private fun greet() {
        greeted = true
        val street = currentStreet?.takeIf { s.streets != StreetMode.OFF }
        lastStreetSaid = street
        val text = if (candidates.isEmpty() && !s.online) Narration(lang).nothingYet() else Narration(lang).tourStart(street)
        speak("greet", getString(R.string.history_greeting), listOf(Line(text)), chime = false)
    }

    /** Straßenname nur, wenn er höchstens 20 m von [p] entfernt ermittelt wurde (sonst ist man schon weiter). */
    private fun freshStreet(p: LatLon): String? =
        currentStreet?.takeIf { streetAt?.let { at -> Geo.distance(at, p) <= 20.0 } == true }

    /** Wikipedia-Koordinaten einer Straße sind ihr Mittelpunkt; wer auf ihr geht, ist trotzdem "da". */
    private fun onStreet(place: Place, p: LatLon): Boolean {
        // Nur ein frischer Straßenname zählt; ein alter würde einen längst verlassenen Platz "unter die Füße" legen
        val street = freshStreet(p) ?: return false
        return place.category == Category.PLACE && place.name.equals(street, ignoreCase = true)
    }

    private fun candidate(place: Place, p: LatLon, heading: Double?): Candidate =
        if (onStreet(place, p)) Candidate(place, 0.0, 0.0, Side.UNKNOWN, onStreet = true) else Candidate.of(place, p, heading)

    private fun ensureArea(p: LatLon) {
        val now = System.currentTimeMillis()
        if (areaJob?.isActive == true || now - lastAreaCheck < 15_000) return
        lastAreaCheck = now
        areaJob = scope.launch {
            if (app.repo.ensureArea(lang, p)) { candidatesDirty = true; evaluate() }
        }
    }

    private fun refreshCandidates(p: LatLon) {
        val moved = candidatesAt?.let { Geo.distance(it, p) } ?: Double.MAX_VALUE
        val stale = candidatesDirty || moved > 80 || System.currentTimeMillis() - candidatesTime > 60_000
        if (!stale || candidatesJob?.isActive == true) return
        candidatesDirty = false
        candidatesJob = scope.launch {
            val list = app.repo.around(lang, p, 700.0, candidates.associateBy { it.pageId })
            candidates = list
            candidatesAt = p
            candidatesTime = System.currentTimeMillis()
            evaluate()
        }
    }

    private fun replaceCandidates(updated: List<Place>) {
        val byId = updated.associateBy { it.pageId }
        candidates = candidates.map { byId[it.pageId] ?: it }
        if (current != null) current = byId[current!!.pageId] ?: current
    }

    /** Einleitungen der nächsten Orte laden und den wahrscheinlich nächsten Ort schon synthetisieren. */
    private fun prefetch(upcoming: List<Candidate>) {
        if (prefetchJob?.isActive == true || upcoming.isEmpty()) return
        prefetchJob = scope.launch {
            val needIntro = upcoming.map { it.place }.filter { it.intro == null && it.pageId !in introRequested }
            if (needIntro.isNotEmpty() && s.online) {
                introRequested += needIntro.map { it.pageId }
                replaceCandidates(app.repo.ensureIntros(lang, needIntro))
            }
            val next = upcoming.firstOrNull()?.place?.let { u -> candidates.firstOrNull { it.pageId == u.pageId } } ?: return@launch
            if (prerendered == next.pageId || speaker.busy || app.tts.voiceFor(lang, s) == null) return@launch
            prerendered = next.pageId
            val full = if (next.hasFull || s.detail == Detail.LONG) app.repo.fullText(next, s.online) else null
            val script = withContext(Dispatchers.Default) { Narration(lang).place(placeText(next, full), Side.UNKNOWN, 0, s.detail) }
            // Die erste Zeile hängt von Richtung und Entfernung ab und wird erst beim Ansagen erzeugt
            runCatching { speaker.prerender(script.lines.drop(1), lang, s) }
        }
    }

    // ================================================================ Erzählen

    private fun placeText(p: Place, full: String?) = PlaceText(p.title, p.description, p.intro, full, p.category)

    private fun narrate(place: Place, auto: Boolean) {
        heard += place.pageId
        current = place
        TourState.last.value = place
        scope.launch(Dispatchers.IO) { app.repo.db.markHeard(place.lang, place.pageId) }
        // Platzhalter, damit die Auswahl nicht gleich den nächsten Ort startet, während der Text entsteht
        TourState.speaking.value = NowPlaying(place, place.name, false)
        narrateJob?.cancel()
        narrateJob = scope.launch {
          try {
            var pl = place
            if (pl.intro == null && s.online) pl = app.repo.ensureIntros(pl.lang, listOf(pl)).first()
            val full = if (pl.hasFull || s.detail == Detail.LONG) app.repo.fullText(pl, s.online) else null
            val fix = tracker.latest
            val c = fix?.let { Candidate.of(pl, it.p, tracker.heading(it.timeMs)) }
            val distance = c?.distanceM?.roundToInt() ?: 0
            // "Du bist jetzt auf dem Münsterplatz" nur, wenn man wirklich dort ist: Straßenname passt oder ganz nah dran
            val inside = (fix != null && onStreet(pl, fix.p)) || Classifier.isStreet(pl.title) ||
                pl.category in LIVE_CATEGORIES && c != null && c.distanceM < INSIDE_M
            val narration = Narration(pl.lang)
            val script = withContext(Dispatchers.Default) { narration.place(placeText(pl, full), c?.side ?: Side.UNKNOWN, distance, s.detail, inside) }
            val lines = ArrayList<Line>()
            val street = fix?.let { freshStreet(it.p) }
            if (auto && !inside && s.streets != StreetMode.OFF && street != null && street != lastStreetSaid && !pl.name.startsWith(street)) {
                lines += Line(narration.street(street), 300)
                lastStreetSaid = street
            }
            if (pl.name.startsWith(street ?: "\u0000")) lastStreetSaid = street
            if (auto && script.lines.size < 2) {
                // Nur "rechts von dir liegt X" ohne jeden Inhalt: lieber schweigen
                Log.i(TAG, "nothing to tell about ${pl.title}")
                TourState.speaking.value = null
                return@launch
            }
            lines += script.lines
            moreQueue = ArrayDeque(script.more)
            moreHasFull = full != null
            current = pl
            TourState.speaking.value = NowPlaying(pl, pl.name, moreQueue.isNotEmpty() || (!moreHasFull && s.online))
            speak("place:${pl.key}", pl.name, lines, chime = s.chime, langOverride = pl.lang)
          } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
          } catch (e: Exception) {
            Log.e(TAG, "narrate failed: ${place.title}", e)
            TourState.speaking.value = null
            lastEnd = System.currentTimeMillis()
          }
        }
    }

    /** Spricht [lines]; der Text kommt in den Verlauf und in die Anzeige, auch wenn keine Stimme da ist. */
    private fun speak(id: String, title: String, lines: List<Line>, chime: Boolean, langOverride: String? = null) {
        val text = lines.map { it.text }
        val voiced = app.tts.voiceFor(langOverride ?: lang, s) != null
        TourState.remember(title, text, voiced)
        if (!voiced) {
            TourState.status.value = TourState.Status.NO_VOICE
            TourState.speaking.value = null
            lastEnd = System.currentTimeMillis()
            return
        }
        TourState.speaking.value = (TourState.speaking.value ?: NowPlaying(null, title, false)).copy(text = text)
        acquireWake()
        speaker.speak(id, lines, langOverride ?: lang, s, chime)
        updateNotification()
    }

    /** Überspringen: auch eine Ansage, die noch vorbereitet wird (Text lädt), darf nicht mehr anfangen. */
    private fun skip() {
        narrateJob?.cancel()
        speaker.stop()
        onUserStop()
    }

    private fun more() {
        val pl = current ?: TourState.last.value ?: return
        scope.launch {
            if (moreQueue.isEmpty() && !moreHasFull) {
                moreHasFull = true
                val full = app.repo.fullText(pl, s.online)
                if (full != null) {
                    val script = withContext(Dispatchers.Default) { Narration(pl.lang).place(placeText(pl, full), Side.UNKNOWN, 0, s.detail) }
                    moreQueue = ArrayDeque(script.more)
                }
            }
            val chunk = moreQueue.removeFirstOrNull()
            if (chunk == null) {
                val text = if (pl.lang == "en") "That's all I know about it." else "Mehr weiß ich darüber nicht."
                speak("more:${pl.key}", getString(R.string.history_more, pl.name), listOf(Line(text)), chime = false, langOverride = pl.lang)
                return@launch
            }
            TourState.speaking.value = NowPlaying(pl, pl.name, moreQueue.isNotEmpty() || (!moreHasFull && s.online))
            speak("more:${pl.key}", getString(R.string.history_more, pl.name), chunk, chime = false, langOverride = pl.lang)
        }
    }

    /** "Was ist hier?": Straße und die bedeutendsten Orte ringsum mit Richtung. */
    private fun whatsHere() {
        val fix = tracker.latest ?: return
        val heading = tracker.heading(fix.timeMs)
        val policy = TourPolicy(s.radiusM, s.categories)
        val near = candidates.map { Candidate.of(it, fix.p, heading) }
            .filter { policy.allowed(it.place) && it.distanceM <= 250 }
            .sortedByDescending { it.place.importance * (1.0 - it.distanceM / 400) }
            .take(5)
        val street = freshStreet(fix.p)
        current = null
        TourState.speaking.value = NowPlaying(null, getString(R.string.whats_here), false)
        narrateJob?.cancel()
        narrateJob = scope.launch {
            val narration = Narration(lang)
            // Satzzerlegung der Einleitungen nicht auf dem Hauptthread
            val items = withContext(Dispatchers.Default) {
                near.map { c ->
                    val first = c.place.intro?.let { WikiText.sections(it).firstOrNull()?.paragraphs?.firstOrNull() }
                        ?.let { WikiText.sentences(it, lang).firstOrNull() }
                    SubjectParser.parse(first, c.place.title, lang).phrase to c.side
                }
            }
            val lines = ArrayList<Line>()
            street?.let { lines += Line(narration.street(it), 300); lastStreetSaid = it }
            lines += Line(narration.overview(items))
            speak("here", getString(R.string.whats_here), lines, chime = false)
        }
    }

    private fun updateStreet(p: LatLon) {
        val now = System.currentTimeMillis()
        if (s.streets == StreetMode.OFF || streetJob?.isActive == true || now - lastStreetCheck < 8000) return
        lastStreetCheck = now
        streetJob = scope.launch {
            val info = app.repo.streetAt(p, lang, s.online)
            currentStreet = info?.address?.street
            streetAt = info?.at
            TourState.street.value = listOfNotNull(info?.address?.street, info?.address?.district).joinToString(", ").ifEmpty { null }
        }
    }

    // ================================================================ Ereignisse des Sprechers

    override fun onUserStop() {
        TourState.speaking.value = null
        lastEnd = System.currentTimeMillis()
        releaseWake()
        updateNotification()
    }

    override fun onUserPlay() {
        TourState.last.value?.let { narrate(it, auto = false) }
    }

    override fun onUserNext() {
        if (speaker.busy || TourState.speaking.value != null) skip()
    }

    override fun onFinished(id: String) {
        TourState.speaking.value = null
        lastEnd = System.currentTimeMillis()
        releaseWake()
        updateNotification()
    }

    override fun onError(e: Exception) {
        if (e is TtsManager.NoModelException) TourState.status.value = TourState.Status.NO_VOICE
        onFinished("")
    }

    // ================================================================ Benachrichtigung

    private fun action(icon: Int, title: Int, action: String) = NotificationCompat.Action(
        icon, getString(title),
        PendingIntent.getService(this, action.hashCode(), Intent(this, TourService::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE),
    )

    private fun notification(): Notification {
        val now = TourState.speaking.value
        val openApp = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val b = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_app)
            .setContentTitle(now?.title ?: getString(R.string.tour_running))
            .setContentText(if (now != null) getString(R.string.tour_speaking_hint) else TourState.street.value ?: getString(R.string.tour_listening))
            .setContentIntent(openApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
        if (now != null) {
            b.addAction(action(R.drawable.ic_skip, R.string.skip, ACTION_SKIP))
            if (now.hasMore) b.addAction(action(R.drawable.ic_more, R.string.more, ACTION_MORE))
        } else {
            b.addAction(action(R.drawable.ic_here, R.string.whats_here, ACTION_HERE))
            if (TourState.last.value != null) b.addAction(action(R.drawable.ic_replay, R.string.replay, ACTION_REPLAY))
        }
        b.addAction(action(R.drawable.ic_stop, R.string.end_tour, ACTION_STOP))
        return b.build()
    }

    private fun updateNotification() {
        if (!started) return
        runCatching { getSystemService(android.app.NotificationManager::class.java).notify(NOTIFICATION_ID, notification()) }
    }

    // ================================================================ Wachhalten während der Ansage

    private fun acquireWake() {
        if (wakeLock == null) {
            wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SimpleGuide:speak")
                .apply { setReferenceCounted(false) }
        }
        wakeLock?.acquire(3 * 60_000L)
    }

    private fun releaseWake() {
        wakeLock?.takeIf { it.isHeld }?.release()
    }
}
