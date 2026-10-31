package net.darkrelay.deaddropfinance.desktop

import androidx.compose.foundation.HorizontalScrollbar
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.ScrollbarStyle
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import net.darkrelay.deaddropfinance.ui.Scrollbars

/**
 * The program's scrollbars (0.15.6), on every scrolling screen (the shared ScrollColumn and its kin): a thin rounded
 * thumb, #1F2A33 at rest and lighter under the mouse, with no track of its own (the window shows through), on the
 * right, or along the bottom for sideways scrolling. Shown only while the content overflows; the content keeps
 * [gutter] free for it, so it never sits on top of anything.
 */
object ProgramScrollbars : Scrollbars {
    val thickness = 6.dp
    override val gutter = 10.dp
    val rest = C.ScrollThumb
    val hover = C.ScrollThumbHover
    val style = ScrollbarStyle(
        minimalHeight = 28.dp, thickness = thickness, shape = RoundedCornerShape(thickness / 2),
        hoverDurationMillis = 200, unhoverColor = rest, hoverColor = hover,
    )

    @Composable
    override fun Vertical(state: ScrollState, modifier: Modifier) {
        if (state.maxValue <= 0) return
        VerticalScrollbar(rememberScrollbarAdapter(state), modifier.padding(2.dp).testTag("scrollbar.vertical"), style = style)
    }

    @Composable
    override fun Vertical(state: LazyListState, modifier: Modifier) {
        if (!state.canScrollForward && !state.canScrollBackward) return
        VerticalScrollbar(rememberScrollbarAdapter(state), modifier.padding(2.dp).testTag("scrollbar.vertical"), style = style)
    }

    @Composable
    override fun Horizontal(state: ScrollState, modifier: Modifier) {
        if (state.maxValue <= 0) return
        HorizontalScrollbar(rememberScrollbarAdapter(state), modifier.padding(2.dp).testTag("scrollbar.horizontal"), style = style)
    }

}
