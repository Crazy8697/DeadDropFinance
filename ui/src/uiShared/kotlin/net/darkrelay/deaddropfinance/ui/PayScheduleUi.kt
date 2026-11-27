package net.darkrelay.deaddropfinance.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import net.darkrelay.deaddropfinance.domain.PaySchedule
import net.darkrelay.deaddropfinance.domain.Paydays

/**
 * What the Payday schedule popup holds while it's open (1.9.8, program 0.15.11): how often, and for monthly the day of
 * the month, the days early and the weekend or holiday choice; for every two weeks the next payday. The phone's popup
 * and the program's both use it.
 */
class PayScheduleForm(initial: PaySchedule?, suggested: LocalDate?, today: LocalDate) {
    var kind by mutableStateOf(initial?.kind ?: PaySchedule.Kind.MONTHLY)
    var day by mutableStateOf(initial?.dayOfMonth ?: suggested?.dayOfMonth ?: 1)
    var early by mutableStateOf(initial?.daysEarly ?: 0)
    var later by mutableStateOf(initial?.moveLater ?: false)
    var next by mutableStateOf((initial?.anchor ?: suggested ?: today).toString())

    /** The schedule entered, or null while the next payday (every two weeks) isn't a date. */
    fun schedule(): PaySchedule? = when (kind) {
        PaySchedule.Kind.MONTHLY -> PaySchedule(PaySchedule.Kind.MONTHLY, dayOfMonth = day, daysEarly = early, moveLater = later)
        PaySchedule.Kind.BIWEEKLY -> parseDateOrNull(next)?.let { PaySchedule(PaySchedule.Kind.BIWEEKLY, moveLater = later, anchor = it) }
    }
}

/** The choices the popup offers. */
object PayScheduleChoices {
    val often = listOf(PaySchedule.Kind.MONTHLY to "Monthly", PaySchedule.Kind.BIWEEKLY to "Every two weeks")
    val days = (1..31).map { it to "$it" }
    val early = (0..PaySchedule.MAX_DAYS_EARLY).map { it to "$it" }
    val move = listOf(false to "Move earlier", true to "Move later")
    const val EARLY_HINT = "If your bank pays before the official date"
    const val MOVE_LABEL = "If it lands on a weekend or holiday"
}

private val short = DateTimeFormatter.ofPattern("MMM d", Locale.US)

/** "Next three paydays: Nov 27, Dec 30, Jan 29", from what's entered (empty while it isn't a schedule yet). */
fun nextThreePaydays(s: PaySchedule?, today: LocalDate): String =
    s?.dates(today, 3)?.joinToString(", ", prefix = "Next three paydays: ") { it.format(short) } ?: "Enter the next payday as 2030-01-31."

/** The line under the next payday, the phone's and the program's: the schedule, or what the date came from. */
fun paydayWhyText(p: Paydays.Prediction): String = when {
    p.schedule != null -> p.schedule!!.describe()
    p.next == null -> "Payday is the deposit landing, never a date on the calendar."
    p.corrected -> "Set by you"
    p.guessed -> "No pay schedule yet. Set one with Change payday."
    p.monthlyDay != null -> "Predicted: monthly, around the ${PaySchedule.ordinal(p.monthlyDay!!)}"
    else -> "Predicted: every ${p.intervalDays} days"
}

/**
 * The Payday schedule popup on the phone (1.9.8): the phone's shared dialog look (Cancel quiet on the left, Save filled
 * on the right); in the program's frame when the app's screen is shown in the program.
 */
@Composable
fun PayScheduleDialog(initial: PaySchedule?, suggested: LocalDate?, today: LocalDate, onCancel: () -> Unit, onSave: (PaySchedule) -> Unit) {
    val form = remember { PayScheduleForm(initial, suggested, today) }
    val entered = form.schedule()
    val fields: @Composable ColumnScope.() -> Unit = {
        Column {
            Picker("How often", PayScheduleChoices.often, form.kind, { form.kind = it }, Modifier.testTag("payday.often"))
            if (form.kind == PaySchedule.Kind.MONTHLY) {
                Picker("Day of the month", PayScheduleChoices.days, form.day, { form.day = it }, Modifier.testTag("payday.day"))
                Picker("Days early", PayScheduleChoices.early, form.early, { form.early = it }, Modifier.testTag("payday.early"))
                Text(PayScheduleChoices.EARLY_HINT, color = DdfColors.Muted, style = MaterialTheme.typography.bodySmall)
            } else {
                DateField("Next payday", form.next, { form.next = it }, modifier = Modifier.testTag("payday.next"))
            }
            Picker(PayScheduleChoices.MOVE_LABEL, PayScheduleChoices.move, form.later, { form.later = it }, Modifier.testTag("payday.move"))
            Text(nextThreePaydays(entered, today), modifier = Modifier.padding(top = 8.dp).testTag("payday.nextThree"))
        }
    }
    if (LocalProgramStyle.current || LocalConfirmHost.current != null) {
        FormDialog(
            "Payday schedule", onCancel,
            listOf(
                DialogAction("Cancel", onCancel, tag = "payday.cancel"),
                DialogAction("Save", { entered?.let(onSave) }, filled = true, enabled = entered != null, tag = "payday.save"),
            ),
            tag = "payday.dialog",
        ) { fields() }
    } else {
        ConfirmDialog(
            "Payday schedule", "When you're paid. The next payday, the forecast and the payday checklist follow it.", "Save",
            onCancel = onCancel, onConfirm = { entered?.let(onSave) }, enabled = entered != null,
            tag = "payday.dialog", cancelTag = "payday.cancel", actionTag = "payday.save", extra = fields,
        )
    }
}
