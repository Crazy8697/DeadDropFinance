package net.darkrelay.deaddropfinance.desktop

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.SystemFont
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.darkrelay.deaddropfinance.domain.Money

/** The DarkRelay look from the approved mockup (desktop/docs/desktop-mockup.html): its colors, one for one. */
/**
 * The program's colors. The window background, cards, their borders, text and accents are the shared ProgramPalette
 * (ui Theme.kt, 0.14.1), the same values the app's screens use when the program shows them; the rest are the
 * program's own shades of those. No screen sets a color of its own (ProgramColorsTest).
 */
object C {
    private val P = net.darkrelay.deaddropfinance.ui.ProgramPalette
    val Bg = P.background
    val Panel = P.surface
    val Panel2 = Color(0xFF151C22)
    val Line = P.outline
    val Line2 = P.outlineHigh
    val Text = P.text
    val Dim = P.muted
    val Dim2 = Color(0xFF5F7382)
    val Cyan = P.cyan
    val Teal = P.teal
    val TealHi = Color(0xFF12899F)
    val Good = Color(0xFF2FBF71)
    val Bad = P.error
    val Warn = P.warning
    /** The menu strip above everything, a shade darker than a card. */
    val MenuBar = Color(0xFF0D1217)
    /** Text on a teal button. */
    val OnTeal = Color(0xFFEAF8FC)
    /** A switch's track when it's off. */
    val SwitchOff = Color(0xFF202C35)
    val Hover = Color(0xFF141C23)
    val NavHover = Color(0xFF17212A)
    val Input = Color(0xFF0C1116)
    val Group = Color(0xFF0E1419)
    val BarTrack = Color(0xFF1B242C)
    val Scrim = Color(0xB8030609)
}

@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
val Segoe: FontFamily = FontFamily(SystemFont("Segoe UI"))

fun ts(size: TextUnit, color: Color = C.Text, weight: FontWeight = FontWeight.Normal, spacing: TextUnit = TextUnit.Unspecified) =
    TextStyle(fontFamily = Segoe, fontSize = size, color = color, fontWeight = weight, letterSpacing = spacing)

@Composable
fun T(text: String, size: TextUnit = 13.5.sp, color: Color = C.Text, weight: FontWeight = FontWeight.Normal, modifier: Modifier = Modifier,
      align: TextAlign? = null, spacing: TextUnit = TextUnit.Unspecified, maxLines: Int = Int.MAX_VALUE) =
    Text(text, modifier, style = ts(size, color, weight, spacing), textAlign = align, maxLines = maxLines)

/** Small caps-style label (the mockup's uppercase, letter-spaced headings). */
@Composable
fun Label(text: String, color: Color = C.Dim2, size: TextUnit = 11.sp, modifier: Modifier = Modifier) =
    T(text.uppercase(), size, color, FontWeight.SemiBold, modifier, spacing = 0.7.sp)

fun money(cents: Long) = Money.format(cents)
fun moneyColor(cents: Long) = when { cents > 0 -> C.Good; cents < 0 -> C.Bad; else -> C.Dim2 }

@Composable
fun hovered(source: MutableInteractionSource): Boolean = source.collectIsHoveredAsState().value

/** The mockup's .btn (teal), .btn.ghost and .btn.sm. */
@Composable
fun Btn(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, ghost: Boolean = false, small: Boolean = false, enabled: Boolean = true, tag: String? = null) {
    val src = remember { MutableInteractionSource() }
    val hover = hovered(src) && enabled
    val shape = RoundedCornerShape(if (small) 6.dp else 7.dp)
    val bg = when { ghost && hover -> C.Hover; ghost -> Color.Transparent; hover -> C.TealHi; else -> C.Teal }
    val border = when { ghost && hover -> C.Teal; ghost -> C.Line2; else -> C.TealHi }
    val fg = when { ghost && hover -> C.Text; ghost -> C.Dim; else -> C.OnTeal }
    Box(
        modifier.alpha(if (enabled) 1f else 0.4f).clip(shape).background(bg).border(1.dp, border, shape)
            .hoverable(src).let { if (enabled) it.clickable(interactionSource = src, indication = null, onClick = onClick) else it }
            .padding(horizontal = if (small) 9.dp else 13.dp, vertical = if (small) 3.dp else 6.dp)
            .let { if (tag != null) it.testTag(tag) else it },
        contentAlignment = Alignment.Center,
    ) { T(text, if (small) 12.sp else 13.sp, fg) }
}

