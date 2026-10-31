package net.darkrelay.deaddropfinance.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import net.darkrelay.deaddropfinance.data.TransactionEntity
import net.darkrelay.deaddropfinance.domain.Bonus
import net.darkrelay.deaddropfinance.domain.Money

/** What the Bonus income form hands back: all four fields, the amount in cents. */
data class BonusForm(val amount: Long, val date: LocalDate, val receivedAs: String, val note: String)

/**
 * Bonus income: money that doesn't come through the bank. Amount, date (today unless changed), how it was received
 * (only a label), and a note. It goes to Ready to Assign and belongs to no account. [existing] edits one (and shows Delete).
 */
@Composable
fun BonusIncomeScreen(
    existing: TransactionEntity?,
    error: String?,
    onBack: () -> Unit,
    onSave: (BonusForm) -> Unit,
    onDelete: (() -> Unit)? = null,
    today: LocalDate = LocalDate.now(),
) {
    var amount by remember { mutableStateOf(existing?.amount?.let { Money.plain(it) } ?: "") }
    var date by remember { mutableStateOf((existing?.date ?: today).toString()) }
    var receivedAs by remember { mutableStateOf(existing?.payee?.takeIf { it in Bonus.LABELS } ?: Bonus.LABELS.first()) }
    var note by remember { mutableStateOf(existing?.memo ?: "") }
    var confirmDelete by remember { mutableStateOf(false) }
    ScrollColumn(Modifier.fillMaxWidth(), rememberScrollState(), Modifier.padding(horizontal = 16.dp)) {
        ScreenTitle(if (existing == null) "Bonus income" else "Edit bonus income", onBack)
        Text(
            "Money that didn't come through the bank. It goes to Ready to Assign and isn't tied to any account. It isn't a payday.",
            color = DdfColors.Muted,
        )
        Gap()
        AmountField("Amount", amount, { amount = it })
        DateField("Date", date, { date = it })
        Picker("Received as", Bonus.LABELS.map { it to it }, receivedAs, { receivedAs = it })
        TextFieldRow("Note (where it came from)", note, { note = it })
        ErrorLine(error)
        Gap()
        Button(
            onClick = { onSave(BonusForm(Money.parse(amount) ?: 0, parseDateOrNull(date) ?: today, receivedAs, note)) },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Save") }
        if (existing != null && onDelete != null) {
            Gap(16)
            OutlinedButton(onClick = { confirmDelete = true }) { Text("Delete", color = DdfColors.Error) }
        }
        Gap(24)
    }
    if (confirmDelete && onDelete != null) {
        ConfirmDialog(
            "Delete this bonus income?", "The amount comes back off Ready to Assign.", "Delete",
            onCancel = { confirmDelete = false }, onConfirm = { confirmDelete = false; onDelete() }, destructive = true,
        )
    }
}
