package to.pubky.maps.place

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import to.pubky.maps.ui.MapSurfaces
import to.pubky.maps.ui.SheetFlingVelocity

/** The height of the drag handle strip at the top of the sheet. */
private val HandleHeight = 20.dp

/**
 * The place page on a phone: a sheet over the map with three heights ([SheetStop]). The handle and
 * the header drag it; scrolling the page up first raises the sheet to the top, scrolling down at the
 * top of the page lowers it; a release settles at the nearest stop, a flick ([SheetFlingVelocity])
 * at the next one in its direction; a tap on the handle steps it up (and from the top back to half).
 * [maxHeight] is the room it may take (under the search bar).
 *
 * The header stays in place over the scrolling page while it and the handle take at most half of
 * [maxHeight]. A taller one (a large font in split screen) scrolls with the page, as [PlacePanel]'s
 * does: kept in place it would leave the page a strip, or no room at all, and the sections could not
 * be reached. Below the top stop that page is back at its start, so the lowest stop shows the header.
 * [onDragStart] is told when a finger starts moving the sheet, before it lands on a stop.
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
    onDragStart: () -> Unit = {},
) {
    val density = LocalDensity.current
    var headerPx by remember { mutableIntStateOf(0) }
    val handlePx = with(density) { HandleHeight.toPx() }
    val maxPx = with(density) { maxHeight.toPx() }
    val insetPx = with(density) { bottomInset.toPx() }
    val flingVelocity = with(density) { SheetFlingVelocity.toPx() }
    val peekPx = ((if (headerPx > 0) headerPx + handlePx else with(density) { 180.dp.toPx() }) + insetPx).coerceAtMost(maxPx)
    // The top stop is no taller than the page: a short page leaves no empty sheet under its end. While
    // the card loads, the page's length is not known yet (a few grey lines stand for the photo, the
    // description and the details), so the sheet takes its usual heights: half stays half of the room
    // instead of growing by the card when it arrives, and the map left in view, where a picked place is
    // centred, does not change under it. A short page shrinks once its card is in or has failed.
    val cardLoading = page.cardLoading
    var contentPx by remember { mutableIntStateOf(0) }
    val fullPx =
        if (!cardLoading && contentPx > 0 && headerPx > 0) (headerPx + handlePx + contentPx).coerceAtMost(maxPx) else maxPx
    val stops = SheetStops(peek = peekPx, half = (maxPx * 0.5f).coerceAtMost(fullPx), full = fullPx)
    // headerPx is the header's own height in both places, so moving it does not change the answer.
    val headerScrolls = headerPx > 0 && headerPx + handlePx > maxPx * 0.5f
    val pageScroll = rememberScrollState()
    LaunchedEffect(stop, headerScrolls) {
        if (headerScrolls && stop != SheetStop.Full) pageScroll.animateScrollTo(0)
    }
    // The resting height, animated between stops; while a finger moves the sheet, [dragged].
    val resting = remember { Animatable(stops.height(stop)) }
    var dragged by remember { mutableStateOf<Float?>(null) }
    val target = stops.height(stop)
    // A new stop animates; the same stop growing or shrinking with its content follows it at once.
    var shownStop by remember { mutableStateOf(stop) }
    LaunchedEffect(target, stop) {
        if (dragged != null) return@LaunchedEffect
        // A release animating to its stop goes on animating (the stop change restarts this effect).
        if (stop == shownStop && !resting.isRunning) resting.snapTo(target) else resting.animateTo(target)
        shownStop = stop
    }
    // Never taller than the page itself, also in the frame where the page has just become shorter.
    val height = (dragged ?: resting.value).coerceAtMost(stops.height(SheetStop.Full))
    val scope = rememberCoroutineScope()
    val currentStop by rememberUpdatedState(stop)
    val currentStops by rememberUpdatedState(stops)
    val currentFlingVelocity by rememberUpdatedState(flingVelocity)
    val currentOnStopChange by rememberUpdatedState(onStopChange)
    val currentOnDragStart by rememberUpdatedState(onDragStart)
    // Opening the route's steps raises the sheet to the top, where they are listed under the header:
    // at the lower stops the header with its route card takes about all the sheet shows, so the list
    // would open out of sight. Lowering the sheet again keeps them open and brings back the map.
    // A step tapped at the top lowers the sheet to its lowest stop, as Google Maps' steps give way to
    // the map: the map shows the step, and the header's route card shows it with the way to the
    // previous and the next one (half would show about the header too, with the list out of sight).
    // Remembered: the sheet recomposes on every frame of a drag, and new actions would recompose the page.
    val sheetActions =
        remember(actions) {
            val ui = actions.directions ?: return@remember actions
            actions.copy(
                directions =
                    ui.copy(
                        onSteps = { shown ->
                            ui.onSteps(shown)
                            if (shown && currentStop != SheetStop.Full) currentOnStopChange(SheetStop.Full)
                        },
                        onStep = { index ->
                            ui.onStep(index)
                            if (currentStop == SheetStop.Full) currentOnStopChange(SheetStop.Peek)
                        },
                    ),
            )
        }

    fun dragBy(delta: Float) {
        if (dragged == null) currentOnDragStart()
        dragged = currentStops.clamp((dragged ?: resting.value) - delta)
    }

    fun release(velocityUp: Float) {
        val from = dragged ?: return
        val next = currentStops.settle(from, velocityUp, currentFlingVelocity)
        currentOnStopChange(next)
        // From where the finger left it, so the sheet does not jump back first.
        scope.launch {
            resting.snapTo(from)
            dragged = null
            shownStop = next
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
                    // Measured at its own height, not the sheet's: the peek is set from this size, so a
                    // header measured inside a shorter sheet would keep the peek too short to show it.
                    .wrapContentHeight(Alignment.Top, unbounded = true)
                    .draggable(
                        state = dragState,
                        orientation = Orientation.Vertical,
                        onDragStopped = { velocity -> release(-velocity) },
                    ),
            ) {
                Handle(stop) { onStopChange(stops.afterTap(stop)) }
                if (!headerScrolls) {
                    PlaceHeader(
                        page,
                        context,
                        sheetActions,
                        Modifier.onSizeChanged { headerPx = it.height },
                        iconActions = true,
                    )
                }
            }
            Column(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .nestedScroll(nestedScroll)
                    .verticalScroll(pageScroll),
            ) {
                // Dragged by the page's own scrolling (the nested scroll above), as the sections are.
                if (headerScrolls) {
                    PlaceHeader(
                        page,
                        context,
                        sheetActions,
                        Modifier.onSizeChanged { headerPx = it.height },
                        iconActions = true,
                    )
                }
                PlaceSections(
                    page,
                    context,
                    sheetActions,
                    Modifier
                        // The scroll viewport's minimum height is not the page's: measure the page itself.
                        .wrapContentHeight(Alignment.Top)
                        .onSizeChanged { contentPx = it.height }
                        .padding(bottom = bottomInset),
                )
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

/**
 * The place page on a wide screen: a panel beside the map, the whole page scrolling. It floats over
 * the map like the search bar above it, so it takes the same edge in dark ([MapSurfaces]), where its
 * shadow does not show and its `surface` is 1.05:1 on OpenFreeMap Dark's land (1.25:1 on VersaTiles').
 */
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
        border = MapSurfaces.edge,
        shadowElevation = 3.dp,
    ) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            PlaceHeader(page, context, actions, modifier = Modifier.padding(top = 8.dp))
            PlaceSections(page, context, actions)
        }
    }
}
