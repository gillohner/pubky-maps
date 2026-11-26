package to.pubky.maps.map

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import to.pubky.maps.config.AttributionLink
import to.pubky.maps.ui.AppIcons

/** The area picker over the map: plain state, so screenshots need no map. */
data class AreaPickerUi(
    /** Whether the area in the frame fits in one download; null until the map has measured it. */
    val fits: Boolean?,
    /** The frame as last measured ([onFrame]); null until it has been. */
    val frame: DpRect? = null,
    /** The frame, in dp from the map's top start corner (the overlay covers the map exactly). */
    val onFrame: (DpRect) -> Unit = {},
    val onDownload: () -> Unit = {},
    val onCancel: () -> Unit = {},
)

/**
 * How far the map's top controls (scale bar, compass) start below the top of their box, which lies
 * [MapControlMargin] inside the safe area ([safeTop] being its top inset): under the search bar's place.
 * While the picker is open they start no higher than its [pickerFrame] (in dp from the map's top),
 * because a large font makes the picker's bar taller than the search bar and it would cover them (and
 * take the compass's taps). At the normal font the frame starts where they do.
 */
fun mapTopControlsPadding(
    pickerFrame: DpRect?,
    safeTop: Dp,
): Dp {
    val underSearchBar = TopControlSize + MapControlMargin
    return maxOf(underSearchBar, (pickerFrame?.top ?: 0.dp) - safeTop - MapControlMargin)
}

/**
 * Picking an area to download: the map stays movable under a frame, the rest of it shaded; a bar on
 * top says what to do and closes the picker, a card at the bottom says whether the area fits in one
 * download and starts it. The map's credits stay under the card.
 */
@Composable
internal fun AreaPickerOverlay(
    picker: AreaPickerUi,
    attribution: List<AttributionLink>,
) {
    var frame by remember { mutableStateOf<Rect?>(null) }
    val density = LocalDensity.current
    val shade = MaterialTheme.colorScheme.scrim.copy(alpha = 0.35f)
    // The frame is measured in the overlay, which lies exactly over the map; either can be placed
    // first, so each placement measures again.
    val placed = remember { arrayOfNulls<LayoutCoordinates>(2) }
    val onPicker by rememberUpdatedState(picker.onFrame)
    val measure = {
        val overlay = placed[0]?.takeIf { it.isAttached }
        val inFrame = placed[1]?.takeIf { it.isAttached }
        if (overlay != null && inFrame != null) {
            val bounds = overlay.localBoundingBoxOf(inFrame, clipBounds = false)
            frame = bounds
            with(density) { onPicker(DpRect(bounds.left.toDp(), bounds.top.toDp(), bounds.right.toDp(), bounds.bottom.toDp())) }
        }
    }
    Box(
        Modifier.fillMaxSize().onGloballyPositioned {
            placed[0] = it
            measure()
        },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val inside = frame ?: return@Canvas
            clipRect(inside.left, inside.top, inside.right, inside.bottom, ClipOp.Difference) { drawRect(shade) }
        }
        AreaPickerControls(picker, attribution) {
            placed[1] = it
            measure()
        }
    }
}

@Composable
private fun AreaPickerControls(
    picker: AreaPickerUi,
    attribution: List<AttributionLink>,
    onFramePlaced: (LayoutCoordinates) -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(MapControlMargin),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // As tall as the search bar it replaces, and taller when a large font wraps the instruction, so
        // no line is cut; the frame below takes what is left.
        Surface(
            modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth().heightIn(min = TopControlSize),
            shape = RoundedCornerShape(TopControlSize / 2),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shadowElevation = 3.dp,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = picker.onCancel) { Icon(AppIcons.Close, contentDescription = "Cancel") }
                Text(
                    "Move the map to frame the area",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f).padding(end = 16.dp, top = 4.dp, bottom = 4.dp).semantics { heading() },
                )
            }
        }
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(vertical = MapControlMargin, horizontal = 12.dp)
                .border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(4.dp))
                .onGloballyPositioned(onFramePlaced),
        )
        Surface(
            modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 3.dp,
        ) {
            Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp)) {
                Text("Download this area", style = MaterialTheme.typography.titleMedium)
                Text(
                    when (picker.fits) {
                        true -> "The map in the frame, light and dark, to use without a connection."
                        false -> "This area is too large for one download. Zoom in, or download it in parts."
                        null -> "Measuring the area…"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Row(
                    Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                ) {
                    TextButton(onClick = picker.onCancel) { Text("Cancel") }
                    Button(onClick = picker.onDownload, enabled = picker.fits == true) { Text("Download") }
                }
            }
        }
        AttributionBar(links = attribution, modifier = Modifier.align(Alignment.Start).padding(top = 8.dp))
    }
}
