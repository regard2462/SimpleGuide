package app.simpleguide.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.widget.Toast
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import app.simpleguide.BuildConfig
import app.simpleguide.GuideApp
import app.simpleguide.R
import app.simpleguide.data.GuideSettings
import app.simpleguide.data.Interrupt
import app.simpleguide.data.StreetMode
import app.simpleguide.text.Category
import app.simpleguide.text.Detail
import app.simpleguide.tts.DownloadState
import app.simpleguide.tts.VoiceInfo
import app.simpleguide.tts.VoicePack
import app.simpleguide.tts.VoicePacks
import app.simpleguide.util.AppLanguage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(app: GuideApp, modifier: Modifier) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val s by app.settings.flow.collectAsState(initial = GuideSettings())
    val installed by app.models.installed.collectAsState()
    val download by app.models.download.collectAsState()
    val loadingVoice by app.tts.loading.collectAsState()
    var previewing by remember { mutableStateOf(false) }
    fun save(block: (GuideSettings) -> GuideSettings) = scope.launch { app.settings.update(block) }

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text(stringResource(R.string.tab_settings), style = MaterialTheme.typography.headlineSmall, fontFamily = FontFamily.Serif)

        // ------------------------------------------------------------ Inhalt
        Section(stringResource(R.string.sec_content))
        Text(stringResource(R.string.content_lang))
        Segments(listOf(stringResource(R.string.language_de), stringResource(R.string.language_en)), if (s.contentLang == "en") 1 else 0) { i ->
            save { it.copy(contentLang = if (i == 1) "en" else "de") }
        }
        Hint(stringResource(R.string.content_lang_hint))
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.detail))
        Segments(listOf(stringResource(R.string.detail_short), stringResource(R.string.detail_medium), stringResource(R.string.detail_long)), s.detail.ordinal) { i ->
            save { it.copy(detail = Detail.entries[i]) }
        }
        Hint(stringResource(R.string.detail_hint))
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.radius, s.radiusM))
        Slider(value = s.radiusM.toFloat(), onValueChange = { v -> save { it.copy(radiusM = (v / 5).roundToInt() * 5) } }, valueRange = 25f..150f)
        Hint(stringResource(R.string.radius_hint))
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.gap, s.gapSec))
        Slider(value = s.gapSec.toFloat(), onValueChange = { v -> save { it.copy(gapSec = (v / 5).roundToInt() * 5) } }, valueRange = 0f..180f)
        Hint(stringResource(R.string.gap_hint))
        Spacer(Modifier.height(8.dp))
        val repeatOptions = listOf(0, 24, 168, 24 * 365)
        ChoiceRow(
            stringResource(R.string.repeat),
            listOf(stringResource(R.string.repeat_tour), stringResource(R.string.repeat_day), stringResource(R.string.repeat_week), stringResource(R.string.repeat_never)),
            repeatOptions.indexOf(s.repeatAfterHours).coerceAtLeast(0),
        ) { i -> save { it.copy(repeatAfterHours = repeatOptions[i]) } }
        TextButton(onClick = {
            scope.launch { withContext(Dispatchers.IO) { app.repo.db.clearHeard() } }
            Toast.makeText(ctx, ctx.getString(R.string.heard_cleared), Toast.LENGTH_SHORT).show()
        }) { Text(stringResource(R.string.clear_heard)) }

        // ------------------------------------------------------------ Was erzählt wird
        Section(stringResource(R.string.sec_categories))
        Hint(stringResource(R.string.categories_hint))
        Category.entries.forEach { c ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = c in s.categories, onCheckedChange = { on -> save { it.copy(categories = if (on) it.categories + c else it.categories - c) } })
                Text(categoryLabel(c))
            }
        }
        Spacer(Modifier.height(8.dp))
        ChoiceRow(
            stringResource(R.string.streets),
            listOf(stringResource(R.string.streets_off), stringResource(R.string.streets_with_places), stringResource(R.string.streets_always)),
            s.streets.ordinal,
        ) { i -> save { it.copy(streets = StreetMode.entries[i]) } }

        // ------------------------------------------------------------ Musik und Ton
        Section(stringResource(R.string.sec_audio))
        Text(stringResource(R.string.interrupt))
        Segments(listOf(stringResource(R.string.interrupt_pause), stringResource(R.string.interrupt_duck)), s.interrupt.ordinal) { i ->
            save { it.copy(interrupt = Interrupt.entries[i]) }
        }
        Hint(stringResource(R.string.interrupt_hint))
        SwitchRow(stringResource(R.string.chime), stringResource(R.string.chime_hint), s.chime) { v -> save { it.copy(chime = v) } }
        SwitchRow(stringResource(R.string.show_map), stringResource(R.string.show_map_hint), s.showMap) { v -> save { it.copy(showMap = v) } }

        // ------------------------------------------------------------ Daten
        Section(stringResource(R.string.sec_data))
        SwitchRow(stringResource(R.string.online), stringResource(R.string.online_hint), s.online) { v -> save { it.copy(online = v) } }

        // ------------------------------------------------------------ Stimme
        Section(stringResource(R.string.sec_voice))
        Hint(stringResource(R.string.voice_hint, languageName(s.contentLang)))
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.voice_speed, "%.2f".format(s.voiceSpeed)))
        Slider(value = s.voiceSpeed, onValueChange = { v -> save { it.copy(voiceSpeed = (v * 20).roundToInt() / 20f) } }, valueRange = 0.7f..1.5f)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(enabled = !previewing && app.tts.voiceFor(s.contentLang, s) != null, onClick = {
                previewing = true
                scope.launch {
                    runCatching { VoicePreview.play(app.tts, s.contentLang, s) }
                        .onFailure { Toast.makeText(ctx, it.message ?: it.javaClass.simpleName, Toast.LENGTH_LONG).show() }
                    previewing = false
                }
            }) { Text(stringResource(R.string.preview)) }
            loadingVoice?.let { Text("  " + stringResource(R.string.loading_voice, it), style = MaterialTheme.typography.bodySmall) }
        }
        Spacer(Modifier.height(8.dp))
        val lang = s.contentLang
        VoicePacks.forLang(lang).sortedByDescending { it.recommended }.forEach { pack ->
            val selected = if (lang == "en") s.packEn == pack.id else s.packDe == pack.id
            PackCard(
                pack = pack, installed = pack.id in installed, selected = selected, sid = if (lang == "en") s.sidEn else s.sidDe, download = download,
                onDownload = { app.models.startDownload(pack) },
                onDelete = { app.models.delete(pack) },
                onSelect = { save { if (lang == "en") it.copy(packEn = pack.id, sidEn = 0) else it.copy(packDe = pack.id, sidDe = 0) } },
                onVoice = { v -> save { if (lang == "en") it.copy(sidEn = v) else it.copy(sidDe = v) } },
            )
            Spacer(Modifier.height(8.dp))
        }
        Hint(stringResource(R.string.models_location, "Android/data/${ctx.packageName}/files/models/"))
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.threads, s.threads))
        Slider(value = s.threads.toFloat(), onValueChange = { v -> save { it.copy(threads = v.roundToInt()) } }, valueRange = 1f..6f, steps = 4)

        // ------------------------------------------------------------ App
        Section(stringResource(R.string.sec_app))
        val tags = AppLanguage.OPTIONS
        ChoiceRow(
            stringResource(R.string.app_language),
            listOf(stringResource(R.string.language_system), stringResource(R.string.language_de), stringResource(R.string.language_en)),
            tags.indexOf(AppLanguage.current(ctx)).coerceAtLeast(0),
        ) { i -> ctx.findActivity()?.let { AppLanguage.set(it, tags[i]) } }

        HorizontalDivider(Modifier.padding(vertical = 16.dp))
        Text(stringResource(R.string.app_name) + " " + BuildConfig.VERSION_NAME, style = MaterialTheme.typography.bodySmall)
        Hint(stringResource(R.string.licences))
        Spacer(Modifier.height(32.dp))
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@Composable
fun categoryLabel(c: Category): String = stringResource(
    when (c) {
        Category.SACRED -> R.string.cat_sacred
        Category.HISTORIC -> R.string.cat_historic
        Category.MONUMENT -> R.string.cat_monument
        Category.CULTURE -> R.string.cat_culture
        Category.BUILDING -> R.string.cat_building
        Category.NATURE -> R.string.cat_nature
        Category.PLACE -> R.string.cat_place
        Category.OTHER -> R.string.cat_other
        Category.ARTWORK -> R.string.cat_artwork
        Category.DISTRICT -> R.string.cat_district
        Category.TRANSPORT -> R.string.cat_transport
        Category.INSTITUTION -> R.string.cat_institution
    }
)

