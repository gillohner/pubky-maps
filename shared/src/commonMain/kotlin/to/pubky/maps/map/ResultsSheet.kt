package to.pubky.maps.map

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.AnchoredDraggableState
import androidx.compose.foundation.gestures.DraggableAnchors
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.anchoredDraggable
import androidx.compose.foundation.gestures.animateTo
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/** How much of the results list shows: its first row, half the screen, or all of it. */
enum class SheetHeight { Peek, Half, Full }

/** The strip at the top of the sheet that holds the drag handle. */
private val HandleHeight = 24.dp

/** The lowest height until the first row has been measured: the handle and a row at the normal font size. */
private val FallbackPeekHeight = 120.dp

/** A release faster than this (about a flick) goes on to the next height; a slower one to the nearest. */
private val FlingVelocity = 400.dp

/**
 * The least a lowest height must lie below half the screen to be one of its own: a closer one would
 * look like half, and the handle's tap or TalkBack's resize from it would seem to do nothing.
 */
private val PeekStep = HandleHeight

/**
 * The results over the map on a phone, as in Google Maps and CoMaps: a sheet at the bottom that the
 * handle drags between its first row ([SheetHeight.Peek]), half the screen ([SheetHeight.Half], where
 * it opens) and the whole height below the search bar ([SheetHeight.Full]); tapping the handle steps
 * up, and back down from full. The list drags it too: a finger moving up raises the sheet to full
 * before the list scrolls, and one moving down with the list at its top lowers it. [above] (the
 * credits and my-location button) rides on the sheet's top edge while there is room for it under
 * [topSpace]; at full height, or while the sheet is dragged that far, it is not there at all (not
 * drawn, not touchable, not read by TalkBack), so it never covers the search bar or Settings.
 * [topSpace] is left free for the search bar.
 *
 * The lowest height shows the handle and the whole first row, as Google Maps' collapsed results
 * show one whole result, above the navigation bar ([bottomInset], which the list stays above at
 * every height): [content] scrolls its list by the given state and reports how far its first
 * row reaches from its top (it grows with the font size and a long name or address), and the
 * sheet's lowest height follows it, never above half the screen. When that leaves it not even
 * [PeekStep] below half (a short window at a large font), the sheet has only half and full. At its
 * lowest height the list is put back at its start, so a sheet lowered from a scrolled list, or given
 * new rows above the one it shows, shows the whole first row, not a part of a later one.
 */
@Composable
internal fun ResultsSheet(
    height: Dp,
    topSpace: Dp,
    bottomInset: Dp,
    above: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable (list: LazyListState, onFirstRowEnd: (Int) -> Unit) -> Unit,
) {
    val density = LocalDensity.current
    var firstRowEnd by remember { mutableIntStateOf(0) }
    val anchors =
        with(density) {
            val total = height.toPx()
            val half = total / 2
            val peek = if (firstRowEnd > 0) HandleHeight.toPx() + firstRowEnd else FallbackPeekHeight.toPx()
            val peekAt = total - (peek + bottomInset.toPx()).coerceAtMost(half)
            DraggableAnchors {
                SheetHeight.Full at topSpace.toPx()
                SheetHeight.Half at half
                if (peekAt - half >= PeekStep.toPx()) SheetHeight.Peek at peekAt
            }
        }
    val state = remember { AnchoredDraggableState(SheetHeight.Half, anchors) }
    // New heights (a measured first row, a turned screen) keep the sheet at the height it is at, or
    // at half when that height is gone.
    SideEffect { state.updateAnchors(anchors, state.targetValue.takeIf(anchors::hasPositionFor) ?: SheetHeight.Half) }
    val list = rememberLazyListState()
    LaunchedEffect(state, list) { keepListStartAtPeek(state, list) }
    val listDrag = remember(state, density) { ListDragsSheet(state, density) }
    val scope = rememberCoroutineScope()
    val step: () -> Unit = {
        val next =
            when (state.currentValue) {
                SheetHeight.Peek -> SheetHeight.Half
                SheetHeight.Half -> SheetHeight.Full
                SheetHeight.Full -> SheetHeight.Half
            }
        scope.launch { state.animateTo(next) }
    }
    Layout(
        contents =
            listOf(
                { if (state.currentValue != SheetHeight.Full) above() },
                {
                    Surface(
                        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
                        color = MaterialTheme.colorScheme.surface,
                        shadowElevation = 3.dp,
                    ) {
                        Column {
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .height(HandleHeight)
                                    .anchoredDraggable(state, Orientation.Vertical)
                                    .clickable(onClick = step)
                                    .semantics {
                                        contentDescription = "Results list, ${state.currentValue.name.lowercase()}"
                                        onClick(label = "Resize the results list") {
                                            step()
                                            true
                                        }
                                    },
                                contentAlignment = Alignment.Center,
                            ) {
                                Box(
                                    Modifier
                                        .size(width = 32.dp, height = 4.dp)
                                        .background(
                                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                                            RoundedCornerShape(2.dp),
                                        ),
                                )
                            }
                            // The list ends above the navigation bar, so at the lowest height nothing of the
                            // second row shows behind the bar; the sheet's own colour fills under it.
                            Box(Modifier.padding(bottom = bottomInset).nestedScroll(listDrag)) { content(list) { firstRowEnd = it } }
                        }
                    }
                },
            ),
        modifier = modifier,
    ) { (aboveMeasurables, sheetMeasurables), constraints ->
        val offset = state.offset.takeUnless { it.isNaN() }?.roundToInt() ?: (constraints.maxHeight / 2)
        val sheetHeight = (constraints.maxHeight - offset).coerceAtLeast(0)
        val sheet = sheetMeasurables.first().measure(Constraints.fixed(constraints.maxWidth, sheetHeight))
        val aboveSheet = aboveMeasurables.firstOrNull()?.measure(constraints.copy(minHeight = 0, minWidth = 0))
        val topLimit = topSpace.roundToPx()
        layout(constraints.maxWidth, constraints.maxHeight) {
            // Left unplaced (so neither drawn nor touchable) when it would reach under the search bar.
            if (aboveSheet != null && offset - aboveSheet.height >= topLimit) aboveSheet.place(0, offset - aboveSheet.height)
            sheet.place(0, offset)
        }
    }
}

