package app.simpleguide.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Notes
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import app.simpleguide.GuideApp
import app.simpleguide.R
import app.simpleguide.data.GuideSettings
import app.simpleguide.geo.Geo
import app.simpleguide.geo.Side
import app.simpleguide.tour.NearbyItem
import app.simpleguide.tour.TourService
import app.simpleguide.tour.TourState
import app.simpleguide.tts.DownloadState
import app.simpleguide.tts.VoicePacks

@Composable
fun TourScreen(app: GuideApp, modifier: Modifier, onOpenSettings: () -> Unit) {
    val ctx = LocalContext.current
    val s by app.settings.flow.collectAsState(initial = GuideSettings())
    val running by TourState.running.collectAsState()
    val status by TourState.status.collectAsState()
    val nearby by TourState.nearby.collectAsState()
    val speaking by TourState.speaking.collectAsState()
    val last by TourState.last.collectAsState()
    val street by TourState.street.collectAsState()
    val accuracy by TourState.accuracyM.collectAsState()
    val heading by TourState.heading.collectAsState()
    val position by TourState.position.collectAsState()
    val installed by app.models.installed.collectAsState()
    val download by app.models.download.collectAsState()
    val loading by app.repo.onlineState.collectAsState()
    val history by TourState.history.collectAsState()
    var selectedKey by rememberSaveable { mutableStateOf<String?>(null) }
    var textFor by remember { mutableStateOf<NearbyItem?>(null) }
    var showText by rememberSaveable { mutableStateOf(true) }
    val selected = nearby.firstOrNull { it.place.key == selectedKey }
    val hasVoice = app.tts.voiceFor(s.contentLang, s) != null || installed.any { id -> VoicePacks.byId(id)?.supports(s.contentLang) == true }

    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted[Manifest.permission.ACCESS_FINE_LOCATION] == true || granted[Manifest.permission.ACCESS_COARSE_LOCATION] == true) {
            TourService.start(ctx)
        } else TourState.status.value = TourState.Status.NO_PERMISSION
    }
    fun startTour() {
        val need = buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION); add(Manifest.permission.ACCESS_COARSE_LOCATION)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.filter { ContextCompat.checkSelfPermission(ctx, it) != PackageManager.PERMISSION_GRANTED }
        if (need.isEmpty()) TourService.start(ctx) else permissions.launch(need.toTypedArray())
    }

    textFor?.let { item ->
        PlaceTextDialog(app, item, s, canPlay = running,
            onPlay = { TourService.send(ctx, TourService.ACTION_PLAY, item.place) }, onDismiss = { textFor = null })
    }

    LazyColumn(modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp)) {
        item {
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium, fontFamily = FontFamily.Serif)
            Text(stringResource(R.string.tagline), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(16.dp))
        }

        // ---- Keine Stimme: direkt die empfohlene anbieten
        if (!hasVoice) item {
            val pack = VoicePacks.forLang(s.contentLang).filter { it.recommended }.minByOrNull { it.sizeMb }
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                Column(Modifier.padding(16.dp)) {
                    Text(stringResource(R.string.need_voice_title), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.need_voice_text), style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(8.dp))
                    val running = download as? DownloadState.Running
                    if (running != null) {
                        LinearProgressIndicator(progress = { running.progress }, modifier = Modifier.fillMaxWidth())
                        Text(running.label, style = MaterialTheme.typography.bodySmall)
                    } else if (pack != null) Column {
                        Button(onClick = { app.models.startDownload(pack) }, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.download_voice, pack.name, pack.sizeMb))
                        }
                        TextButton(onClick = onOpenSettings) { Text(stringResource(R.string.other_voices)) }
                    }
                    (download as? DownloadState.Failed)?.let { Text(it.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                }
            }
            Spacer(Modifier.height(16.dp))
        }

        // ---- Start / Stopp
        item {
            if (!running) {
                Button(onClick = { startTour() }, modifier = Modifier.fillMaxWidth().height(64.dp)) {
                    Icon(Icons.Default.PlayArrow, null); Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.start_tour), style = MaterialTheme.typography.titleMedium)
                }
                Spacer(Modifier.height(12.dp))
                if (status == TourState.Status.NO_PERMISSION) {
                    Text(stringResource(R.string.no_permission), color = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.height(8.dp))
                }
                Text(stringResource(R.string.how_it_works), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                OutlinedButton(onClick = { TourService.send(ctx, TourService.ACTION_STOP) }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Stop, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.end_tour))
                }
            }
            Spacer(Modifier.height(12.dp))
        }

        if (running) {
            // ---- Gerade gesprochen
            item {
                val now = speaking
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                    Column(Modifier.padding(16.dp)) {
                        if (now != null) {
                            Text(stringResource(R.string.now_speaking), style = MaterialTheme.typography.labelMedium)
                            Text(now.title, style = MaterialTheme.typography.titleLarge, fontFamily = FontFamily.Serif)
                            if (now.text.isNotEmpty()) {
                                TextButton(onClick = { showText = !showText }, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
                                    Text(stringResource(if (showText) R.string.hide_text else R.string.show_text))
                                }
                                if (showText) SpokenText(now.text, Modifier.padding(bottom = 8.dp))
                            }
                            Spacer(Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilledTonalButton(onClick = { TourService.send(ctx, TourService.ACTION_SKIP) }) {
                                    Icon(Icons.Default.SkipNext, null); Spacer(Modifier.width(4.dp)); Text(stringResource(R.string.skip))
                                }
                                if (now.hasMore) OutlinedButton(onClick = { TourService.send(ctx, TourService.ACTION_MORE) }) {
                                    Icon(Icons.Default.UnfoldMore, null); Spacer(Modifier.width(4.dp)); Text(stringResource(R.string.more))
                                }
                            }
                        } else {
                            Text(statusText(status, nearby.size), style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilledTonalButton(onClick = { TourService.send(ctx, TourService.ACTION_HERE) }) {
                                    Icon(Icons.Default.Explore, null); Spacer(Modifier.width(4.dp)); Text(stringResource(R.string.whats_here))
                                }
                                last?.let { l ->
                                    OutlinedButton(onClick = { TourService.send(ctx, TourService.ACTION_REPLAY) }) {
                                        Icon(Icons.Default.Replay, null); Spacer(Modifier.width(4.dp))
                                        Text(stringResource(R.string.replay_named, l.name), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                val info = buildList {
                    street?.let { add(it) }
                    accuracy?.let { add(stringResource(R.string.gps_accuracy, it.toInt())) }
                    if (heading == null && position != null) add(stringResource(R.string.standing))
                    if (loading is app.simpleguide.data.Repository.OnlineState.Loading) add(stringResource(R.string.loading_articles))
                    (loading as? app.simpleguide.data.Repository.OnlineState.Failed)?.let { add(stringResource(R.string.offline_now)) }
                }.joinToString(" · ")
                if (info.isNotEmpty()) Text(info, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (status == TourState.Status.NO_VOICE) Text(stringResource(R.string.no_voice_running), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                if (s.showMap && position != null) {
                    Spacer(Modifier.height(12.dp))
                    Card(Modifier.fillMaxWidth().height(300.dp)) {
                        TourMap(
                            position = position, heading = heading, accuracyM = accuracy, items = nearby,
                            speakingKey = speaking?.place?.key, selectedKey = selectedKey, radiusM = s.radiusM,
                            onSelect = { selectedKey = it?.key },
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                    selected?.let { item ->
                        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(item.place.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("${item.distanceM} m" + (item.place.description?.let { " · $it" } ?: ""),
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            IconButton(onClick = { TourService.send(ctx, TourService.ACTION_PLAY, item.place) }) {
                                Icon(Icons.Default.PlayArrow, stringResource(R.string.read_aloud))
                            }
                            IconButton(onClick = { textFor = item }) { Icon(Icons.AutoMirrored.Filled.Notes, stringResource(R.string.read_text)) }
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
                Text(stringResource(R.string.nearby), style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
            }
            items(nearby, key = { it.place.key }) { item ->
                NearbyRow(item, heading, onPlay = { TourService.send(ctx, TourService.ACTION_PLAY, item.place) }, onText = { textFor = item })
            }
        }
        if (history.isNotEmpty()) item {
            Spacer(Modifier.height(16.dp))
            HistorySection(history)
        }
        if (running || history.isNotEmpty()) item {
            Spacer(Modifier.height(16.dp))
            Text(stringResource(R.string.attribution), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun statusText(status: TourState.Status, count: Int): String = when (status) {
    TourState.Status.WAITING_GPS -> stringResource(R.string.status_waiting_gps)
    TourState.Status.LOADING -> stringResource(R.string.status_loading)
    TourState.Status.OFFLINE_EMPTY -> stringResource(R.string.status_empty)
    TourState.Status.NO_VOICE -> stringResource(R.string.status_no_voice)
    else -> if (count == 0) stringResource(R.string.status_nothing) else stringResource(R.string.status_listening)
}

@Composable
private fun NearbyRow(item: NearbyItem, heading: Double?, onPlay: () -> Unit, onText: () -> Unit) {
    val p = item.place
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onPlay).padding(vertical = 8.dp).alpha(if (item.heard) 0.55f else 1f),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Pfeil relativ zur Gehrichtung, ohne Gehrichtung nach Norden ausgerichtet
        val pos = TourState.position.value
        val bearing = pos?.let { Geo.bearing(it, p.pos) } ?: 0.0
        val angle = if (heading != null) Geo.angleDiff(heading, bearing) else bearing
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(56.dp)) {
            Icon(Icons.Default.ArrowUpward, null, Modifier.size(22.dp).rotate(angle.toFloat()),
                tint = if (item.inRange) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant)
            Text("${item.distanceM} m", style = MaterialTheme.typography.labelSmall)
        }
        Column(Modifier.weight(1f)) {
            Text(p.name, style = MaterialTheme.typography.bodyLarge, fontWeight = if (item.inRange) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            val sub = listOfNotNull(sideLabel(item.side, heading != null), p.description).joinToString(" · ")
            if (sub.isNotEmpty()) Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        IconButton(onClick = onText) { Icon(Icons.AutoMirrored.Filled.Notes, stringResource(R.string.read_text)) }
    }
}

@Composable
private fun sideLabel(side: Side, known: Boolean): String? = if (!known) null else when (side) {
    Side.AHEAD -> stringResource(R.string.side_ahead)
    Side.AHEAD_RIGHT -> stringResource(R.string.side_ahead_right)
    Side.AHEAD_LEFT -> stringResource(R.string.side_ahead_left)
    Side.RIGHT -> stringResource(R.string.side_right)
    Side.LEFT -> stringResource(R.string.side_left)
    Side.BEHIND -> stringResource(R.string.side_behind)
    Side.UNKNOWN -> null
}