/** The mockup's .tag (and .tag.cy). */
@Composable
fun Tag(text: String, cyan: Boolean = false, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(4.dp)
    Box(modifier.border(1.dp, if (cyan) C.Cyan.copy(alpha = 0.4f) else C.Line2, shape).padding(horizontal = 6.dp, vertical = 1.dp)) {
        T(text, 10.sp, if (cyan) C.Cyan else C.Dim2, spacing = 0.4.sp)
    }
}

/** The mockup's .card (and .card.hi). */
@Composable
fun StatCard(label: String, value: String, sub: String?, modifier: Modifier = Modifier, hi: Boolean = false, cyan: Boolean = false, valueSize: TextUnit = 24.sp, tag: String? = null) {
    val shape = RoundedCornerShape(10.dp)
    Column(
        modifier.clip(shape).background(C.Panel).border(1.dp, if (hi) C.Cyan.copy(alpha = 0.4f) else C.Line, shape)
            .padding(horizontal = 16.dp, vertical = 14.dp).let { if (tag != null) it.testTag(tag) else it },
    ) {
        Label(label)
        T(value, valueSize, if (cyan) C.Cyan else C.Text, FontWeight.SemiBold, Modifier.padding(top = 5.dp).let { if (tag != null) it.testTag("$tag.value") else it })
        if (sub != null) T(sub, 11.5.sp, C.Dim, modifier = Modifier.padding(top = 3.dp))
    }
}

/** A bordered panel (the mockup's .panel and the table / activity frames). */
@Composable
fun Frame(modifier: Modifier = Modifier, dashed: Boolean = false, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    val m = if (dashed) modifier.clip(shape).background(C.Panel).drawBehind {
        drawRoundRect(C.Line2, cornerRadius = androidx.compose.ui.geometry.CornerRadius(10.dp.toPx()),
            style = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx(), pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))))
    } else modifier.clip(shape).background(C.Panel).border(BorderStroke(1.dp, C.Line), shape)
    Box(m) { content() }
}

@Composable
fun RowHead(title: String, modifier: Modifier = Modifier, actions: @Composable RowScope.() -> Unit = {}) {
    Row(modifier.fillMaxWidth().padding(bottom = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        T(title.uppercase(), 13.sp, C.Dim2, FontWeight.SemiBold, spacing = 0.8.sp)
        Box(Modifier.weight(1f))
        actions()
    }
}

/** The progress bar under an envelope's numbers: teal to cyan, green when full, red when overspent. */
@Composable
fun ProgressBar(fraction: Float, full: Boolean, over: Boolean, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)).background(C.BarTrack)) {
        val f = fraction.coerceIn(0f, 1f)
        if (f > 0f) {
            val brush = when {
                over -> SolidColor(C.Bad)
                full -> SolidColor(C.Good)
                else -> Brush.horizontalGradient(listOf(C.Teal, C.Cyan))
            }
            Box(Modifier.fillMaxWidth(f).height(5.dp).background(brush))
        }
    }
}

/** The mockup's switch (.sw). */
@Composable
fun Switch(on: Boolean, onChange: (Boolean) -> Unit, tag: String? = null) {
    val shape = RoundedCornerShape(50)
    Box(
        Modifier.width(38.dp).height(21.dp).clip(shape).background(if (on) C.Teal.copy(alpha = 0.5f) else C.SwitchOff)
            .border(1.dp, if (on) C.Teal else C.Line2, shape).clickable { onChange(!on) }.let { if (tag != null) it.testTag(tag) else it },
    ) {
        Box(Modifier.offset(x = if (on) 19.dp else 2.dp, y = 2.dp).size(15.dp).clip(CircleShape).background(if (on) C.Cyan else C.Dim2))
    }
}

