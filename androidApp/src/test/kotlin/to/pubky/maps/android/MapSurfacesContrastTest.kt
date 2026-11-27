package to.pubky.maps.android

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import to.pubky.maps.config.ServerDefaults
import to.pubky.maps.map.MapScreen
import to.pubky.maps.map.MapSearch
import to.pubky.maps.search.SearchActions
import to.pubky.maps.search.SearchState
import to.pubky.maps.status.ServicePart
import to.pubky.maps.ui.PubkyMapsTheme

/**
 * In dark mode every control and notice over the map is told from the map by its tone: a shadow does
 * not show on a dark map, and the default dark style's land is nearly black. Each container is read
 * from the drawn screen, a few dp inside its edge where no icon or text is, over the dark style's
 * land, and must reach 1.3:1 against it (a visible boundary; the near-black container gave 1.01:1).
 * Native graphics, so the pixels are the ones a phone draws.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MapSurfacesContrastTest {
    @get:Rule
    val compose = createComposeRule()

    private val land = placeholderMapColor(dark = true)
    private val note = "The map could not load. Check your connection."
    private lateinit var density: Density

    private fun showDark() {
        compose.setContent {
            density = LocalDensity.current
            PubkyMapsTheme(darkTheme = true) {
                MapScreen(
                    attribution =
                        ServerDefaults.lists
                            .mapStyles(darkTheme = true)
                            .first()
                            .attribution,
                    mapUnavailable = true,
                    onRetryMap = {},
                    onMyLocation = {},
                    onOpenSettings = {},
                    missing = setOf(ServicePart.Homeserver),
                    search = MapSearch(SearchState(), recents = emptyList(), userLocation = null, actions = SearchActions()),
                ) { Box(Modifier.fillMaxSize().background(land)) }
            }
        }
        compose.waitForIdle()
    }

    private fun PixelMap.at(
        x: Dp,
        y: Dp,
    ): Color = with(density) { this@at[x.roundToPx(), y.roundToPx()] }

    private fun contrast(
        a: Color,
        b: Color,
    ): Float {
        val (dark, light) = listOf(a.luminance(), b.luminance()).sorted()
        return (light + 0.05f) / (dark + 0.05f)
    }

    private fun DpRect.middleY() = (top + bottom) / 2

    @Test
    @Config(qualifiers = "w412dp-h915dp-night")
    fun everyControlAndNoticeOverTheDarkMapStandsOutFromItsLand() {
        showDark()
        val screen = compose.onRoot().captureToImage().toPixelMap()
        val settings = compose.onNodeWithContentDescription("Settings").getUnclippedBoundsInRoot()
        val location = compose.onNodeWithContentDescription("Show my location").getUnclippedBoundsInRoot()
        val hint = compose.onNodeWithText("Search here", useUnmergedTree = true).getUnclippedBoundsInRoot()
        val notice = compose.onNodeWithText(note, useUnmergedTree = true).getUnclippedBoundsInRoot()
        val banner = compose.onNodeWithText(ServicePart.Homeserver.missingText, useUnmergedTree = true).getUnclippedBoundsInRoot()
        val samples =
            mapOf(
                // The round buttons 4 dp inside their left edge, on the icon's line (the icon is 24 dp in the middle).
                "Settings button" to screen.at(settings.left + 4.dp, settings.middleY()),
                "My location button" to screen.at(location.left + 4.dp, location.middleY()),
                // The search bar past its hint, before the Settings button.
                "search bar" to screen.at(hint.right + 16.dp, hint.middleY()),
                // The notices in the padding before their text (16 dp; the banner's icon-text gap is 8 dp).
                "'could not load' notice" to screen.at(notice.left - 8.dp, notice.middleY()),
                "missing-service banner" to screen.at(banner.left - 4.dp, banner.middleY()),
            )
        val map = screen.at(8.dp, compose.onRoot().getUnclippedBoundsInRoot().middleY())
        assertTrue("the sample of the map is its land ($map)", contrast(map, land) < 1.01f)
        samples.forEach { (what, colour) ->
            val ratio = contrast(colour, land)
            assertTrue("the $what ($colour) stands out from the dark map's land: $ratio:1, needs 1.3:1", ratio >= 1.3f)
        }
    }
}
