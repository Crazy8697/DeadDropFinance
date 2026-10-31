package net.darkrelay.deaddropfinance.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.currentCompositionLocalContext
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * The one confirm every screen uses (0.15.6): a question, what happens, Cancel and the action (Clear, Delete, Log out...).
 *
 * On the phone it's Material's AlertDialog, Cancel left of the action, an action that throws work away in red. In the
 * program (LocalProgramStyle) it's a card like the program's: radius 10, #11161B with a 1px #1F2A33 border, the title
 * 16sp, the text 13.5sp in the muted color, as wide as its text up to [CONFIRM_MAX_WIDTH] (longer text wraps), with the
 * card's padding. Cancel is quiet (outlined) on the left; the action is filled on the right: red when [destructive],
 * teal otherwise. A scrim covers the whole window ([ConfirmLayer], at the window's root); Esc cancels, Enter acts, and
 * focus starts on Cancel. [extra] is anything more the confirm shows under its text (a checkbox, a picker, a note).
 */
@Composable
fun ConfirmDialog(
    title: String?,
    text: String,
    action: String,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
    destructive: Boolean = false,
    enabled: Boolean = true,
    tag: String? = null,
    cancelTag: String? = null,
    actionTag: String? = null,
    textTag: String? = null,
    extra: (@Composable ColumnScope.() -> Unit)? = null,
) {
    if (!LocalProgramStyle.current && LocalConfirmHost.current == null) {
        AlertDialog(
            onDismissRequest = onCancel,
            modifier = Modifier.tagged(tag),
            title = title?.let { t -> { Text(t) } },
            text = { Column { Text(text, modifier = Modifier.tagged(textTag)); extra?.invoke(this) } },
            confirmButton = {
                TextButton(onClick = onConfirm, enabled = enabled, modifier = Modifier.tagged(actionTag)) {
                    if (destructive) Text(action, color = DdfColors.Error) else Text(action)
                }
            },
            dismissButton = { TextButton(onClick = onCancel, modifier = Modifier.tagged(cancelTag)) { Text("Cancel") } },
        )
        return
    }
    val card: @Composable () -> Unit = {
        ProgramConfirm(title, text, action, onCancel, onConfirm, destructive, enabled, tag, cancelTag, actionTag, textTag, extra)
    }
    val host = LocalConfirmHost.current
    if (host == null) {
        // a program screen shown on its own (no window root to draw on): a full-size dialog of the same card
        Dialog(onDismissRequest = onCancel, properties = DialogProperties(usePlatformDefaultWidth = false)) { card() }
        return
    }
    // drawn at the window's root, so the scrim covers the menu strip, the sidebar and the top bar too; with this
    // screen's theme and settings (its composition locals), as if it were drawn here
    val locals = currentCompositionLocalContext
    val entry = remember { ConfirmHost.Entry() }
    SideEffect { entry.content = { CompositionLocalProvider(locals) { card() } } }
    DisposableEffect(host, entry) {
        host.open += entry
        onDispose { host.open -= entry }
    }
}

/** Where the program draws the confirms: one per window, provided at its root, drawn on top by [ConfirmLayer]. */
class ConfirmHost {
    internal class Entry { var content: (@Composable () -> Unit)? by mutableStateOf(null) }
    internal val open = mutableStateListOf<Entry>()
    /** A confirm is showing. */
    val showing: Boolean get() = open.isNotEmpty()
}

/** The window's [ConfirmHost] (the program); null on the phone, where a confirm is an AlertDialog. */
val LocalConfirmHost = staticCompositionLocalOf<ConfirmHost?> { null }

/** The confirm showing, over everything in the window. The program puts this last in its window's root. */
@Composable
fun ConfirmLayer(host: ConfirmHost) {
    val top = host.open.lastOrNull() ?: return
    key(top) { top.content?.invoke() }
}

/** The program's confirm card is never wider than this: longer text wraps. */
val CONFIRM_MAX_WIDTH = 460.dp
/** ...and never narrower than this, so a short question isn't a sliver. */
val CONFIRM_MIN_WIDTH = 300.dp
/** The program's scrim: dark enough that nothing behind a confirm reads at full brightness. */
val ProgramScrim = Color(0xB8030609)

private fun Modifier.tagged(tag: String?) = if (tag != null) testTag(tag) else this

@Composable
private fun ProgramConfirm(
    title: String?, text: String, action: String, onCancel: () -> Unit, onConfirm: () -> Unit,
    destructive: Boolean, enabled: Boolean, tag: String?, cancelTag: String?, actionTag: String?, textTag: String?,
    extra: (@Composable ColumnScope.() -> Unit)?,
) {
    val p = ProgramPalette
    val cancelFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { cancelFocus.requestFocus() } }
    Box(
        Modifier.fillMaxSize().background(ProgramScrim)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onCancel)
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (e.key) {
                    Key.Escape -> { onCancel(); true }
                    Key.Enter, Key.NumPadEnter -> { if (enabled) onConfirm(); true }
                    else -> false
                }
            }
            .testTag("confirm.scrim"),
        contentAlignment = Alignment.Center,
    ) {
        val shape = RoundedCornerShape(10.dp)
        Column(
            Modifier.padding(24.dp).widthIn(min = CONFIRM_MIN_WIDTH, max = CONFIRM_MAX_WIDTH).width(IntrinsicSize.Max)
                .clip(shape).background(p.surface).border(1.dp, p.outline, shape)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                .padding(16.dp).tagged(tag ?: "confirm.card"),
        ) {
            val font = ProgramType.family
            if (title != null) {
                Text(title, style = TextStyle(fontFamily = font, fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold, color = p.text), modifier = Modifier.testTag("confirm.title"))
                Spacer(Modifier.height(8.dp))
            }
            Text(text, style = TextStyle(fontFamily = font, fontSize = 13.5.sp, lineHeight = 19.sp, color = p.muted), modifier = Modifier.fillMaxWidth().testTag(textTag ?: "confirm.text"))
            if (extra != null) {
                Column(Modifier.fillMaxWidth().padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { extra() }
            }
            Row(Modifier.fillMaxWidth().padding(top = 20.dp), horizontalArrangement = Arrangement.spacedBy(9.dp, Alignment.End)) {
                ConfirmButton("Cancel", onCancel, filled = false, destructive = false, enabled = true, Modifier.focusRequester(cancelFocus).testTag(cancelTag ?: "confirm.cancel"))
                ConfirmButton(action, onConfirm, filled = true, destructive = destructive, enabled = enabled, Modifier.testTag(actionTag ?: "confirm.action"))
            }
        }
    }
}

