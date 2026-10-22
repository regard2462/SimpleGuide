package app.simpleguide.ui

import android.text.format.Formatter
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import app.simpleguide.GuideApp
import app.simpleguide.R
import app.simpleguide.data.City
import app.simpleguide.data.Downloads
import app.simpleguide.data.GuideSettings
import app.simpleguide.data.limited
import app.simpleguide.map.MapOffline
import app.simpleguide.wiki.Osm
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun CitiesScreen(app: GuideApp, modifier: Modifier) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val s by app.settings.flow.collectAsState(initial = GuideSettings())
    val progress by Downloads.progress.collectAsState()
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<Osm.Found>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirm by remember { mutableStateOf<Osm.Found?>(null) }
    var deleting by remember { mutableStateOf<City?>(null) }
    var cities by remember { mutableStateOf<List<City>>(emptyList()) }

    // Liste der Städte auffrischen, solange die Seite offen ist (Fortschritt steht in der Datenbank)
    LaunchedEffect(Unit) {
        while (true) {
            cities = withContext(Dispatchers.IO) { app.repo.db.cities() }
            delay(if (progress.isNotEmpty()) 1000 else 3000)
        }
    }

    fun search() {
        if (query.isBlank()) return
        searching = true; error = null
        scope.launch {
            runCatching { Osm.search(query.trim(), s.contentLang) }
                .onSuccess { results = it; if (it.isEmpty()) error = ctx.getString(R.string.nothing_found) }
                .onFailure { error = ctx.getString(R.string.search_failed, it.message ?: "") }
            searching = false
        }
    }

    confirm?.let { f ->
        var texts by remember { mutableStateOf(true) }
        var streets by remember { mutableStateOf(true) }
        var withMap by remember { mutableStateOf(true) }
        val box = f.box.limited()
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(stringResource(R.string.download_city, f.name)) },
            text = {
                Column {
                    Text(stringResource(R.string.download_city_area, (box.widthM / 1000).toInt().coerceAtLeast(1), (box.heightM / 1000).toInt().coerceAtLeast(1)))
                    Spacer(Modifier.height(8.dp))
                    SwitchRow(stringResource(R.string.dl_opt_texts), stringResource(R.string.dl_opt_texts_hint), texts) { texts = it }
                    SwitchRow(stringResource(R.string.dl_opt_streets), stringResource(R.string.dl_opt_streets_hint), streets) { streets = it }
                    SwitchRow(stringResource(R.string.dl_opt_map), stringResource(R.string.dl_opt_map_hint), withMap) { withMap = it }
                    Hint(stringResource(R.string.dl_lang_hint, languageName(s.contentLang)))
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    confirm = null
                    scope.launch {
                        val id = withContext(Dispatchers.IO) { app.repo.db.addCity(s.contentLang, f.name, box, texts, streets, withMap) }
                        Downloads.start(ctx, id)
                        results = emptyList(); query = ""
                    }
                }) { Text(stringResource(R.string.download)) }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }

    deleting?.let { c ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.delete_city, c.name)) },
            text = { Text(stringResource(R.string.delete_city_hint)) },
            confirmButton = {
                TextButton(onClick = {
                    deleting = null
                    Downloads.cancel(ctx, c.id)
                    scope.launch {
                        withContext(Dispatchers.IO) { app.repo.db.deleteCity(c.id) }
                        runCatching { MapOffline.delete(ctx, c.id) }
                        cities = cities.filter { it.id != c.id }
                    }
                }) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }

    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp)) {
        item {
            Text(stringResource(R.string.cities_title), style = MaterialTheme.typography.headlineSmall, fontFamily = FontFamily.Serif)
            Hint(stringResource(R.string.cities_hint))
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = query, onValueChange = { query = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.city_search)) },
                trailingIcon = {
                    if (searching) CircularProgressIndicator(Modifier.padding(12.dp))
                    else IconButton(onClick = { search() }) { Icon(Icons.Default.Search, stringResource(R.string.city_search)) }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { search() }),
            )
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }
        items(results) { f ->
            Column(Modifier.fillMaxWidth().clickable { confirm = f }.padding(vertical = 10.dp)) {
                Text(f.name, style = MaterialTheme.typography.bodyLarge)
                Text(f.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
            }
            HorizontalDivider()
        }
        item {
            Spacer(Modifier.height(20.dp))
            Text(stringResource(R.string.downloaded), style = MaterialTheme.typography.titleMedium)
            if (cities.isEmpty()) Hint(stringResource(R.string.no_cities))
            Spacer(Modifier.height(8.dp))
        }
        items(cities, key = { it.id }) { c ->
            CityCard(c, progress[c.id],
                onPause = { Downloads.cancel(ctx, c.id) },
                onResume = { Downloads.start(ctx, c.id) },
                onDelete = { deleting = c },
            )
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun CityCard(c: City, p: Downloads.Progress?, onPause: () -> Unit, onResume: () -> Unit, onDelete: () -> Unit) {
    val ctx = LocalContext.current
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(c.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Text(c.lang.uppercase(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(
                stringResource(R.string.city_stats, c.places, c.texts, c.streets, Formatter.formatShortFileSize(ctx, c.bytes)),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val active = p != null || c.state == "running" || c.state == "queued"
            if (p != null) {
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(progress = { p.fraction }, modifier = Modifier.fillMaxWidth())
                Text(p.label, style = MaterialTheme.typography.bodySmall)
            } else {
                val state = when (c.state) {
                    "done" -> stringResource(R.string.city_done)
                    "failed" -> stringResource(R.string.city_failed, c.message)
                    "paused" -> stringResource(R.string.city_paused)
                    else -> stringResource(R.string.city_waiting)
                }
                Text(state, style = MaterialTheme.typography.bodySmall,
                    color = if (c.state == "failed") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                if (c.state == "done" && c.message.isNotEmpty()) Hint(c.message)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (active && p != null) TextButton(onClick = onPause) { Text(stringResource(R.string.pause)) }
                else TextButton(onClick = onResume) { Text(stringResource(if (c.state == "done") R.string.update else R.string.resume)) }
                Spacer(Modifier.width(4.dp))
                TextButton(onClick = onDelete) { Text(stringResource(R.string.delete)) }
            }
        }
    }
}

@Composable
fun languageName(lang: String): String = stringResource(if (lang == "en") R.string.language_en else R.string.language_de)
