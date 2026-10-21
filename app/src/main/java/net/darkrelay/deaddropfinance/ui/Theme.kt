package net.darkrelay.deaddropfinance.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** DarkRelay colors: near-black, cyan accent, teal buttons. Full polish comes in Stage 15. */
object DdfColors {
    val Background = Color(0xFF0B131B)
    val Surface = Color(0xFF111D27)
    val SurfaceHigh = Color(0xFF1A2733)
    val Outline = Color(0xFF24323D)
    val Cyan = Color(0xFF19C2E6)
    val Teal = Color(0xFF0E7490)
    val Text = Color(0xFFE6EEF2)
    val Muted = Color(0xFF8FA3AE)
    val Warning = Color(0xFFF0A35E)
    val Error = Color(0xFFF09595)
}

private val scheme = darkColorScheme(
    primary = DdfColors.Teal,
    onPrimary = DdfColors.Text,
    secondary = DdfColors.Cyan,
    onSecondary = DdfColors.Background,
    secondaryContainer = DdfColors.SurfaceHigh,
    onSecondaryContainer = DdfColors.Cyan,
    primaryContainer = DdfColors.Teal,
    onPrimaryContainer = DdfColors.Text,
    tertiary = DdfColors.Cyan,
    background = DdfColors.Background,
    onBackground = DdfColors.Text,
    surface = DdfColors.Background,
    onSurface = DdfColors.Text,
    surfaceVariant = DdfColors.Surface,
    onSurfaceVariant = DdfColors.Muted,
    surfaceContainer = DdfColors.Surface,
    surfaceContainerHigh = DdfColors.SurfaceHigh,
    surfaceContainerHighest = DdfColors.SurfaceHigh,
    surfaceContainerLow = DdfColors.Surface,
    outline = DdfColors.Outline,
    outlineVariant = DdfColors.Outline,
    error = DdfColors.Error,
)

@Composable
fun DdfTheme(content: @Composable () -> Unit) = MaterialTheme(colorScheme = scheme, content = content)