/** A labeled text field (the mockup's .f input). */
@Composable
fun Field(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier, placeholder: String = "", tag: String? = null, masked: Boolean = false) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Label(label)
        val shape = RoundedCornerShape(7.dp)
        Box(Modifier.fillMaxWidth().clip(shape).background(C.Input).border(1.dp, C.Line2, shape).padding(horizontal = 11.dp, vertical = 8.dp)) {
            if (value.isEmpty() && placeholder.isNotEmpty()) T(placeholder, 13.5.sp, C.Dim2)
            BasicTextField(
                value, onChange, singleLine = true, textStyle = ts(13.5.sp), cursorBrush = SolidColor(C.Cyan),
                visualTransformation = if (masked) androidx.compose.ui.text.input.PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
                modifier = Modifier.fillMaxWidth().let { if (tag != null) it.testTag(tag) else it },
            )
        }
    }
}

/** A labeled choice (the mockup's .f select): click to open the list. */
@Composable
fun <K> Select(label: String, options: List<Pair<K, String>>, selected: K, onSelect: (K) -> Unit, modifier: Modifier = Modifier, tag: String? = null, placeholder: String = "") {
    var open by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Label(label)
        val shape = RoundedCornerShape(7.dp)
        Box {
            Row(
                Modifier.fillMaxWidth().clip(shape).background(C.Input).border(1.dp, if (open) C.Cyan else C.Line2, shape)
                    .clickable { open = true }.padding(horizontal = 11.dp, vertical = 8.dp).let { if (tag != null) it.testTag(tag) else it },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val shown = options.firstOrNull { it.first == selected }?.second
                T(shown ?: placeholder, 13.5.sp, if (shown == null) C.Dim2 else C.Text, modifier = Modifier.weight(1f).let { if (tag != null) it.testTag("$tag.value") else it }, maxLines = 1)
                T("▾", 12.sp, C.Dim)
            }
            androidx.compose.material3.DropdownMenu(open, { open = false }, containerColor = C.Panel2, modifier = Modifier.border(1.dp, C.Line2)) {
                options.forEach { (k, name) ->
                    androidx.compose.material3.DropdownMenuItem(
                        text = { T(name, 13.5.sp, if (k == selected) C.Cyan else C.Text) },
                        onClick = { onSelect(k); open = false },
                        modifier = Modifier.let { if (tag != null) it.testTag("$tag.option.$name") else it },
                    )
                }
            }
        }
    }
}

@Composable
fun Glow(size: Dp, radius: Dp, modifier: Modifier = Modifier) {
    Box(modifier.size(size).clip(RoundedCornerShape(radius)).background(Brush.radialGradient(listOf(C.Cyan, C.Teal))))
}

/**
 * A checkbox with its label and help line, greyed and unclickable when not [enabled] (the Payday and Fill envelopes
 * boxes). Toggleable semantics, so it reads as a checkbox to tests and screen readers.
 */
@Composable
fun CheckRow(title: String, sub: String, checked: Boolean, enabled: Boolean, tag: String, modifier: Modifier = Modifier, onChange: (Boolean) -> Unit) {
    Row(
        modifier.alpha(if (enabled) 1f else 0.4f)
            .toggleable(value = checked, enabled = enabled, role = androidx.compose.ui.semantics.Role.Checkbox, onValueChange = onChange)
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val shape = RoundedCornerShape(4.dp)
        Box(
            Modifier.size(16.dp).clip(shape).background(if (checked) C.Teal else C.Input).border(1.dp, if (checked) C.Cyan else C.Line2, shape),
            contentAlignment = Alignment.Center,
        ) { if (checked) T("✓", 11.sp, C.Text, FontWeight.Bold) }
        Box(Modifier.width(10.dp))
        Column {
            T(title, 13.5.sp)
            T(sub, 11.5.sp, C.Dim2)
        }
    }
}
