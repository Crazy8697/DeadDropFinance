package net.darkrelay.deaddropfinance.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp

/** DarkRelay colors (the reference mockup's): near-black, cyan accent, teal buttons. */
object DdfColors {
    /** The phone's colors, or the program's (0.14.1) while its window shows these screens: set once by the host. */
    var palette: DdfPalette = PhonePalette
    val Background get() = palette.background
    val Surface get() = palette.surface
    val SurfaceHigh get() = palette.surfaceHigh
    val Outline get() = palette.outline
    val OutlineHigh get() = palette.outlineHigh
    val Cyan get() = palette.cyan
    val Teal get() = palette.teal
    val Text get() = palette.text
    val Muted get() = palette.muted
    val Warning get() = palette.warning
    val Error get() = palette.error
}

/** One set of the colors the screens use: window background, cards (surface), their borders (outline), text, accents. */
data class DdfPalette(
    val background: Color, val surface: Color, val surfaceHigh: Color, val outline: Color, val outlineHigh: Color,
    val text: Color, val muted: Color, val cyan: Color, val teal: Color, val warning: Color, val error: Color,
)

/** The phone app's colors. */
val PhonePalette = DdfPalette(
    background = Color(0xFF0B131B), surface = Color(0xFF111D27), surfaceHigh = Color(0xFF1A2733), outline = Color(0xFF24323D),
    outlineHigh = Color(0xFF24323D), text = Color(0xFFE6EEF2), muted = Color(0xFF8FA3AE), cyan = Color(0xFF19C2E6),
    teal = Color(0xFF0E7490), warning = Color(0xFFFAC775), error = Color(0xFFF09595),
)

/**
 * The Windows program's colors (0.14.1), the one set its own screens (desktop Theme.kt's C) and the app's screens it
 * shows both use: the window background, a card and its border, as on Accounts and Settings.
 */
val ProgramPalette = DdfPalette(
    background = Color(0xFF0A0D10), surface = Color(0xFF11161B), surfaceHigh = Color(0xFF11161B), outline = Color(0xFF1F2A33),
    // 0.15.0: text and muted text as DarkRelay's other Windows programs have them (#e6e6e6, #9aa0a6)
    outlineHigh = Color(0xFF2A3843), text = Color(0xFFE6E6E6), muted = Color(0xFF9AA0A6), cyan = Color(0xFF19C2E6),
    teal = Color(0xFF0E7490), warning = Color(0xFFD9A441), error = Color(0xFFE2574C),
)

/** The report charts' extra series colors (1.9.0), after cyan and amber: green, violet, rose, sky. Both palettes use them. */
val ChartColors = listOf(Color(0xFF2FBF71), Color(0xFFA78BFA), Color(0xFFF472B6), Color(0xFF7DD3FC))

/** The app's screens shown in the program (0.14.1): their text actions are drawn as the program's buttons. */
val LocalProgramStyle = androidx.compose.runtime.staticCompositionLocalOf { false }

private fun scheme() = darkColorScheme(
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

/**
 * The text sizes (1.9.1, program 0.15.1): the phone's are Material's own; with the program's colors (the screens shown
 * inside the program) they're the program's, in its font, so a hosted screen's text matches its sidebar and top bar.
 * Picked here, once, with the palette, as the colors are.
 */
@Composable
fun DdfTheme(content: @Composable () -> Unit) =
    MaterialTheme(colorScheme = scheme(), typography = if (DdfColors.palette == ProgramPalette) ProgramType.typography() else PhoneTypography, content = content)

/** The phone's text sizes: Material 3's. */
val PhoneTypography = androidx.compose.material3.Typography()

/** The program's text sizes (sp): its body 13.5 (the sidebar's), its top bar title 16, its hints 11.5. */
object ProgramType {
    /** The program's font (Segoe UI), set by the program with the palette; the default font until then. */
    var family: androidx.compose.ui.text.font.FontFamily = androidx.compose.ui.text.font.FontFamily.Default

    private fun s(size: Double, line: Double, weight: androidx.compose.ui.text.font.FontWeight = androidx.compose.ui.text.font.FontWeight.Normal) =
        androidx.compose.ui.text.TextStyle(fontFamily = family, fontWeight = weight, fontSize = size.sp, lineHeight = line.sp)

    fun typography() = androidx.compose.material3.Typography(
        displayLarge = s(30.0, 38.0), displayMedium = s(26.0, 34.0), displaySmall = s(22.0, 30.0),
        headlineLarge = s(22.0, 30.0), headlineMedium = s(20.0, 28.0), headlineSmall = s(17.0, 24.0),
        titleLarge = s(16.0, 22.0, androidx.compose.ui.text.font.FontWeight.SemiBold), titleMedium = s(14.0, 20.0, androidx.compose.ui.text.font.FontWeight.Medium),
        titleSmall = s(13.5, 19.0, androidx.compose.ui.text.font.FontWeight.Medium),
        bodyLarge = s(13.5, 19.0), bodyMedium = s(13.0, 18.0), bodySmall = s(11.5, 16.0),
        labelLarge = s(13.0, 18.0, androidx.compose.ui.text.font.FontWeight.Medium), labelMedium = s(12.0, 16.0), labelSmall = s(11.0, 15.0),
    )
}
