package app.simpleguide.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import app.simpleguide.GuideApp
import app.simpleguide.R
import app.simpleguide.data.GuideSettings
import app.simpleguide.tour.NearbyItem
import app.simpleguide.tour.Preview
import app.simpleguide.tour.Spoken
import java.text.DateFormat
import java.util.Date

/** Sätze einer Ansage als Fließtext, markierbar zum Kopieren. */
@Composable
fun SpokenText(lines: List<String>, modifier: Modifier = Modifier) {
    SelectionContainer(modifier) {
        Text(lines.joinToString(" "), style = MaterialTheme.typography.bodyMedium)
    }
}

/**
 * Was zu einem Ort erzählt würde – zum Lesen statt Hören. Wird erst beim Öffnen zusammengestellt (lädt online
 * fehlende Texte nach) und zeigt auch alle Teile für "Mehr erzählen".
 */
@Composable
fun PlaceTextDialog(app: GuideApp, item: NearbyItem, s: GuideSettings, canPlay: Boolean, onPlay: () -> Unit, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var text by remember(item.place.key) { mutableStateOf<Preview.Text?>(null) }
    LaunchedEffect(item.place.key) {
        text = runCatching { Preview.of(app, item.place, item.side, item.distanceM, s) }.getOrElse { Preview.Text(emptyList(), emptyList()) }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(item.place.name, fontFamily = FontFamily.Serif) },
        text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
                val t = text
                when {
                    t == null -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.padding(end = 12.dp))
                        Text(stringResource(R.string.preview_loading))
                    }
                    t.lines.isEmpty() -> Text(stringResource(R.string.preview_empty))
                    else -> {
                        SpokenText(t.lines)
                        t.more.forEachIndexed { i, chunk ->
                            Spacer(Modifier.height(12.dp))
                            Text(stringResource(R.string.preview_more, i + 1), style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary)
                            SpokenText(chunk)
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Hint(stringResource(R.string.preview_source))
            }
        },
        confirmButton = {
            if (canPlay) TextButton(onClick = { onDismiss(); onPlay() }) { Text(stringResource(R.string.read_aloud)) }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(item.place.withUrl()))) }) {
                    Text(stringResource(R.string.open_wikipedia))
                }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) }
            }
        },
    )
}

/** Verlauf aller Ansagen zum Nachlesen; aufklappbar, neueste zuerst. */
@Composable
fun HistorySection(history: List<Spoken>) {
    var open by rememberSaveable { mutableStateOf(false) }
    val time = remember { DateFormat.getTimeInstance(DateFormat.SHORT) }
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().clickable { open = !open }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.history, history.size), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Icon(if (open) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null)
        }
        if (open) history.forEach { h ->
            HorizontalDivider()
            Column(Modifier.padding(vertical = 8.dp)) {
                Text(time.format(Date(h.at)) + " · " + h.title, style = MaterialTheme.typography.labelLarge)
                if (!h.voiced) Text(stringResource(R.string.not_voiced), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error)
                SpokenText(h.lines)
            }
        }
    }
}
