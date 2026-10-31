package net.darkrelay.deaddropfinance.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate
import java.time.YearMonth
import net.darkrelay.deaddropfinance.domain.Money
import net.darkrelay.deaddropfinance.domain.Reports

/*
 * The report charts (stage 27: phone 1.9.0, program 0.15.0), drawn with Compose in the screens' own colors. The numbers
 * come from Reports; these only draw them.
 */

/** A chart's series colors, in order: the screens' accents, then [ChartColors]. */
fun seriesColor(i: Int): Color {
    val all = listOf(DdfColors.Cyan, DdfColors.Warning) + ChartColors + listOf(DdfColors.Error, DdfColors.Muted)
    return all[i % all.size]
}

/** A share of a total as one row: the name, the amount, its percent, and a bar as long as its share of the biggest. */
@Composable
fun ShareBars(shares: List<Reports.Share>, max: Int = Int.MAX_VALUE, onClick: ((Reports.Share) -> Unit)? = null) {
    val top = shares.maxOfOrNull { it.amount }?.takeIf { it > 0 } ?: 1L
    shares.take(max).forEach { s ->
        Column(
            Modifier.fillMaxWidth().let { if (onClick != null) it.clickable { onClick(s) } else it }.padding(vertical = 5.dp)
                .testTag("share.${s.name}"),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(s.name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, color = if (s.id == null) DdfColors.Muted else DdfColors.Text)
                MoneyText(s.amount)
                Text(percentText(s.share), Modifier.width(52.dp), textAlign = TextAlign.End, color = DdfColors.Muted, style = MaterialTheme.typography.bodySmall)
            }
            Bar((s.amount.coerceAtLeast(0).toFloat() / top).coerceIn(0f, 1f), DdfColors.Cyan)
        }
    }
}

/** A thin bar, [fraction] of the width, in [color]. */
@Composable
fun Bar(fraction: Float, color: Color, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(top = 4.dp).height(6.dp).clip(RoundedCornerShape(3.dp)).background(DdfColors.Outline)) {
        if (fraction > 0f) Box(Modifier.fillMaxWidth(fraction).height(6.dp).clip(RoundedCornerShape(3.dp)).background(color))
    }
}

/** Hundredths of a percent as a whole percent: "25%", "<1%" for a sliver, "-12%". */
fun percentText(hundredths: Long): String = when {
    hundredths in 1..49 -> "<1%"
    else -> "${Math.floorDiv(hundredths + 50, 100)}%"
}

/** Pairs of bars per label (income and spending, say), from zero up, with each label under its pair. */
@Composable
fun PairBars(labels: List<String>, a: List<Long>, b: List<Long>, colorA: Color, colorB: Color, height: Int) {
    val top = (a + b).maxOrNull()?.takeIf { it > 0 } ?: 1L
    val outline = DdfColors.Outline
    Canvas(Modifier.fillMaxWidth().height(height.dp).testTag("chart.pairs")) {
        val n = maxOf(1, labels.size)
        val slot = size.width / n
        val bar = minOf(slot * 0.32f, 28.dp.toPx())
        drawLine(outline, Offset(0f, size.height), Offset(size.width, size.height), strokeWidth = 2f)
        for (i in labels.indices) {
            val cx = slot * i + slot / 2
            fun h(v: Long) = size.height * (v.coerceAtLeast(0).toFloat() / top)
            drawRect(colorA, Offset(cx - bar - 2f, size.height - h(a[i])), Size(bar, h(a[i])))
            drawRect(colorB, Offset(cx + 2f, size.height - h(b[i])), Size(bar, h(b[i])))
        }
    }
    AxisLabels(labels)
}