/** Anzeigename einer Stimme; ohne eigenen Namen „Stimme 3“. */
@Composable
fun voiceName(v: VoiceInfo): String = v.name.ifBlank { stringResource(R.string.voice_number, v.sid + 1) }

@Composable
private fun PackCard(
    pack: VoicePack, installed: Boolean, selected: Boolean, sid: Int, download: DownloadState,
    onDownload: () -> Unit, onDelete: () -> Unit, onSelect: () -> Unit, onVoice: (Int) -> Unit,
) {
    val running = download as? DownloadState.Running
    val failed = download as? DownloadState.Failed
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(pack.name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                if (pack.recommended) AssistChip(onClick = {}, label = { Text(stringResource(R.string.recommended)) })
            }
            Text(pluralStringResource(R.plurals.pack_summary, pack.voices.size, pack.sizeMb, pack.voices.size),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(stringResource(pack.note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
            Spacer(Modifier.height(8.dp))
            when {
                running?.packId == pack.id -> {
                    LinearProgressIndicator(progress = { running.progress }, modifier = Modifier.fillMaxWidth())
                    Text(running.label, style = MaterialTheme.typography.bodySmall)
                }
                installed -> Row(verticalAlignment = Alignment.CenterVertically) {
                    if (selected) {
                        var menu by remember { mutableStateOf(false) }
                        val voice = pack.voices.firstOrNull { it.sid == sid } ?: VoiceInfo("", sid)
                        Box {
                            OutlinedButton(onClick = { menu = true }, enabled = pack.voices.size > 1) { Text(stringResource(R.string.voice_label, voiceName(voice))) }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                pack.voices.forEach { v -> DropdownMenuItem(text = { Text(voiceName(v)) }, onClick = { onVoice(v.sid); menu = false }) }
                            }
                        }
                    } else Button(onClick = onSelect) { Text(stringResource(R.string.use)) }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onDelete) { Text(stringResource(R.string.delete)) }
                }
                else -> {
                    if (failed?.packId == pack.id) Text(failed.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    Button(onClick = onDownload, enabled = running == null) { Text(stringResource(R.string.download)) }
                }
            }
        }
    }
}
