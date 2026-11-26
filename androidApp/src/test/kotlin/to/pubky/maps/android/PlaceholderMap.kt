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
            modifier = Modifier.fillMaxSize().background(if (dark) Color(0xFF2B2D31) else Color(0xFFF2EFE9)),
            contentAlignment = Alignment.Center,
        ) {
            Text("Map", color = if (dark) Color(0xFF8A8D93) else Color(0xFF9A968E))
        }
    }
}
