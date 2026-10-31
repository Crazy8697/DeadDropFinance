package net.darkrelay.deaddropfinance.ui

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import net.darkrelay.deaddropfinance.domain.Reports.Kind

/**
 * The phone's Home dashboard (1.9.1): the reports the user picked, as cards on Home in their order, each the report's
 * own tile (the Reports grid's) opening its report. Kept on this phone only ([AppSettings.homeReports]), never synced
 * or carried in a backup. None until the user adds some: Home is as it was.
 */
object HomeDashboard {
    /** The saved list as reports, in order; names no longer known and repeats are dropped. */
    fun read(saved: List<String>): List<Kind> = saved.mapNotNull { n -> Kind.entries.firstOrNull { it.name == n } }.distinct()

    fun write(cards: List<Kind>): List<String> = cards.map { it.name }

    fun add(cards: List<Kind>, k: Kind): List<Kind> = if (k in cards) cards else cards + k

    fun remove(cards: List<Kind>, k: Kind): List<Kind> = cards - k

    /** [k] moved to [to] (clamped to the list). */
    fun move(cards: List<Kind>, k: Kind, to: Int): List<Kind> {
        val i = cards.indexOf(k)
        if (i < 0) return cards
        return cards.toMutableList().apply { removeAt(i); add(to.coerceIn(0, size), k) }
    }
}

/** The picked reports' cards on Home, then Customize. */
@Composable
fun HomeReportCards(cards: List<Kind>, data: ReportData, monthIndex: Int, onOpen: (Kind) -> Unit, onCustomize: () -> Unit) {
    Column(Modifier.fillMaxWidth().testTag("home.reports")) {
        if (cards.isNotEmpty()) SectionTitle("Reports")
        cards.forEach { k -> ReportTile(k, data, data.month(monthIndex), Modifier.fillMaxWidth().padding(bottom = 8.dp)) { onOpen(k) } }
        TextButton(onClick = onCustomize, modifier = Modifier.testTag("home.customize")) {
            Text(if (cards.isEmpty()) "Customize Home: add reports" else "Customize Home", color = DdfColors.Cyan)
        }
    }
}

/**
 * Customize Home: the cards on Home, in order, each with a handle to drag it up or down and Remove; under them every
 * other report, with Add. Each change is saved as it's made.
 */
@Composable
fun HomeCustomizeScreen(cards: List<Kind>, onChange: (List<Kind>) -> Unit, onBack: () -> Unit) {
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).testTag("home.customizeScreen")) {
        ScreenTitle("Customize Home", onBack)
        Text(
            "Reports to show on Home, as cards. Drag the handle to change the order. This is kept on this phone only.",
            color = DdfColors.Muted, style = MaterialTheme.typography.bodySmall,
        )
        SectionTitle("On Home")
        if (cards.isEmpty()) EmptyState("No reports on Home. Add one below.")
        DragList(cards, onChange)
        SectionTitle("Add a report")
        val others = Kind.entries.filter { it !in cards }
        if (others.isEmpty()) Text("Every report is on Home.", color = DdfColors.Muted, style = MaterialTheme.typography.bodySmall)
        others.forEach { k ->
            ListRow(null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(k.title)
                        Text(k.sub, color = DdfColors.Muted, style = MaterialTheme.typography.bodySmall)
                    }
                    TextButton(onClick = { onChange(HomeDashboard.add(cards, k)) }, modifier = Modifier.testTag("home.add.${k.name}")) { Text("Add") }
                }
            }
        }
        Gap(24)
    }
}

/** The chosen cards with drag handles (as the fill order's), each with Remove. */
@Composable
private fun DragList(cards: List<Kind>, onChange: (List<Kind>) -> Unit) {
    var order by remember(cards) { mutableStateOf(cards) }
    var dragging by remember { mutableStateOf<Kind?>(null) }
    var offset by remember { mutableFloatStateOf(0f) }
    val heights = remember { mutableStateMapOf<Kind, Int>() }
    Column {
        order.forEach { k ->
            androidx.compose.runtime.key(k) {
                Box(
                    Modifier.onGloballyPositioned { heights[k] = it.size.height }.zIndex(if (dragging == k) 1f else 0f)
                        .graphicsLayer { translationY = if (dragging == k) offset else 0f }.testTag("home.card.${k.name}"),
                ) {
                    ListRow(null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "≡", color = DdfColors.Muted, style = MaterialTheme.typography.titleLarge,
                                modifier = Modifier.padding(end = 12.dp).testTag("home.handle.${k.name}").pointerInput(k) {
                                    detectDragGestures(
                                        onDragStart = { dragging = k; offset = 0f },
                                        onDragEnd = { dragging = null; offset = 0f; onChange(order) },
                                        onDragCancel = { dragging = null; offset = 0f },
                                        onDrag = { change, amount ->
                                            change.consume()
                                            offset += amount.y
                                            val i = order.indexOf(k)
                                            val h = heights[k] ?: return@detectDragGestures
                                            if (offset > h / 2f && i < order.lastIndex) {
                                                order = HomeDashboard.move(order, k, i + 1); offset -= h
                                            } else if (offset < -h / 2f && i > 0) {
                                                order = HomeDashboard.move(order, k, i - 1); offset += h
                                            }
                                        },
                                    )
                                },
                            )
                            Text(k.title, Modifier.weight(1f))
                            TextButton(onClick = { onChange(HomeDashboard.remove(order, k)) }, modifier = Modifier.testTag("home.remove.${k.name}")) { Text("Remove") }
                        }
                    }
                }
            }
        }
    }
}
