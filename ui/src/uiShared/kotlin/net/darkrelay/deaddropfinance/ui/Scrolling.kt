package net.darkrelay.deaddropfinance.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp

/**
 * The scrollbars a platform draws beside the shared scroll containers (0.15.6): the program's (thin, rounded, on the
 * right, and along the bottom for sideways scrolling), each drawn only while its content overflows. The phone has none.
 */
interface Scrollbars {
    /** The room kept for a bar beside the content, so a bar never sits on top of it. */
    val gutter: Dp
    @Composable fun Vertical(state: ScrollState, modifier: Modifier)
    @Composable fun Vertical(state: LazyListState, modifier: Modifier)
    @Composable fun Horizontal(state: ScrollState, modifier: Modifier)
}

/** The window's [Scrollbars]: the program's; null on the phone. */
val LocalScrollbars = staticCompositionLocalOf<Scrollbars?> { null }

/**
 * The shared scrolling column every scrolling screen is (0.15.6): [modifier] sizes it, [inner] is the content's own
 * (its padding, its tag). On the phone it's the plain scrolling Column it always was; in the program the content keeps
 * the bar's [Scrollbars.gutter] free on the right and the bar shows there while the content is taller than the screen.
 */
@Composable
fun ScrollColumn(
    modifier: Modifier = Modifier,
    state: ScrollState = rememberScrollState(),
    inner: Modifier = Modifier,
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    horizontalAlignment: Alignment.Horizontal = Alignment.Start,
    content: @Composable ColumnScope.() -> Unit,
) {
    val bars = LocalScrollbars.current
    if (bars == null) {
        Column(modifier.verticalScroll(state).then(inner), verticalArrangement, horizontalAlignment, content)
        return
    }
    Box(modifier) {
        Column(Modifier.fillMaxWidth().verticalScroll(state).padding(end = bars.gutter).then(inner), verticalArrangement, horizontalAlignment, content)
        Box(Modifier.matchParentSize()) { bars.Vertical(state, Modifier.align(Alignment.CenterEnd).fillMaxHeight()) }
    }
}

/** The shared lazy list (Home): a LazyColumn, with the program's bar on the right as [ScrollColumn] has it. */
@Composable
fun ScrollLazyColumn(
    modifier: Modifier = Modifier,
    state: LazyListState = rememberLazyListState(),
    contentPadding: PaddingValues = PaddingValues(),
    content: LazyListScope.() -> Unit,
) {
    val bars = LocalScrollbars.current
    if (bars == null) {
        LazyColumn(modifier, state, contentPadding, content = content)
        return
    }
    Box(modifier) {
        LazyColumn(Modifier.fillMaxWidth().padding(end = bars.gutter), state, contentPadding, content = content)
        Box(Modifier.matchParentSize()) { bars.Vertical(state, Modifier.align(Alignment.CenterEnd).fillMaxHeight()) }
    }
}

/** The shared sideways-scrolling row: in the program its bar runs along the bottom, below the content. */
@Composable
fun ScrollRow(
    modifier: Modifier = Modifier,
    state: ScrollState = rememberScrollState(),
    inner: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    val bars = LocalScrollbars.current
    if (bars == null) {
        Row(modifier.horizontalScroll(state).then(inner), content = content)
        return
    }
    Box(modifier) {
        Row(Modifier.horizontalScroll(state).padding(bottom = bars.gutter).then(inner), content = content)
        Box(Modifier.matchParentSize()) { bars.Horizontal(state, Modifier.align(Alignment.BottomCenter).fillMaxWidth()) }
    }
}
