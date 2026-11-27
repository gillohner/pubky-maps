package to.pubky.maps.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

private val LightColors =
    lightColorScheme(
        primary = Color(0xFF0B57D0),
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFFD3E3FD),
        onPrimaryContainer = Color(0xFF041E49),
    )

private val DarkColors =
    darkColorScheme(
        primary = Color(0xFFA8C7FA),
        onPrimary = Color(0xFF062E6F),
        primaryContainer = Color(0xFF0842A0),
        onPrimaryContainer = Color(0xFFD3E3FD),
    )

/**
 * The containers of what floats over the map: [control] for the search bar and the round buttons,
 * [notice] for the lines saying what is missing (a step apart from the controls above them), [card]
 * for the card of a place opened from a link or search.
 *
 * Light, a white control and its shadow stand out from the light map. Dark, a shadow does not show
 * and the dark styles' land is nearly black (OpenFreeMap Dark rgb(12,12,12)), so the darkest
 * container (#0F0D13) left only the icons visible (1.01:1); a lighter tone carries the edge instead,
 * as in Google Maps' and Material 3's dark themes: control and card 1.36:1, notice 1.59:1 on that
 * land (the card's light container, #1D1B20 dark, gave 1.15:1).
 */
@Immutable
data class MapSurfaceColors(
    val control: Color,
    val notice: Color,
    val card: Color,
)

private fun mapSurfaceColors(
    scheme: ColorScheme,
    dark: Boolean,
) = if (dark) {
    MapSurfaceColors(control = scheme.surfaceContainerHigh, notice = scheme.surfaceContainerHighest, card = scheme.surfaceContainerHigh)
} else {
    MapSurfaceColors(control = scheme.surfaceContainerLowest, notice = scheme.surfaceContainerHigh, card = scheme.surfaceContainerLow)
}

private val LocalMapSurfaceColors = staticCompositionLocalOf { mapSurfaceColors(LightColors, dark = false) }

/** The containers of the controls and notices over the map, for the current theme. */
val MapSurfaces: MapSurfaceColors
    @Composable
    @ReadOnlyComposable
    get() = LocalMapSurfaceColors.current

/** The app's Material 3 theme; light or dark follows the system. */
@Composable
fun PubkyMapsTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val scheme = if (darkTheme) DarkColors else LightColors
    CompositionLocalProvider(LocalMapSurfaceColors provides mapSurfaceColors(scheme, darkTheme)) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
