package net.darkrelay.deaddropfinance.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
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
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * The one confirm every screen uses (0.15.6): a question, what happens, Cancel and the action (Clear, Delete, Log out...).
 *
 * On the phone (1.9.6) it's the same dialog in Material's own window and shape: Cancel quiet on the left, the action filled on the right,
 * red when [destructive]. In the
 * program (LocalProgramStyle) it's the program's dialog ([ProgramDialog]): the text 13.5sp in the muted color, as wide
 * as its text up to [CONFIRM_MAX_WIDTH] (longer text wraps). Cancel is quiet (outlined) on the left; the action is filled
 * on the right: red when [destructive], teal otherwise. Esc cancels; focus starts on Cancel, and Enter presses the
 * button that has the focus (0.15.7), so Enter alone cancels. [extra] is anything more the confirm shows under its text
 * (a checkbox, a picker, a note).
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
    if (!programDialogs()) {
        // 1.9.6: the same confirm as the program's: the question, what happens, Cancel quiet on the left and the action
        // filled on the right (red when it throws work away). Material's dialog window and shape stay the phone's own.
        androidx.compose.ui.window.Dialog(onDismissRequest = onCancel) {
            androidx.compose.material3.Surface(
                modifier = Modifier.tagged(tag),
                shape = AlertDialogDefaults.shape, color = AlertDialogDefaults.containerColor, contentColor = AlertDialogDefaults.textContentColor, tonalElevation = AlertDialogDefaults.TonalElevation,
            ) {
                Column(Modifier.padding(24.dp)) {
                    if (title != null) {
                        Text(title, style = MaterialTheme.typography.headlineSmall, color = AlertDialogDefaults.titleContentColor, modifier = Modifier.testTag("confirm.title"))
                        Spacer(Modifier.height(16.dp))
                    }
                    Text(text, style = MaterialTheme.typography.bodyMedium, color = AlertDialogDefaults.textContentColor, modifier = Modifier.tagged(textTag ?: "confirm.text"))
                    if (extra != null) Column(Modifier.fillMaxWidth().padding(top = 8.dp), content = extra)
                    Row(Modifier.fillMaxWidth().padding(top = 24.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(onClick = onCancel, modifier = Modifier.tagged(cancelTag ?: "confirm.cancel")) { Text("Cancel") }
                        Button(
                            onClick = onConfirm, enabled = enabled, modifier = Modifier.tagged(actionTag ?: "confirm.action"),
                            colors = if (destructive) ButtonDefaults.buttonColors(containerColor = ConfirmRed, contentColor = ConfirmOnFill) else ButtonDefaults.buttonColors(),
                        ) { Text(action) }
                    }
                }
            }
        }
        return
    }
    val cancelFocus = remember { FocusRequester() }
    ProgramDialog(
        title, onCancel, tag ?: "confirm.card", width = null, initialFocus = cancelFocus, scrollBody = false,
        buttons = {
            DialogButton("Cancel", onCancel, Modifier.focusRequester(cancelFocus), tag = cancelTag ?: "confirm.cancel")
            DialogButton(action, onConfirm, filled = true, destructive = destructive, enabled = enabled, tag = actionTag ?: "confirm.action")
        },
    ) {
        Text(text, style = TextStyle(fontFamily = ProgramType.family, fontSize = 13.5.sp, lineHeight = 19.sp, color = ProgramPalette.muted), modifier = Modifier.fillMaxWidth().testTag(textTag ?: "confirm.text"))
        if (extra != null) {
            Column(Modifier.fillMaxWidth().padding(top = 2.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { extra() }
        }
    }
}

/** One button of a [FormDialog]: quiet (outlined) unless [filled]; filled red when [destructive]. */
data class DialogAction(
    val label: String,
    val onClick: () -> Unit,
    val filled: Boolean = false,
    val destructive: Boolean = false,
    val enabled: Boolean = true,
    val tag: String? = null,
)

/**
 * A dialog with fields or a list (0.15.7: Rename category, the currency picker, the recovery key): Material's
 * AlertDialog on the phone, as it always was (the filled action is its confirm button, the rest its dismiss buttons);
 * the program's dialog ([ProgramDialog]) at [width] in the program. [dismissible] false: only its buttons close it.
 */
@Composable
fun FormDialog(
    title: String,
    onDismiss: () -> Unit,
    actions: List<DialogAction>,
    tag: String? = null,
    width: Dp = 470.dp,
    dismissible: Boolean = true,
    body: @Composable ColumnScope.() -> Unit,
) {
    if (!programDialogs()) {
        val main = actions.lastOrNull { it.filled } ?: actions.last()
        val rest = actions - main
        @Composable fun phoneButton(a: DialogAction) =
            TextButton(onClick = a.onClick, enabled = a.enabled, modifier = Modifier.tagged(a.tag)) { if (a.destructive) Text(a.label, color = DdfColors.Error) else Text(a.label) }
        AlertDialog(
            onDismissRequest = { if (dismissible) onDismiss() },
            modifier = Modifier.tagged(tag),
            title = { Text(title) },
            text = { Column(content = body) },
            confirmButton = { phoneButton(main) },
            dismissButton = if (rest.isEmpty()) null else { { Row { rest.forEach { phoneButton(it) } } } },
        )
        return
    }
    ProgramDialog(
        title, if (dismissible) onDismiss else null, tag, width,
        buttons = { actions.forEach { DialogButton(it.label, it.onClick, filled = it.filled, destructive = it.destructive, enabled = it.enabled, tag = it.tag) } },
        body = body,
    )
}

/** The program draws its dialogs ([LocalProgramStyle], or the program's own window with its [ConfirmHost]). */
@Composable
private fun programDialogs() = LocalProgramStyle.current || LocalConfirmHost.current != null

/**
 * The program's dialog (0.15.6 confirms, 0.15.7 every dialog): a card like the program's, radius 10, #11161B with a 1px
 * #1F2A33 border, the card's padding, the title 16sp; the body under it and the buttons on the right at the bottom.
 * [width] fixes the card's width (a form); null fits it to its content between [CONFIRM_MIN_WIDTH] and
 * [CONFIRM_MAX_WIDTH] (a confirm). A scrim covers the whole window: it's drawn from the window's root ([ConfirmLayer]).
 * Esc and a click on the scrim call [onCancel] (null: neither does anything). Focus starts on [initialFocus], or on the
 * card itself (so Esc works) when there's none; Enter isn't the dialog's: a focused button presses, a field does what it
 * does. [scrollBody]: the body scrolls when the window is too short for it (a form).
 */
@Composable
fun ProgramDialog(
    title: String?,
    onCancel: (() -> Unit)?,
    tag: String?,
    width: Dp?,
    initialFocus: FocusRequester? = null,
    scrollBody: Boolean = true,
    buttons: @Composable RowScope.() -> Unit,
    body: @Composable ColumnScope.() -> Unit,
) {
    val card: @Composable () -> Unit = { ProgramDialogCard(title, onCancel, tag, width, initialFocus, scrollBody, buttons, body) }
    val host = LocalConfirmHost.current
    if (host == null) {
        // a program screen shown on its own (no window root to draw on): a full-size dialog of the same card
        Dialog(onDismissRequest = { onCancel?.invoke() }, properties = DialogProperties(usePlatformDefaultWidth = false)) { card() }
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

/** Where the program draws its dialogs: one per window, provided at its root, drawn on top by [ConfirmLayer]. */
class ConfirmHost {
    internal class Entry { var content: (@Composable () -> Unit)? by mutableStateOf(null) }
    internal val open = mutableStateListOf<Entry>()
    /** A dialog is showing. */
    val showing: Boolean get() = open.isNotEmpty()
}

/** The window's [ConfirmHost] (the program); null on the phone, where a dialog is an AlertDialog. */
val LocalConfirmHost = staticCompositionLocalOf<ConfirmHost?> { null }

/** The dialog showing, over everything in the window. The program puts this last in its window's root. */
@Composable
fun ConfirmLayer(host: ConfirmHost) {
    val top = host.open.lastOrNull() ?: return
    key(top) { top.content?.invoke() }
}

/** The program's confirm card is never wider than this: longer text wraps. */
val CONFIRM_MAX_WIDTH = 460.dp
/** ...and never narrower than this, so a short question isn't a sliver. */
val CONFIRM_MIN_WIDTH = 300.dp
/** The program's scrim: dark enough that nothing behind a dialog reads at full brightness. */
val ProgramScrim = Color(0xB8030609)

private fun Modifier.tagged(tag: String?) = if (tag != null) testTag(tag) else this

@Composable
private fun ProgramDialogCard(
    title: String?, onCancel: (() -> Unit)?, tag: String?, width: Dp?, initialFocus: FocusRequester?, scrollBody: Boolean,
    buttons: @Composable RowScope.() -> Unit, body: @Composable ColumnScope.() -> Unit,
) {
    val p = ProgramPalette
    val cardFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { (initialFocus ?: cardFocus).requestFocus() } }
    Box(
        Modifier.fillMaxSize().background(ProgramScrim)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onCancel?.invoke() }
            .onPreviewKeyEvent { e ->
                if (e.type == KeyEventType.KeyDown && e.key == Key.Escape) { onCancel?.invoke(); true } else false
            }
            .testTag("dialog.scrim"),
        contentAlignment = Alignment.Center,
    ) {
        val shape = RoundedCornerShape(10.dp)
        val size = if (width != null) Modifier.width(width) else Modifier.widthIn(min = CONFIRM_MIN_WIDTH, max = CONFIRM_MAX_WIDTH).width(IntrinsicSize.Max)
        Column(
            Modifier.padding(24.dp).then(size)
                .clip(shape).background(p.surface).border(1.dp, p.outline, shape)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                .focusRequester(cardFocus).focusable().tagged(tag)
                .padding(16.dp),
        ) {
            if (title != null) {
                Text(title, style = TextStyle(fontFamily = ProgramType.family, fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold, color = p.text), modifier = Modifier.testTag("confirm.title"))
                Spacer(Modifier.height(if (scrollBody) 14.dp else 8.dp))
            }
            if (scrollBody) {
                ScrollColumn(Modifier.fillMaxWidth().weight(1f, fill = false).heightIn(max = 640.dp), verticalArrangement = Arrangement.spacedBy(13.dp), content = body)
            } else {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp), content = body)
            }
            Row(Modifier.fillMaxWidth().padding(top = 20.dp), horizontalArrangement = Arrangement.spacedBy(9.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically, content = buttons)
        }
    }
}

/** The fill of the program's destructive action, and lighter under the mouse. */
val ConfirmRed = Color(0xFFE2574C)
private val ConfirmRedHover = Color(0xFFEA6E64)
private val ConfirmTealHover = Color(0xFF12899F)
private val ConfirmOnFill = Color(0xFFF4FAFC)
private val ConfirmQuietHover = Color(0xFF141C23)

/**
 * The program's dialog button: quiet (outlined, Cancel and the like) or [filled] (the action: teal, red when
 * [destructive]); radius 7, as the program's own buttons; a cyan border while it has the focus; Enter or Space press
 * it then. A disabled one has no click at all.
 */
@Composable
fun DialogButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    filled: Boolean = false,
    destructive: Boolean = false,
    enabled: Boolean = true,
    tag: String? = null,
) {
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
        modifier.tagged(tag).alpha(if (enabled) 1f else 0.4f).clip(shape).background(bg).border(1.dp, border, shape)
            .hoverable(src)
            // a disabled action has no click at all, as the program's own buttons
            .let { if (enabled) it.clickable(interactionSource = src, indication = null, role = Role.Button, onClick = onClick) else it }
            .padding(horizontal = 14.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) { Text(label, style = TextStyle(fontFamily = ProgramType.family, fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium, color = fg)) }
}
