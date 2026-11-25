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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** How much of the results list shows: its first row, half the screen, or all of it. */
enum class SheetHeight { Peek, Half, Full }

/** The handle and the first row: what the sheet keeps on screen when pulled down. */
private val PeekHeight = 120.dp

/**
 * The results over the map on a phone, as in Google Maps and CoMaps: a sheet at the bottom that the
 * handle drags between its first row ([SheetHeight.Peek]), half the screen ([SheetHeight.Half], where
 * it opens) and the whole height below the search bar ([SheetHeight.Full]); tapping the handle steps
 * up, and back down from full. [above] (the credits and my-location button) rides on the sheet's top
 * edge and hides when the sheet is full. [topSpace] is left free for the search bar.
 */
@Composable
internal fun ResultsSheet(
    height: Dp,
    topSpace: Dp,
    above: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val anchors =
        with(density) {
            val total = height.toPx()
            DraggableAnchors {
                SheetHeight.Full at topSpace.toPx()
                SheetHeight.Half at total / 2
                SheetHeight.Peek at total - PeekHeight.toPx()
            }
        }
    val state = remember(anchors) { AnchoredDraggableState(SheetHeight.Half, anchors) }
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
                { Box(Modifier.alpha(if (state.currentValue == SheetHeight.Full) 0f else 1f)) { above() } },
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
                                    .height(24.dp)
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
                            content()
                        }
                    }
                },
            ),
        modifier = modifier,
    ) { (aboveMeasurables, sheetMeasurables), constraints ->
        val offset = state.offset.takeUnless { it.isNaN() }?.roundToInt() ?: (constraints.maxHeight / 2)
        val sheetHeight = (constraints.maxHeight - offset).coerceAtLeast(0)
        val sheet = sheetMeasurables.first().measure(Constraints.fixed(constraints.maxWidth, sheetHeight))
        val aboveSheet = aboveMeasurables.first().measure(constraints.copy(minHeight = 0, minWidth = 0))
        layout(constraints.maxWidth, constraints.maxHeight) {
            aboveSheet.place(0, offset - aboveSheet.height)
            sheet.place(0, offset)
        }
    }
}
