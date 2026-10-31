package net.darkrelay.deaddropfinance.ui

import androidx.compose.ui.draw.alpha
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import java.time.LocalDate
import net.darkrelay.deaddropfinance.domain.Money

@Composable
fun MoneyText(cents: Long, modifier: Modifier = Modifier, bold: Boolean = false, color: Color? = null, small: Boolean = false) {
    Text(
        Money.format(cents),
        modifier = modifier,
        color = color ?: moneyColor(cents),
        fontWeight = if (bold) FontWeight.Medium else null,
        style = if (small) MaterialTheme.typography.bodySmall else LocalTextStyle.current,
    )
}

/** Negative money is always shown in the warning color, with its minus sign (Money.format). */
@Composable
fun moneyColor(cents: Long): Color = if (cents < 0) DdfColors.Warning else MaterialTheme.colorScheme.onSurface

/** What a list says when it's empty (a new install starts empty everywhere). */
@Composable
fun EmptyState(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier.fillMaxWidth().padding(vertical = 24.dp, horizontal = 16.dp),
        color = DdfColors.Muted,
        textAlign = TextAlign.Center,
    )
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier, trailing: @Composable (() -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
        trailing?.invoke()
    }
}

@Composable
fun ScreenTitle(title: String, onBack: (() -> Unit)?, actions: @Composable () -> Unit = {}) {
    // a sidebar item's first screen in the program: the program's top bar already names it
    if (LocalHostedRoot.current) {
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.End, verticalAlignment = Alignment.CenterVertically) { actions() }
        return
    }
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) TextButton(onClick = onBack) { Text("Back") }
        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f).padding(start = if (onBack == null) 4.dp else 0.dp))
        actions()
    }
}

/** A 0..1 progress bar in the accent color (UI only; the domain never uses fractions of money). */
@Composable
fun Progress(fraction: Float, warn: Boolean = false) {
    Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(DdfColors.Outline)) {
        Box(
            Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(4.dp).clip(RoundedCornerShape(2.dp))
                .background(if (warn) DdfColors.Warning else DdfColors.Cyan),
        )
    }
}

@Composable
fun ListRow(onClick: (() -> Unit)?, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().let { if (onClick != null) it.clickable(onClick = onClick) else it }.padding(vertical = 10.dp)) {
        content()
    }
    HorizontalDivider(color = DdfColors.Outline)
}

@Composable
fun AmountField(label: String, text: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    OutlinedTextField(
        value = text, onValueChange = onChange, label = { Text(label) }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = modifier.fillMaxWidth(),
        isError = text.isNotBlank() && Money.parse(text) == null,
    )
}

@Composable
fun TextFieldRow(label: String, text: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    OutlinedTextField(value = text, onValueChange = onChange, label = { Text(label) }, singleLine = true, modifier = modifier.fillMaxWidth())
}

/** A date typed as yyyy-mm-dd, with Today as a shortcut. */
@Composable
fun DateField(label: String, text: String, onChange: (String) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = text, onValueChange = onChange, label = { Text("$label (yyyy-mm-dd)") }, singleLine = true,
            modifier = Modifier.weight(1f), isError = text.isNotBlank() && parseDateOrNull(text) == null,
        )
        TextButton(onClick = { onChange(LocalDate.now().toString()) }) { Text("Today") }
    }
}

fun parseDateOrNull(s: String): LocalDate? = try { LocalDate.parse(s.trim()) } catch (e: Exception) { null }

/** A labeled dropdown of [options] (value to label). */
@Composable
fun <T> Picker(label: String, options: List<Pair<T, String>>, selected: T?, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    Column(modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = DdfColors.Muted)
        Box {
            OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
                Text(options.firstOrNull { it.first == selected }?.second ?: "Choose", modifier = Modifier.weight(1f))
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                options.forEach { (v, l) -> DropdownMenuItem(text = { Text(l) }, onClick = { open = false; onSelect(v) }) }
            }
        }
    }
}

@Composable
fun ErrorLine(text: String?) {
    if (text != null) Text(text, color = DdfColors.Error, modifier = Modifier.padding(vertical = 6.dp))
}

@Composable
fun Gap(h: Int = 8) = Spacer(Modifier.height(h.dp))

@Composable
fun HGap(w: Int = 8) = Spacer(Modifier.width(w.dp))

@Composable
fun IconAction(icon: ImageVector, description: String, onClick: () -> Unit) {
    IconButton(onClick = onClick) { Icon(icon, contentDescription = description) }
}

@Composable
fun Pill(text: String) {
    Text(
        text, style = MaterialTheme.typography.labelSmall, color = DdfColors.Muted,
        modifier = Modifier.padding(start = 6.dp).clip(RoundedCornerShape(6.dp)).background(DdfColors.SurfaceHigh).padding(horizontal = 6.dp, vertical = 1.dp),
    )
}

val Arrangement.spaced8 get() = spacedBy(8.dp)

/**
 * The screens' text actions (Add, Edit, Scan, Show more...): Material's TextButton on the phone; in the program
 * (0.14.1, LocalProgramStyle) a button in the program's style, a bordered ghost button, so no action is a bare link.
 */
@Composable
fun TextButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    if (!LocalProgramStyle.current) {
        androidx.compose.material3.TextButton(onClick = onClick, modifier = modifier, enabled = enabled, content = content)
        return
    }
    val shape = RoundedCornerShape(6.dp)
    Row(
        modifier.padding(2.dp).alpha(if (enabled) 1f else 0.4f).clip(shape).border(1.dp, DdfColors.OutlineHigh, shape)
            .clickable(enabled = enabled, role = androidx.compose.ui.semantics.Role.Button, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        androidx.compose.runtime.CompositionLocalProvider(
            androidx.compose.material3.LocalContentColor provides DdfColors.Muted,
            androidx.compose.material3.LocalTextStyle provides MaterialTheme.typography.labelLarge,
        ) { content() }
    }
}