/** Labels spread under a chart: each one under its slot, every other one left out when there are many. */
@Composable
fun AxisLabels(labels: List<String>, points: Boolean = false) {
    // just the two ends (a payoff plan's first and last dates): each at its end, at full width
    if (labels.size > 2 && labels.count { it.isNotBlank() } <= 2) {
        Row(Modifier.fillMaxWidth().padding(top = 2.dp)) {
            Text(labels.first(), color = DdfColors.Muted, fontSize = 10.sp)
            androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
            Text(labels.last(), color = DdfColors.Muted, fontSize = 10.sp)
        }
        return
    }
    val every = if (labels.size > 8) 2 else 1
    Row(Modifier.fillMaxWidth().padding(top = 2.dp)) {
        labels.forEachIndexed { i, l ->
            Text(
                if (i % every == 0 || i == labels.lastIndex) l else "", Modifier.weight(1f), maxLines = 1,
                textAlign = if (points && i == 0) TextAlign.Start else if (points && i == labels.lastIndex) TextAlign.End else TextAlign.Center,
                color = DdfColors.Muted, fontSize = 10.sp,
            )
        }
    }
}

/** Lines over time, one per series, with a dot on each point and the zero line dashed when it's in range. */
@Composable
fun LineChart(series: List<Pair<Color, List<Long>>>, labels: List<String>, height: Int, tag: String = "chart.line") {
    val all = series.flatMap { it.second }
    val max = (all + 0L).max()
    val min = (all + 0L).min()
    val outline = DdfColors.Outline
    Canvas(Modifier.fillMaxWidth().height(height.dp).padding(vertical = 6.dp).testTag(tag)) {
        val n = series.maxOfOrNull { it.second.size } ?: 0
        fun x(i: Int) = if (n <= 1) size.width / 2 else size.width * i / (n - 1)
        fun y(v: Long) = if (max == min) size.height / 2 else size.height * (max - v).toFloat() / (max - min).toFloat()
        drawLine(outline, Offset(0f, y(0)), Offset(size.width, y(0)), strokeWidth = 2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)))
        for ((color, points) in series) {
            points.zipWithNext().forEachIndexed { i, (a, b) -> drawLine(color, Offset(x(i), y(a)), Offset(x(i + 1), y(b)), strokeWidth = 4f) }
            points.forEachIndexed { i, v -> drawCircle(color, 5f, Offset(x(i), y(v))) }
        }
    }
    AxisLabels(labels, points = true)
}

