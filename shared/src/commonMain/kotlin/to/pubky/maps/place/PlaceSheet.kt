package to.pubky.maps.place

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/** The height of the drag handle strip at the top of the sheet. */
private val HandleHeight = 20.dp

/**
 * The place page on a phone: a sheet over the map with three heights ([SheetStop]). The handle and
 * the header drag it; scrolling the page up first raises the sheet to the top, scrolling down at the
 * top of the page lowers it; a tap on the handle steps it up (and from the top back to half).
 * [maxHeight] is the room it may take (under the search bar).
 */
@Composable
fun PlaceSheet(
    page: PlacePageState,
    context: PlacePageContext,
    actions: PlacePageActions,
    stop: SheetStop,
    onStopChange: (SheetStop) -> Unit,
    maxHeight: Dp,
    modifier: Modifier = Modifier,
    /** The system bar under the sheet: it adds to every stop, and the page scrolls clear of it. */
    bottomInset: Dp = 0.dp,
) {
    val density = LocalDensity.current
    var headerPx by remember { mutableIntStateOf(0) }
    val handlePx = with(density) { HandleHeight.toPx() }
    val maxPx = with(density) { maxHeight.toPx() }
    val insetPx = with(density) { bottomInset.toPx() }
    val peekPx = ((if (headerPx > 0) headerPx + handlePx else with(density) { 180.dp.toPx() }) + insetPx).coerceAtMost(maxPx)
    val stops = SheetStops(peek = peekPx, half = maxPx * 0.5f, full = maxPx)
    // The resting height, animated between stops; while a finger moves the sheet, [dragged].
    val resting = remember { Animatable(stops.height(stop)) }
    var dragged by remember { mutableStateOf<Float?>(null) }
    val target = stops.height(stop)
    LaunchedEffect(target) { if (dragged == null) resting.animateTo(target) }
    val height = dragged ?: resting.value
    val scope = rememberCoroutineScope()
    val currentStop by rememberUpdatedState(stop)
    val currentStops by rememberUpdatedState(stops)
    val currentOnStopChange by rememberUpdatedState(onStopChange)

    fun dragBy(delta: Float) {
        dragged = currentStops.clamp((dragged ?: resting.value) - delta)
    }

    fun release(velocityUp: Float) {
        val from = dragged ?: return
        val next = currentStops.settle(from, velocityUp)
        currentOnStopChange(next)
        // From where the finger left it, so the sheet does not jump back first.
        scope.launch {
            resting.snapTo(from)
            dragged = null
            resting.animateTo(currentStops.height(next))
        }
    }

    val dragState = rememberDraggableState { delta -> dragBy(delta) }
    val nestedScroll =
        remember {
            object : NestedScrollConnection {
                override fun onPreScroll(
                    available: Offset,
                    source: NestedScrollSource,
                ): Offset {
                    // Finger up: raise the sheet before the page scrolls.
                    val current = dragged ?: currentStops.height(currentStop)
                    if (available.y < 0 && current < currentStops.height(SheetStop.Full)) {
                        dragBy(available.y)
                        return Offset(0f, available.y)
                    }
                    return Offset.Zero
                }

                override fun onPostScroll(
                    consumed: Offset,
                    available: Offset,
                    source: NestedScrollSource,
                ): Offset {
                    // Finger down with the page at its top: lower the sheet.
                    if (available.y > 0 && source == NestedScrollSource.UserInput) {
                        dragBy(available.y)
                        return Offset(0f, available.y)
                    }
                    return Offset.Zero
                }

                override suspend fun onPreFling(available: Velocity): Velocity {
                    if (dragged == null) return Velocity.Zero
                    release(-available.y)
                    return available
                }
            }
        }

    Surface(
        modifier = modifier.fillMaxWidth().height(with(density) { height.toDp() }),
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 6.dp,
    ) {
        Column(Modifier.fillMaxSize()) {
            Column(
                Modifier
                    .draggable(
                        state = dragState,
                        orientation = Orientation.Vertical,
                        onDragStopped = { velocity -> release(-velocity) },
                    ).onSizeChanged { headerPx = it.height - handlePx.toInt() },
            ) {
                Handle(stop) { onStopChange(SheetStops.afterTap(stop)) }
                PlaceHeader(page, context, actions)
            }
            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f).nestedScroll(nestedScroll),
                contentPadding = PaddingValues(bottom = bottomInset),
            ) {
                placeSections(page, context, actions)
            }
        }
    }
}

@Composable
private fun Handle(
    stop: SheetStop,
    onTap: () -> Unit,
) {
    val label = if (stop == SheetStop.Full) "Show less of the place" else "Show more of the place"
    Box(
        Modifier
            .fillMaxWidth()
            .height(HandleHeight)
            .clickable(onClick = onTap)
            .semantics {
                contentDescription = label
                onClick(label = label) {
                    onTap()
                    true
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(width = 32.dp, height = 4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)),
        )
    }
}

/** The place page on a wide screen: a panel beside the map, the whole page scrolling. */
@Composable
fun PlacePanel(
    page: PlacePageState,
    context: PlacePageContext,
    actions: PlacePageActions,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 3.dp,
    ) {
        LazyColumn(Modifier.fillMaxSize()) {
            item(key = "header") { PlaceHeader(page, context, actions, modifier = Modifier.padding(top = 8.dp)) }
            placeSections(page, context, actions)
        }
    }
}
