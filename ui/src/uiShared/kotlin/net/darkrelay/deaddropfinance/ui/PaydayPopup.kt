package net.darkrelay.deaddropfinance.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import kotlinx.coroutines.launch
import net.darkrelay.deaddropfinance.domain.Budget
import net.darkrelay.deaddropfinance.domain.BudgetActions
import net.darkrelay.deaddropfinance.domain.Money
import net.darkrelay.deaddropfinance.domain.PaydayButton

/** The "?" beside Clean up. */
const val CLEAN_UP_HELP = "Zeroes every envelope first: leftovers go back to Unplanned, overspent envelopes are covered from Unplanned."

/** What the confirm lists before anything is saved (1.9.9). */
fun paydayConfirmText(b: Budget, accountId: String, p: PaydayButton.Preview): String {
    val name = b.accounts.firstOrNull { it.id == accountId }?.name ?: "the account"
    val names = b.envelopes.associate { it.id to it.name }
    val out = mutableListOf<String>()
    val existing = p.check.existing
    out += if (existing != null) "The deposit of ${Money.format(existing.amount)} on ${dayText(existing.date)} in $name is marked as payday."
    else "A deposit of ${Money.format(p.check.difference)} is added to $name today, payee Payday, and marked as payday."
    p.cleanup?.let { c ->
        val back = c.moves.filter { it.toEnvelopeId == b.unplannedEnvelope?.id }
        val covered = c.moves.filter { it.fromEnvelopeId == b.unplannedEnvelope?.id }
        out += if (c.moves.isEmpty()) "Clean up: every envelope is at zero already." else
            "Clean up: ${back.size} leftover${if (back.size == 1) "" else "s"} back to Unplanned (${Money.format(back.sumOf { it.amount })}), " +
                "${covered.size} overspent covered from Unplanned (${Money.format(covered.sumOf { it.amount })})."
        if (c.uncovered > 0) out += "Unplanned can't cover ${Money.format(c.uncovered)} of the overspending; that stays negative."
    }
    val fills = p.fill.fills
    out += if (fills.isEmpty()) "Fill: nothing to fill." else "Fill: ${fills.size} envelope${if (fills.size == 1) "" else "s"}, ${Money.format(fills.sumOf { it.amount })}" +
        (if (p.fill.shortfalls.isNotEmpty()) "; short ${p.fill.shortfalls.joinToString { "${names[it.envelopeId] ?: "an envelope"} ${Money.format(it.short)}" }}" else "") + "."
    return out.joinToString("\n")
}

/** What happened, said once it's done. */
fun paydayResultText(r: BudgetActions.PaydayResult): String = buildString {
    append(r.added?.let { "Added the ${Money.format(it)} payday deposit" } ?: "Marked the deposit as payday")
    if (r.cleanupMoves > 0) append(", cleaned up ${r.cleanupMoves} envelope${if (r.cleanupMoves == 1) "" else "s"}")
    append(", filled ${r.plan.fills.size} envelope${if (r.plan.fills.size == 1) "" else "s"} (${Money.format(r.plan.fills.sumOf { it.amount })}).")
    if (r.uncovered > 0) append(" Unplanned couldn't cover ${Money.format(r.uncovered)}.")
}

/**
 * The Payday popup (1.9.9, program 0.15.12): the account (where the last payday went), its balance now after payday
 * from the bank, what the app shows and the difference, and Clean up (off by default, its "?" says what it does). Payday
 * asks once (the shared confirm) with everything it will do; only then is anything saved ([onRun], which returns what
 * to say when it's done). The program's dialog frame in the program, the phone's shared dialog look on the phone.
 */
@Composable
fun PaydayPopup(budget: Budget, today: LocalDate, onClose: () -> Unit, onRun: suspend (String, Long, Boolean) -> String) {
    val accounts = budget.accounts.filter { !it.archived }
    var account by remember { mutableStateOf(PaydayButton.defaultAccount(budget)) }
    var text by remember { mutableStateOf("") }
    var cleanUp by remember { mutableStateOf(false) }
    var help by remember { mutableStateOf(false) }
    var confirming by remember { mutableStateOf(false) }
    var done by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val acct = account
    val bank = text.trim().takeIf { it.isNotEmpty() }?.let { Money.parse(it) }
    val check = if (acct != null && bank != null) PaydayButton.check(budget, acct, bank) else null
    val preview = if (acct != null && bank != null && check?.problem == null) PaydayButton.preview(budget, acct, bank, cleanUp, today) else null

    done?.let { msg ->
        FormDialog("Payday", onClose, listOf(DialogAction("OK", onClose, filled = true, tag = "payday.ok")), tag = "payday.done") {
            Text(msg, modifier = Modifier.testTag("payday.result"))
        }
        return
    }
    if (confirming && preview != null && acct != null && bank != null) {
        ConfirmDialog(
            "Payday?", paydayConfirmText(budget, acct, preview), "Payday",
            onCancel = { confirming = false },
            onConfirm = {
                confirming = false
                scope.launch { runCatching { onRun(acct, bank, cleanUp) }.onSuccess { done = it }.onFailure { error = it.message ?: "That couldn't be done." } }
            },
            tag = "payday.confirm", cancelTag = "payday.confirmCancel", actionTag = "payday.confirmGo", textTag = "payday.confirmText",
        )
        return
    }
    val fields: @Composable ColumnScope.() -> Unit = {
        Column {
            Picker("Account", accounts.map { it.id to it.name }, account, { account = it }, Modifier.testTag("payday.account"))
            TextFieldRow("Balance now, after payday", text, { text = it }, Modifier.testTag("payday.balance"))
            if (acct != null) {
                val app = PaydayButton.appBalance(budget, acct)
                Text(
                    if (bank == null) "App shows ${Money.format(app)}." else "App shows ${Money.format(app)}. Difference ${Money.format(bank - app)}.",
                    modifier = Modifier.testTag("payday.diff"), style = MaterialTheme.typography.bodyMedium,
                )
            }
            check?.problem?.let { Text(it, color = DdfColors.Warning, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp).testTag("payday.problem")) }
            error?.let { Text(it, color = DdfColors.Error, style = MaterialTheme.typography.bodySmall) }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                Checkbox(cleanUp, { cleanUp = it }, modifier = Modifier.testTag("payday.cleanUp"))
                Text("Clean up")
                val hover = remember { MutableInteractionSource() }
                val hovered by hover.collectIsHoveredAsState()
                Text(
                    "?", color = DdfColors.Cyan,
                    modifier = Modifier.padding(start = 8.dp).hoverable(hover).clickable { help = !help }.padding(horizontal = 6.dp).testTag("payday.help"),
                )
                androidx.compose.runtime.LaunchedEffect(hovered) { if (hovered) help = true }
            }
            if (help) Text(CLEAN_UP_HELP, color = DdfColors.Muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("payday.helpText"))
        }
    }
    val ready = preview != null
    if (LocalProgramStyle.current || LocalConfirmHost.current != null) {
        FormDialog(
            "Payday", onClose,
            listOf(DialogAction("Cancel", onClose, tag = "payday.cancel"), DialogAction("Payday", { confirming = true }, filled = true, enabled = ready, tag = "payday.go")),
            tag = "payday.dialog",
        ) { fields() }
    } else {
        ConfirmDialog(
            "Payday", "Enter the account's balance from your bank, now that you've been paid: posted, without pending.", "Payday",
            onCancel = onClose, onConfirm = { confirming = true }, enabled = ready,
            tag = "payday.dialog", cancelTag = "payday.cancel", actionTag = "payday.go", extra = fields,
        )
    }
}