/** A color key: a dot and a name per series. */
@Composable
fun Legend(items: List<Pair<Color, String>>) {
    Column(Modifier.padding(vertical = 4.dp)) {
        items.forEach { (c, name) ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(c))
                Text(name, Modifier.padding(start = 8.dp), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/** Small bars for a report tile. */
@Composable
fun MiniBars(values: List<Long>, color: Color = DdfColors.Cyan, modifier: Modifier = Modifier) {
    val top = values.maxOrNull()?.takeIf { it > 0 } ?: 1L
    val outline = DdfColors.Outline
    Canvas(modifier.fillMaxWidth().height(44.dp)) {
        drawLine(outline, Offset(0f, size.height), Offset(size.width, size.height), strokeWidth = 2f)
        if (values.isEmpty()) return@Canvas
        val slot = size.width / values.size
        values.forEachIndexed { i, v ->
            val h = size.height * (v.coerceAtLeast(0).toFloat() / top)
            drawRect(color, Offset(slot * i + slot * 0.18f, size.height - h), Size(slot * 0.64f, h))
        }
    }
}

/** A small line for a report tile. */
@Composable
fun MiniLine(values: List<Long>, color: Color = DdfColors.Cyan, modifier: Modifier = Modifier) {
    val max = (values + 0L).max(); val min = (values + 0L).min()
    val outline = DdfColors.Outline
    Canvas(modifier.fillMaxWidth().height(44.dp)) {
        fun x(i: Int) = if (values.size <= 1) size.width / 2 else size.width * i / (values.size - 1)
        fun y(v: Long) = if (max == min) size.height / 2 else size.height * (max - v).toFloat() / (max - min).toFloat()
        drawLine(outline, Offset(0f, y(0)), Offset(size.width, y(0)), strokeWidth = 2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)))
        values.zipWithNext().forEachIndexed { i, (a, b) -> drawLine(color, Offset(x(i), y(a)), Offset(x(i + 1), y(b)), strokeWidth = 4f) }
        if (values.size == 1) drawCircle(color, 5f, Offset(x(0), y(values[0])))
    }
}

/**
 * Money flow as three columns of bars joined by ribbons: what came in, the envelopes it was given to, and what was
 * spent and kept. Every column is the same total, so each ribbon is as wide as the money it carries.
 */
@Composable
fun FlowChart(f: Reports.Flow, height: Int) {
    val measurer = rememberTextMeasurer()
    val text = DdfColors.Text; val muted = DdfColors.Muted; val cyan = DdfColors.Cyan; val warn = DdfColors.Warning; val teal = DdfColors.Teal
    val label = TextStyle(color = text, fontSize = 11.sp)
    Canvas(Modifier.fillMaxWidth().height(height.dp).testTag("chart.flow")) {
        val total = f.total.toFloat().takeIf { it > 0f } ?: return@Canvas
        val barW = 10.dp.toPx()
        val gap = 6.dp.toPx()
        // one scale for every column, the one with the most bars and so the most gaps, so a ribbon is as wide as its bars
        val xs = listOf(0f, size.width * 0.42f, size.width - barW)
        class Node(val name: String, val value: Long, val color: Color) { var top = 0f; var h = 0f; var outAt = 0f; var inAt = 0f }
        var perCent = 0f
        fun column(nodes: List<Node>, x: Float) {
            val shown = nodes.filter { it.value > 0 }
            var y = 0f
            for (n in shown) { n.top = y; n.h = perCent * n.value; n.outAt = y; n.inAt = y; y += n.h + gap }
            for (n in shown) drawRect(n.color, Offset(x, n.top), Size(barW, n.h))
        }
        val left = listOf(Node("Income", f.income, cyan), Node("Earlier money", f.earlier, teal))
        val mid = f.envelopes.mapIndexed { i, e -> Node(e.name, e.given, seriesColor(i + 2)) } + Node("Not assigned", f.notAssigned, muted)
        val right = listOf(Node("Spent", f.spent, warn), Node("Kept", f.kept, cyan), Node("Ready to assign", f.notAssigned, muted))
        val most = listOf(left, mid, right).maxOf { c -> c.count { it.value > 0 } }
        perCent = (size.height - gap * (most - 1).coerceAtLeast(0)) / total
        column(left, xs[0]); column(mid, xs[1]); column(right, xs[2])
        fun ribbon(from: Node, to: Node, value: Long, x1: Float, x2: Float, color: Color) {
            if (value <= 0) return
            val h = perCent * value
            val y1 = from.outAt; val y2 = to.inAt
            from.outAt += h; to.inAt += h
            val mx = (x1 + x2) / 2
            val p = Path().apply {
                moveTo(x1, y1); cubicTo(mx, y1, mx, y2, x2, y2); lineTo(x2, y2 + h); cubicTo(mx, y2 + h, mx, y1 + h, x1, y1 + h); close()
            }
            drawPath(p, color.copy(alpha = 0.32f))
        }
        // left to middle: what came in fills the envelopes from the top, then Not assigned
        var src = 0
        val remaining = left.map { it.value }.toMutableList()
        for (m in mid) {
            var need = m.value
            while (need > 0 && src < left.size) {
                val take = minOf(need, remaining[src])
                ribbon(left[src], m, take, xs[0] + barW, xs[1], left[src].color)
                need -= take; remaining[src] -= take
                if (remaining[src] <= 0) src++
            }
        }
        // middle to right: each envelope's spent and kept; Not assigned stays in Ready to assign
        for (m in mid.dropLast(1)) {
            val e = f.envelopes.first { it.name == m.name && it.given == m.value }
            ribbon(m, right[0], e.spent, xs[1] + barW, xs[2], m.color)
            ribbon(m, right[1], e.kept, xs[1] + barW, xs[2], m.color)
        }
        ribbon(mid.last(), right[2], f.notAssigned, xs[1] + barW, xs[2], muted)
        // a name goes beside its bar where there's room, and never over another one (on a phone the middle column's names
        // reach the right column's); every figure is in the totals under the chart as well
        val taken = mutableListOf<androidx.compose.ui.geometry.Rect>()
        fun names(nodes: List<Node>, x: Float, end: Boolean) {
            for (n in nodes.filter { it.value > 0 }) {
                val t = measurer.measure("${n.name} ${Money.format(n.value)}", label)
                if (t.size.height > n.h + gap) continue
                val tx = (if (end) x - t.size.width - 4.dp.toPx() else x + barW + 4.dp.toPx()).coerceAtLeast(0f)
                val r = androidx.compose.ui.geometry.Rect(Offset(tx, n.top + n.h / 2 - t.size.height / 2), Size(t.size.width.toFloat(), t.size.height.toFloat()))
                if (r.right > size.width || taken.any { it.overlaps(r) }) continue
                taken += r
                drawText(t, topLeft = r.topLeft)
            }
        }
        names(left, xs[0], false); names(mid, xs[1], false); names(right, xs[2], true)
    }
}

/**
 * A month as a calendar, Sunday first: each day with what's due on it, today ringed. Where a day is too narrow for the
 * amount (a phone), a dot marks it instead; the list under the calendar has every name and amount.
 */
@Composable
fun CalendarGrid(month: YearMonth, dues: List<Reports.Due>, today: LocalDate) = androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth()) {
    val roomy = maxWidth / 7 >= 72.dp
    Column { CalendarDays(month, dues, today, roomy) }
}

