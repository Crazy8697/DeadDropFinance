package net.darkrelay.deaddropfinance.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import net.darkrelay.deaddropfinance.domain.Money

/** The widest amount the column is sized for (11 characters): money in and money out at their largest realistic size. */
val AMOUNT_COLUMN_SAMPLES = listOf("+$12,345.67", "-$98,765.43")

/**
 * The width of Activity's amount column: the widest of [AMOUNT_COLUMN_SAMPLES] in the row's text style, measured at the
 * phone's own font size (a larger system font makes the column wider, so an amount never wraps or shrinks).
 */
@Composable
fun amountColumnWidth(): Dp {
    val measurer = rememberTextMeasurer()
    val style = LocalTextStyle.current
    val px = AMOUNT_COLUMN_SAMPLES.maxOf { measurer.measure(it, style, softWrap = false, maxLines = 1).size.width }
    return with(LocalDensity.current) { px.toDp() } + 2.dp
}

/** A chip for an Activity row's third line: the first sits flush left under the info line. */
@Composable
fun RowChip(text: String, first: Boolean) {
    Text(
        text, style = MaterialTheme.typography.labelSmall, color = DdfColors.Muted, maxLines = 1, softWrap = false,
        modifier = Modifier.padding(start = if (first) 0.dp else 6.dp).clip(RoundedCornerShape(6.dp)).background(DdfColors.SurfaceHigh)
            .padding(horizontal = 6.dp, vertical = 1.dp),
    )
}

/**
 * One Activity row (1.2.1), three lines: the name on one line (cut with an ellipsis, never wrapping), the info line
 * (date and status), and the category and status chips on their own line (always there, so every row is the same
 * height). The amount sits in a fixed column on the right ([amountColumnWidth]): it never wraps, shrinks or moves, so
 * amounts line up down the screen. [below] is anything under the three lines (a suggestion, a picker, buttons).
 */
@Composable
fun ActivityRowLines(
    name: String,
    info: String,
    chips: List<String>,
    amount: Long?,
    onClick: (() -> Unit)?,
    amountColor: Color? = null,
    below: @Composable () -> Unit = {},
) {
    val column = amountColumnWidth()
    val chipLine = with(LocalDensity.current) { MaterialTheme.typography.labelSmall.lineHeight.toDp() } + 2.dp
    ListRow(onClick) {
        Row(verticalAlignment = Alignment.Top, modifier = Modifier.testTag("activityRow")) {
            Column(Modifier.weight(1f)) {
                Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("rowName"))
                Text(info, color = DdfColors.Muted, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(Modifier.heightIn(min = chipLine), verticalAlignment = Alignment.CenterVertically) {
                    chips.forEachIndexed { i, c -> RowChip(c, first = i == 0) }
                }
            }
            Spacer(Modifier.width(8.dp))
            Box(Modifier.width(column).testTag("rowAmount"), contentAlignment = Alignment.TopEnd) {
                if (amount != null) {
                    Text(
                        Money.format(amount), color = amountColor ?: moneyColor(amount), maxLines = 1, softWrap = false,
                        textAlign = TextAlign.End, overflow = TextOverflow.Visible, modifier = Modifier.testTag("rowAmountText"),
                    )
                }
            }
        }
        below()
    }
}
