package to.pubky.maps.map

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import to.pubky.maps.ui.AppIcons
import to.pubky.maps.write.WriteIcons

/** The layers the map can draw over its base map, as the user switched them: plain state, for screenshots. */
data class MapLayersUi(
    /** Where Panoramax has street-level photos. */
    val streetPhotos: Boolean,
    /** The street-level photo layer is on, but no Panoramax server of the list answered. */
    val streetPhotosUnreachable: Boolean = false,
    val onStreetPhotosChange: (Boolean) -> Unit,
    /** Asks the Panoramax servers again after none answered. */
    val onRetryStreetPhotos: () -> Unit = {},
)

/**
 * The map's layers, as Google Maps' Layers sheet: a panel at the bottom over a shaded map (a tap on
 * the shade, Close or Back closes it), one switch per layer. Its width stops at Material's 640 dp for
 * a bottom sheet, so on a tablet it stays a panel in the middle and the map shows on both sides.
 */
@Composable
internal fun MapLayersPanel(
    ui: MapLayersUi,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxSize()) {
        // The shade: a tap closes the panel. TalkBack closes it with Close or Back, so it is no stop.
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.32f))
                .pointerInput(onClose) { detectTapGestures { onClose() } }
                .clearAndSetSemantics {},
        )
        Surface(
            modifier =
                Modifier
                    .align(Alignment.BottomCenter)
                    .widthIn(max = 640.dp)
                    .fillMaxWidth()
                    .semantics { paneTitle = "Map layers" },
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            shadowElevation = 3.dp,
        ) {
            Column(Modifier.windowInsetsPadding(WindowInsets.navigationBars).padding(bottom = 16.dp)) {
                Row(
                    Modifier.padding(start = 24.dp, end = 12.dp, top = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Map layers",
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.weight(1f).semantics { heading() },
                    )
                    IconButton(onClick = onClose) { Icon(AppIcons.Close, contentDescription = "Close") }
                }
                StreetPhotosLayerRow(ui)
            }
        }
    }
}

/** The street-level photo layer's switch: TalkBack reads it as the switch "Street-level photos", on or off. */
@Composable
private fun StreetPhotosLayerRow(ui: MapLayersUi) {
    val unreachable = ui.streetPhotos && ui.streetPhotosUnreachable
    ListItem(
        leadingContent = {
            Box(
                Modifier.size(40.dp).background(StreetPhotoColor, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(WriteIcons.Camera, contentDescription = null, tint = Color.White, modifier = Modifier.size(22.dp))
            }
        },
        headlineContent = { Text("Street-level photos") },
        supportingContent = {
            Text(if (unreachable) "Can't reach Panoramax" else "Where Panoramax has photos of the streets")
        },
        trailingContent = { Switch(checked = ui.streetPhotos, onCheckedChange = null) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier =
            Modifier
                .padding(horizontal = 8.dp)
                .toggleable(value = ui.streetPhotos, role = Role.Switch, onValueChange = ui.onStreetPhotosChange),
    )
    if (unreachable) {
        TextButton(onClick = ui.onRetryStreetPhotos, modifier = Modifier.padding(start = 76.dp)) { Text("Try again") }
    }
}