@Composable
private fun CalendarDays(month: YearMonth, dues: List<Reports.Due>, today: LocalDate, roomy: Boolean) {
    val first = month.atDay(1)
    val lead = first.dayOfWeek.value % 7
    val days = month.lengthOfMonth()
    val byDay = dues.groupBy { it.date.dayOfMonth }
    Row(Modifier.fillMaxWidth()) {
        listOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat").forEach {
            Text(it, Modifier.weight(1f), textAlign = TextAlign.Center, color = DdfColors.Muted, fontSize = 11.sp)
        }
    }
    val cells = lead + days
    for (week in 0 until (cells + 6) / 7) {
        Row(Modifier.fillMaxWidth()) {
            for (dow in 0 until 7) {
                val day = week * 7 + dow - lead + 1
                Box(Modifier.weight(1f).aspectRatio(1.15f).padding(2.dp)) {
                    if (day in 1..days) {
                        val due = byDay[day].orEmpty()
                        val isToday = month.atDay(day) == today
                        Column(
                            Modifier.fillMaxSize().clip(RoundedCornerShape(6.dp))
                                .background(if (due.isEmpty()) DdfColors.Surface else DdfColors.SurfaceHigh)
                                .let { if (isToday) it.border(1.dp, DdfColors.Cyan, RoundedCornerShape(6.dp)) else it }
                                .padding(4.dp).testTag("day.$day"),
                        ) {
                            Text("$day", fontSize = 11.sp, color = if (isToday) DdfColors.Cyan else DdfColors.Muted)
                            if (due.isNotEmpty()) {
                                val open = due.filter { !it.paid }
                                val color = when { open.isEmpty() -> DdfColors.Muted; open.any { it.debt } -> DdfColors.Warning; else -> DdfColors.Cyan }
                                if (roomy) {
                                    Text(Money.format(due.sumOf { it.amount }), fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Clip, color = color)
                                    Text(if (due.size == 1) due[0].name else "${due.size} due", fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = DdfColors.Muted)
                                } else {
                                    Box(Modifier.padding(top = 4.dp).size(8.dp).clip(CircleShape).background(color).testTag("dot.${day}"))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** A short name for a budget month on a chart: its first day ("Mar 1"), or "Before" one. */
fun monthShort(m: net.darkrelay.deaddropfinance.domain.Budget.Month): String {
    val f = java.time.format.DateTimeFormatter.ofPattern("MMM d")
    return when {
        m.start != null -> m.start!!.format(f)
        m.end != null -> "Before"
        else -> "All"
    }
}

