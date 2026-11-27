package to.pubky.maps.ui

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.ui.unit.dp

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
 * The containers of what floats over the map: [control] for the search bar (and the area picker's bar
 * in its place) and the round buttons, [notice] for the lines saying what is missing (a step apart
 * from the controls above them), [card] for the card of a place opened from a link or search, and
 * [edge] around each of them and the area picker's card.
 *
 * Light, a white control and its shadow stand out from the light map, so there is no edge. Dark, a
 * shadow does not show, and the default dark styles' land ranges from nearly black (OpenFreeMap
 * Dark rgb(12,12,12)) to dark grey (VersaTiles Colorful Dark rgb(40,39,37)): a container tone
 * matched to one land fades on the other (the darkest container, #0F0D13, gave 1.01:1 on the first;
 * M3's High, #2B2930, 1.04:1 on the second). So dark draws a 1 dp outlineVariant edge, as Material
 * 3's outlined containers do: 2.09:1 and 1.60:1 on those lands. Over VersaTiles' woods, grass and
 * buildings it is fainter (1.3-1.5:1); the outline role (3.8:1 and more) would frame every control
 * in a bright line. Dark takes higher container roles than light: controls and card
 * surfaceContainerHigh, notices a step above them (surfaceContainerHighest), as in light.
 */
@Immutable
data class MapSurfaceStyle(
    val control: Color,
    val notice: Color,
    val card: Color,
    val edge: BorderStroke?,
)

private fun mapSurfaceStyle(
    scheme: ColorScheme,
    dark: Boolean,
) = if (dark) {
    MapSurfaceStyle(
        control = scheme.surfaceContainerHigh,
        notice = scheme.surfaceContainerHighest,
        card = scheme.surfaceContainerHigh,
        edge = BorderStroke(1.dp, scheme.outlineVariant),
    )
} else {
    MapSurfaceStyle(
        control = scheme.surfaceContainerLowest,
        notice = scheme.surfaceContainerHigh,
        card = scheme.surfaceContainerLow,
        edge = null,
    )
}

private val LocalMapSurfaceStyle = staticCompositionLocalOf { mapSurfaceStyle(LightColors, dark = false) }

/** The containers of the controls and notices over the map, for the current theme. */
val MapSurfaces: MapSurfaceStyle
    @Composable
    @ReadOnlyComposable
    get() = LocalMapSurfaceStyle.current

/** The app's Material 3 theme; light or dark follows the system. */
@Composable
fun PubkyMapsTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val scheme = if (darkTheme) DarkColors else LightColors
    CompositionLocalProvider(LocalMapSurfaceStyle provides mapSurfaceStyle(scheme, darkTheme)) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