/** The fill of the program's destructive action, and lighter under the mouse. */
val ConfirmRed = Color(0xFFE2574C)
private val ConfirmRedHover = Color(0xFFEA6E64)
private val ConfirmTealHover = Color(0xFF12899F)
private val ConfirmOnFill = Color(0xFFF4FAFC)
private val ConfirmQuietHover = Color(0xFF141C23)

/** The program's button: filled (the action) or quiet, outlined (Cancel); radius 7, as the program's own buttons. */
@Composable
private fun ConfirmButton(label: String, onClick: () -> Unit, filled: Boolean, destructive: Boolean, enabled: Boolean, modifier: Modifier) {
    val p = ProgramPalette
    val src = remember { MutableInteractionSource() }
    val hover by src.collectIsHoveredAsState()
    val focused by src.collectIsFocusedAsState()
    val lit = hover && enabled
    val shape = RoundedCornerShape(7.dp)
    val bg = when {
        !filled -> if (lit) ConfirmQuietHover else Color.Transparent
        destructive -> if (lit) ConfirmRedHover else ConfirmRed
        else -> if (lit) ConfirmTealHover else p.teal
    }
    val border = when {
        focused -> p.cyan
        !filled -> if (lit) p.teal else p.outlineHigh
        else -> bg
    }
    val fg = if (filled) ConfirmOnFill else if (lit || focused) p.text else p.muted
    Box(
        modifier.alpha(if (enabled) 1f else 0.4f).clip(shape).background(bg).border(1.dp, border, shape)
            .hoverable(src)
            // a disabled action has no click at all, as the program's own buttons
            .let { if (enabled) it.clickable(interactionSource = src, indication = null, role = Role.Button, onClick = onClick) else it }
            .padding(horizontal = 14.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) { Text(label, style = TextStyle(fontFamily = ProgramType.family, fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium, color = fg)) }
}
