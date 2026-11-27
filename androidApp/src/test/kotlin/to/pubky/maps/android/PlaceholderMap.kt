package to.pubky.maps.android

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import to.pubky.maps.config.ServerDefaults
import to.pubky.maps.map.MapScreen

/**
 * The colour standing in for the map in screenshots, the land of the default styles: light near
 * OpenFreeMap Liberty's #F8F4F0, dark OpenFreeMap Dark's rgb(12,12,12). So the shots show how the
 * controls and notices stand out from the map users see (a grey dark placeholder hid near-black ones).
 */
internal fun placeholderMapColor(dark: Boolean): Color = if (dark) Color(0xFF0C0C0C) else Color(0xFFF2EFE9)

/** The map with [card] over a flat placeholder where MapLibre would draw. */
@Composable
internal fun PlaceholderMap(card: (@Composable () -> Unit)?) {
    val dark = isSystemInDarkTheme()
    MapScreen(
        attribution =
            ServerDefaults.lists
                .mapStyles(darkTheme = false)
                .first()
                .attribution,
        mapUnavailable = false,
        onRetryMap = {},
        onMyLocation = {},
        onOpenSettings = {},
        placeCard = card,
    ) {
        Box(
            modifier = Modifier.fillMaxSize().background(placeholderMapColor(dark)),
            contentAlignment = Alignment.Center,
        ) {
            Text("Map", color = if (dark) Color(0xFF8A8D93) else Color(0xFF9A968E))
        }
    }
}
