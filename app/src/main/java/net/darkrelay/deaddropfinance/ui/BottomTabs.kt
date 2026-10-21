package net.darkrelay.deaddropfinance.ui

import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * The tab icons, drawn here as simple 24 by 24 shapes (the app ships no icon library). Home is a house, Envelopes an
 * envelope, Activity a list, More three dots, as in the reference mockup's bottom bar.
 */
object TabIcons {
    private fun icon(name: String, block: ImageVector.Builder.() -> Unit) =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply(block).build()

    private fun ImageVector.Builder.line(block: androidx.compose.ui.graphics.vector.PathBuilder.() -> Unit) =
        path(stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round, pathBuilder = block)

    val Home = icon("home") {
        line { moveTo(3f, 11f); lineTo(12f, 3.5f); lineTo(21f, 11f) }
        line { moveTo(5.5f, 9.5f); lineTo(5.5f, 20f); lineTo(10f, 20f); lineTo(10f, 14.5f); lineTo(14f, 14.5f); lineTo(14f, 20f); lineTo(18.5f, 20f); lineTo(18.5f, 9.5f) }
    }

    val Envelope = icon("envelope") {
        line { moveTo(3f, 6f); lineTo(21f, 6f); lineTo(21f, 18f); lineTo(3f, 18f); close() }
        line { moveTo(3.5f, 6.5f); lineTo(12f, 13f); lineTo(20.5f, 6.5f) }
    }

    val List = icon("list") {
        for (y in listOf(7f, 12f, 17f)) {
            line { moveTo(9f, y); lineTo(20f, y) }
            line { moveTo(4.5f, y); lineTo(5f, y) }
        }
    }

    val More = icon("more") {
        for (x in listOf(5f, 12f, 19f)) {
            path(fill = SolidColor(Color.Black)) {
                moveTo(x - 2f, 12f)
                arcToRelative(2f, 2f, 0f, isMoreThanHalf = true, isPositiveArc = true, dx1 = 4f, dy1 = 0f)
                arcToRelative(2f, 2f, 0f, isMoreThanHalf = true, isPositiveArc = true, dx1 = -4f, dy1 = 0f)
                close()
            }
        }
    }
}

/** The four tabs, each with its icon and its name. The selected one is cyan, the others muted, as in the mockup. */
@Composable
fun BottomTabs(selected: Nav, onTab: (Nav) -> Unit) {
    val tabs = listOf(
        Triple(Nav.Home, "Home", TabIcons.Home),
        Triple(Nav.Envelopes, "Envelopes", TabIcons.Envelope),
        Triple(Nav.Activity, "Activity", TabIcons.List),
        Triple(Nav.More, "More", TabIcons.More),
    )
    NavigationBar(containerColor = DdfColors.Background) {
        tabs.forEach { (n, label, icon) ->
            NavigationBarItem(
                selected = selected == n,
                onClick = { onTab(n) },
                icon = { Icon(icon, contentDescription = null) },
                label = { Text(label, maxLines = 1, softWrap = false, overflow = TextOverflow.Visible) },
                alwaysShowLabel = true,
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = DdfColors.Cyan, selectedTextColor = DdfColors.Cyan,
                    unselectedIconColor = DdfColors.Muted, unselectedTextColor = DdfColors.Muted,
                    indicatorColor = Color.Transparent,
                ),
            )
        }
    }
}
