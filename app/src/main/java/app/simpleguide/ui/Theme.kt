package app.simpleguide.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

// Nachtblau und Gold wie Simple Narrator, dazu ein Grünton für "unterwegs"
private val Light = lightColorScheme(
    primary = Color(0xFF2E4466), onPrimary = Color.White,
    primaryContainer = Color(0xFFD6E3FF), onPrimaryContainer = Color(0xFF0F1D33),
    secondary = Color(0xFF8A5A00), onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFDDAE), onSecondaryContainer = Color(0xFF2B1700),
    tertiary = Color(0xFF2F6B4F), tertiaryContainer = Color(0xFFB4F1CE),
    background = Color(0xFFFAF9FF), surface = Color(0xFFFAF9FF),
)
private val Dark = darkColorScheme(
    primary = Color(0xFFAFC6F0), onPrimary = Color(0xFF16294A),
    primaryContainer = Color(0xFF2E4466), onPrimaryContainer = Color(0xFFD6E3FF),
    secondary = Color(0xFFF3BD6E), onSecondary = Color(0xFF462B00),
    secondaryContainer = Color(0xFF6A4300), onSecondaryContainer = Color(0xFFFFDDAE),
    tertiary = Color(0xFF98D5B3), tertiaryContainer = Color(0xFF155238),
    background = Color(0xFF111318), surface = Color(0xFF111318),
)

@Composable
fun GuideTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> Dark
        else -> Light
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
