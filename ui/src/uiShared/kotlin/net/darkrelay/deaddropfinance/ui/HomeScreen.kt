package net.darkrelay.deaddropfinance.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.darkrelay.deaddropfinance.UpdateCheck
import net.darkrelay.deaddropfinance.data.EnvelopeEntity
import net.darkrelay.deaddropfinance.data.EnvelopeMode
import java.time.LocalDate
import net.darkrelay.deaddropfinance.domain.Bills
import net.darkrelay.deaddropfinance.domain.Budget
import net.darkrelay.deaddropfinance.domain.Money

/**
 * Download and install's progress (0.15.1): downloading (a percent, or none while the size isn't known yet), needing
 * Android's "install unknown apps" permission first, or a final message once it's handed to the installer or failed.
 */
data class UpdateInstallState(
    val working: Boolean = false,
    val percent: Int? = null,
    val needsPermission: Boolean = false,
    val message: String? = null,
    val failed: Boolean = false,
)

/** Asked before Download and install on mobile data (0.15.2): "Download" goes on, "Cancel" stops. */
@Composable
fun MobileDataDialog(bytes: Long?, onDownload: () -> Unit, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        text = { Text(net.darkrelay.deaddropfinance.UpdateStart.message(bytes)) },
        confirmButton = { TextButton(onClick = onDownload) { Text("Download") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}

/** The update bar: never a popup. Download and install (0.15.1) fetches, verifies and installs the release itself. */
@Composable
fun UpdateBar(
    release: UpdateCheck.Release, install: UpdateInstallState, onNotes: () -> Unit, onDownload: () -> Unit,
    onAllowInstalls: () -> Unit, onSkip: () -> Unit, onLater: () -> Unit,
) {
    Card(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        Column(Modifier.padding(12.dp)) {
            Text("Version ${release.version} is available", style = MaterialTheme.typography.titleMedium)
            if (install.working) {
                if (install.percent != null) {
                    androidx.compose.material3.LinearProgressIndicator(progress = { install.percent / 100f }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                    Text("Downloading... ${install.percent}%", color = DdfColors.Muted, style = MaterialTheme.typography.bodySmall)
                } else {
                    androidx.compose.material3.LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
                    Text("Downloading...", color = DdfColors.Muted, style = MaterialTheme.typography.bodySmall)
                }
            }
            if (install.needsPermission) {
                Text("Dead Drop Finance needs permission to install updates.", color = DdfColors.Warning, modifier = Modifier.padding(top = 8.dp))
                TextButton(onClick = onAllowInstalls) { Text("Allow installs") }
            }
            if (install.message != null) {
                Text(install.message, color = if (install.failed) DdfColors.Error else DdfColors.Cyan, modifier = Modifier.padding(top = 8.dp))
            }
            Row(Modifier.fillMaxWidth()) {
                TextButton(onClick = onNotes) { Text("Release notes") }
                TextButton(onClick = onDownload, enabled = !install.working) { Text("Download and install") }
            }
            Row(Modifier.fillMaxWidth()) {
                TextButton(onClick = onSkip) { Text("Skip this version") }
                TextButton(onClick = onLater) { Text("Later") }
            }
        }
    }
}

/** The parts of Home, top to bottom, in the reference prototype's order. Rollover is never one of them. */
enum class HomeSection { SAFE_TO_SPEND, FORECAST, READY_TO_ASSIGN, OVER_ASSIGNED, BONUS, PAY_THESE_NOW, TO_SORT, ENVELOPES }

/**
 * Which parts Home shows, in order: Safe to Spend always first; the forecast when there's a next payday to forecast
 * to; Ready to Assign only when money is waiting, or the over-assigned warning in its place when it's below zero; the
 * payday checklist when it has bills; To sort when something is waiting; then Home's envelopes with All envelopes.
 */
fun homeSections(b: Budget, hasForecast: Boolean, hasChecklist: Boolean): List<HomeSection> = buildList {
    add(HomeSection.SAFE_TO_SPEND)
    if (hasForecast) add(HomeSection.FORECAST)
    if (b.readyToAssign > 0) add(HomeSection.READY_TO_ASSIGN)
    if (b.readyToAssign < 0) add(HomeSection.OVER_ASSIGNED)
    add(HomeSection.BONUS)
    if (hasChecklist) add(HomeSection.PAY_THESE_NOW)
    if (b.toSort.isNotEmpty()) add(HomeSection.TO_SORT)
    add(HomeSection.ENVELOPES)
}

/**
 * The envelopes Home lists, in fill order: the everyday ones, plus any debt envelope with a payment due before the
 * next payday (none of those without a next payday to go by). Never Rollover.
 */
fun homeEnvelopes(b: Budget, today: LocalDate, nextPayday: LocalDate?): List<EnvelopeEntity> = b.fillOrder.filter { e ->
    e.mode != EnvelopeMode.ROLLOVER && (
        e.everyday ||
            (e.mode == EnvelopeMode.DEBT && nextPayday != null && Bills.nextDebtDue(b, e, today)?.isBefore(nextPayday) == true)
        )
}

/** "Groceries", "Groceries and Gas", "Groceries, Gas and Household". */
fun namesText(names: List<String>): String = when (names.size) {
    0 -> ""
    1 -> names[0]
    else -> names.dropLast(1).joinToString(", ") + " and " + names.last()
}

/**
 * Home (Stage 11, built from the reference prototype): Safe to Spend is the biggest thing on the screen, then the
 * balance forecast, Ready to Assign (only with money waiting; a warning row when over-assigned), Pay these now, To
 * sort, and the everyday envelopes with any debt payment due before payday. Rollover isn't shown here. [top] holds the setup card, the alerts offer and the update bar.
 */
@Composable
fun HomeScreen(
    budget: Budget,
    top: (@Composable () -> Unit)?,
    onAssign: () -> Unit,
    onToSort: () -> Unit,
    onEnvelope: (String) -> Unit,
    onAllEnvelopes: () -> Unit,
    onSafeToSpend: () -> Unit,
    /** Over-assigned: moves money back to Ready to Assign. */
    onFixOverAssigned: () -> Unit,
    /** The late payday note and the balance forecast card. */
    forecast: (@Composable () -> Unit)? = null,
    /** The payday checklist (Pay these now). */
    checklist: (@Composable () -> Unit)? = null,
    /** The envelopes to list (homeEnvelopes); the everyday ones when not given. */
    envelopes: List<EnvelopeEntity> = budget.everydayEnvelopes,
    /** Bonus income: money outside the bank, added to Ready to Assign. */
    onBonus: () -> Unit = {},
    /** 1.9.1: the reports picked for Home and Customize (HomeReportCards), under everything else; null shows none. */
    dashboard: (@Composable () -> Unit)? = null,
) {
    val sections = homeSections(budget, forecast != null, checklist != null)
    LazyColumn(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        if (top != null) item { top() }
        for (s in sections) when (s) {
            HomeSection.SAFE_TO_SPEND -> item { SafeToSpendCard(budget, onSafeToSpend) }
            HomeSection.FORECAST -> item { forecast?.invoke() }
            HomeSection.READY_TO_ASSIGN -> item {
                Row(
                    Modifier.fillMaxWidth().padding(bottom = 8.dp).clip(RoundedCornerShape(10.dp)).background(DdfColors.Teal).padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Ready to assign", style = MaterialTheme.typography.labelMedium, color = DdfColors.Text)
                        Text(Money.format(budget.readyToAssign), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Medium, color = DdfColors.Text)
                    }
                    Button(onClick = onAssign) { Text("Assign") }
                }
            }
            HomeSection.BONUS -> item { TextButton(onClick = onBonus) { Text("Bonus income", color = DdfColors.Cyan) } }
            HomeSection.OVER_ASSIGNED -> item {
                Row(
                    Modifier.fillMaxWidth().padding(bottom = 8.dp).clip(RoundedCornerShape(10.dp)).border(1.dp, DdfColors.Warning, RoundedCornerShape(10.dp))
                        .clickable(onClick = onFixOverAssigned).padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Over-assigned by ${Money.format(-budget.readyToAssign)}", color = DdfColors.Warning, fontWeight = FontWeight.Medium)
                        Text("More is in envelopes than you have. ${LocalDeviceWords.current.tap} to move some back.", color = DdfColors.Muted, style = MaterialTheme.typography.bodySmall)
                    }
                    Icon(TabIcons.Chevron, contentDescription = null, tint = DdfColors.Warning, modifier = Modifier.size(18.dp))
                }
            }
            HomeSection.PAY_THESE_NOW -> item { checklist?.invoke() }
            HomeSection.TO_SORT -> item {
                val n = budget.toSort.size
                HomeLinkCard(TabIcons.Inbox, "$n transaction${if (n == 1) "" else "s"} to sort", null, onToSort)
            }
            HomeSection.ENVELOPES -> {
                items(envelopes, key = { it.id }) { e -> EnvelopeRow(e, budget, onClick = { onEnvelope(e.id) }) }
                item { TextButton(onClick = onAllEnvelopes) { Text("All envelopes", color = DdfColors.Cyan) } }
            }
        }
        dashboard?.let { d -> item { d() } }
        item { Gap(24) }
    }
}

/** Safe to Spend: what's left in the everyday envelopes until next payday, and which envelopes those are. */
@Composable
fun SafeToSpendCard(budget: Budget, onChange: () -> Unit) {
    val counted = budget.everydayEnvelopes
    val safe = budget.safeToSpend
    Card(
        Modifier.fillMaxWidth().padding(bottom = 8.dp).clickable(onClick = onChange),
        border = BorderStroke(1.dp, DdfColors.Cyan),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text("Safe to spend", color = DdfColors.Muted, style = MaterialTheme.typography.bodySmall)
            if (safe == null) {
                Text("Pick the envelopes you spend from day to day, like groceries and gas.", Modifier.padding(vertical = 4.dp), color = DdfColors.Text)
                TextButton(onClick = onChange) { Text("Choose envelopes") }
            } else {
                Text(
                    Money.format(safe), fontSize = 32.sp, fontWeight = FontWeight.Medium, lineHeight = 38.sp,
                    color = if (safe < 0) DdfColors.Warning else DdfColors.Cyan,
                )
                Text("in ${namesText(counted.map { it.name })} until next payday", color = DdfColors.Muted, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/** A Home card that opens a screen: an icon, a title, a quiet detail, and a chevron. */
@Composable
private fun HomeLinkCard(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, detail: String?, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(bottom = 8.dp).clickable(onClick = onClick)) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = DdfColors.Cyan, modifier = Modifier.size(18.dp))
            HGap()
            Text(title)
            if (detail != null) { HGap(6); Text(detail, color = DdfColors.Muted, style = MaterialTheme.typography.bodySmall) }
            Spacer(Modifier.weight(1f))
            Icon(TabIcons.Chevron, contentDescription = null, tint = DdfColors.Muted, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
fun EnvelopeRow(e: EnvelopeEntity, budget: Budget, onClick: () -> Unit, handle: (@Composable () -> Unit)? = null) {
    val bal = budget.envelopeBalance(e.id)
    val goal = when (e.mode) {
        EnvelopeMode.FILL_TO_TARGET -> e.target
        EnvelopeMode.GOAL -> e.goalAmount
        else -> null
    }
    ListRow(onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            handle?.invoke()
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                Text(e.name)
                modeTag(e)?.let { Pill(it) }
            }
            MoneyText(bal)
            if (goal != null) Text(" / ${Money.format(goal)}", color = DdfColors.Muted)
        }
        if (goal != null && goal > 0) {
            Gap(4)
            Progress(if (bal <= 0) 0f else (bal.toFloat() / goal.toFloat()), warn = bal < 0)
        } else if (bal < 0) {
            Gap(4)
            Progress(1f, warn = true)
        }
    }
}

fun modeTag(e: EnvelopeEntity): String? = when (e.mode) {
    EnvelopeMode.FILL_TO_TARGET -> null
    EnvelopeMode.ADD_EVERY_PAYDAY -> "every payday"
    EnvelopeMode.GOAL -> "goal"
    EnvelopeMode.SEASONAL -> "seasonal"
    EnvelopeMode.DEBT -> "debt"
    EnvelopeMode.CASH -> "cash"
    EnvelopeMode.ROLLOVER -> null
}

fun modeName(m: EnvelopeMode): String = when (m) {
    EnvelopeMode.FILL_TO_TARGET -> "Fill to target"
    EnvelopeMode.ADD_EVERY_PAYDAY -> "Add every payday"
    EnvelopeMode.GOAL -> "Goal"
    EnvelopeMode.SEASONAL -> "Seasonal"
    EnvelopeMode.DEBT -> "Debt"
    EnvelopeMode.CASH -> "Cash"
    EnvelopeMode.ROLLOVER -> "Rollover"
}