/**
 * Puts [list] back at its start when the sheet comes down to its lowest height with the list scrolled
 * (it was scrolled at a higher one), and when, at the lowest height, new rows came in above the row it
 * showed, which the list keeps in view: there the first row is measured and shown whole. A scroll at
 * that height that shows other rows (TalkBack moving on to the next result) stays.
 */
private suspend fun keepListStartAtPeek(
    state: AnchoredDraggableState<SheetHeight>,
    list: LazyListState,
) {
    var wasAtPeek = false
    var shownIndex = 0
    var shownKey: Any? = null
    // By position, not `settledValue`: a sheet lowered by dragging its list stops at the lowest height
    // without an animation, so it is there without having settled.
    val shown =
        snapshotFlow {
            val peek = state.anchors.positionOf(SheetHeight.Peek)
            // NaN (never near) while there is no lowest height or no offset yet.
            val atPeek = abs(state.offset - peek) < 1f
            val rows = list.layoutInfo.visibleItemsInfo
            Triple(atPeek, list.firstVisibleItemIndex, rows.firstOrNull()?.key)
        }
    shown.collect { (atPeek, index, key) ->
        val scrolled = index != 0 || list.firstVisibleItemScrollOffset != 0
        val pushedDown = key != null && key == shownKey && index > shownIndex
        if (atPeek && scrolled && (!wasAtPeek || pushedDown)) list.requestScrollToItem(0)
        wasAtPeek = atPeek
        shownIndex = index
        shownKey = key
    }
}

/**
 * Passes the list's scrolling to the sheet first while it can move (as Google Maps' and CoMaps'
 * results): up until the sheet is full, then the list scrolls; down once the list is at its top. A
 * release that moved the sheet settles it at a height.
 */
private class ListDragsSheet(
    private val state: AnchoredDraggableState<SheetHeight>,
    density: Density,
) : NestedScrollConnection {
    private val flingVelocity = with(density) { FlingVelocity.toPx() }

    override fun onPreScroll(
        available: Offset,
        source: NestedScrollSource,
    ): Offset =
        if (available.y < 0 && source == NestedScrollSource.UserInput) {
            Offset(0f, state.dispatchRawDelta(available.y))
        } else {
            Offset.Zero
        }

    override fun onPostScroll(
        consumed: Offset,
        available: Offset,
        source: NestedScrollSource,
    ): Offset =
        if (source == NestedScrollSource.UserInput) {
            Offset(0f, state.dispatchRawDelta(available.y))
        } else {
            Offset.Zero
        }

    override suspend fun onPreFling(available: Velocity): Velocity {
        // An upward flick while the sheet is not yet full raises the sheet, not the list.
        if (available.y < 0 && state.offset > state.anchors.minPosition()) {
            settle(available.y)
            return available
        }
        return Velocity.Zero
    }

    // A sheet resting at a height leaves the rest of the fling to the list (its edge stretch).
    override suspend fun onPostFling(
        consumed: Velocity,
        available: Velocity,
    ): Velocity = if (settle(available.y)) available else Velocity.Zero

    /**
     * To the next height in the direction of a flick, else to the nearest; a sheet at a height stays.
     * Returns whether the sheet moved.
     */
    private suspend fun settle(velocity: Float): Boolean {
        val offset = state.offset.takeUnless { it.isNaN() } ?: return false
        // Offsets grow down the screen: `searchUpwards` looks at larger offsets, the heights below.
        val target =
            (if (abs(velocity) >= flingVelocity) state.anchors.closestAnchor(offset, searchUpwards = velocity > 0) else null)
                ?: state.anchors.closestAnchor(offset)
                ?: return false
        if (state.anchors.positionOf(target) == offset) return false
        state.animateTo(target)
        return true
    }
}
