package to.pubky.maps.android

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
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
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import to.pubky.maps.config.ServerDefaults
import to.pubky.maps.links.GeoPoint
import to.pubky.maps.links.LinkProvider
import to.pubky.maps.map.AreaPickerUi
import to.pubky.maps.map.MapScreen
import to.pubky.maps.map.MapSearch
import to.pubky.maps.place.PlaceCard
import to.pubky.maps.place.PlaceRef
import to.pubky.maps.place.PlaceSource
import to.pubky.maps.search.SearchActions
import to.pubky.maps.search.SearchState
import to.pubky.maps.status.ServicePart
import to.pubky.maps.ui.PubkyMapsTheme

/**
 * In dark mode every control, notice and card over the map, and the area picker's bar and card, has
 * an edge that stands out from the land of each default dark style: a shadow does not show on a dark
 * map, and those lands range from nearly black to dark grey, so the edge must carry the boundary. From
 * a point in each container's padding, where no icon or text is, the pixels are read outward across
 * its edge, and the most distinct one that is not the container's fill must reach 1.5:1 against the
 * land (without an edge: 1.36:1 on OpenFreeMap Dark, 1.04:1 on VersaTiles Colorful Dark for the
 * search bar and buttons). Runs once per default dark style.
 * Native graphics, so the pixels are the ones a phone draws.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MapSurfacesContrastTest(
    private val style: String,
    private val landArgb: Int,
) {
    @get:Rule
    val compose = createComposeRule()

    private val land = Color(landArgb)
    private val note = "The map could not load. Check your connection."
    private lateinit var density: Density

    private val place =
        PlaceRef(
            uri = "https://maps.app.goo.gl/AbCdEf123",
            name = "Zürich Hauptbahnhof",
            point = GeoPoint(47.37786, 8.54032),
            osm = null,
            source = PlaceSource.LinkText,
        )

    private fun showDark(
        placeCard: (@Composable () -> Unit)? = null,
        areaPicker: AreaPickerUi? = null,
    ) {
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
                    placeCard = placeCard,
                    areaPicker = areaPicker,
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

    /**
     * The colour most distinct from the land among the pixels from ([x], [y]) [span] outward, one pixel
     * a step ([dx], [dy]): across the container's padding, its edge and the map just outside. Pixels
     * of the container's own fill (as at the start) do not count, so a fill that stands out by itself
     * does not pass for an edge; with nothing past the fill but the map, the land itself.
     */
    private fun PixelMap.edgeFrom(
        x: Dp,
        y: Dp,
        dx: Int,
        dy: Int,
        span: Dp,
    ): Color =
        with(density) {
            val x0 = x.roundToPx()
            val y0 = y.roundToPx()
            val fill = this@edgeFrom[x0, y0]
            (0..span.roundToPx())
                .map { i -> Pair(x0 + i * dx, y0 + i * dy) }
                .filter { (px, py) -> px in 0 until width && py in 0 until height }
                .map { (px, py) -> this@edgeFrom[px, py] }
                .filter { contrast(it, fill) >= 1.1f }
                .maxByOrNull { contrast(it, land) } ?: land
        }

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
    fun everyControlAndNoticeOverTheDarkMapHasAnEdgeThatStandsOutFromItsLand() {
        showDark()
        val screen = compose.onRoot().captureToImage().toPixelMap()
        val settings = compose.onNodeWithContentDescription("Settings").getUnclippedBoundsInRoot()
        val location = compose.onNodeWithContentDescription("Show my location").getUnclippedBoundsInRoot()
        val hint = compose.onNodeWithText("Search here", useUnmergedTree = true).getUnclippedBoundsInRoot()
        val notice = compose.onNodeWithText(note, useUnmergedTree = true).getUnclippedBoundsInRoot()
        val banner = compose.onNodeWithText(ServicePart.Homeserver.missingText, useUnmergedTree = true).getUnclippedBoundsInRoot()
        val map = screen.at(8.dp, compose.onRoot().getUnclippedBoundsInRoot().middleY())
        assertTrue("the sample of the map is its land ($map)", contrast(map, land) < 1.01f)
        val edges =
            mapOf(
                // The round buttons from 4 dp inside their left edge, on the icon's line (the icon is
                // 24 dp in the middle), leftward to 4 dp outside.
                "Settings button" to screen.edgeFrom(settings.left + 4.dp, settings.middleY(), -1, 0, 8.dp),
                "My location button" to screen.edgeFrom(location.left + 4.dp, location.middleY(), -1, 0, 8.dp),
                // The search bar past its hint, up across its top edge (the bar is 48 dp, the hint in its
                // middle) to the screen's top.
                "search bar" to screen.edgeFrom(hint.right + 16.dp, hint.middleY(), 0, -1, 40.dp),
                // The notice from its 16 dp start padding, leftward across its edge.
                "'could not load' notice" to screen.edgeFrom(notice.left - 8.dp, notice.middleY(), -1, 0, 16.dp),
                // The banner from just above its text, up across its 8 dp top padding and edge to 2 dp
                // above it (its icon is before the text; the notice is 8 dp above).
                "missing-service banner" to screen.edgeFrom(banner.left, banner.top - 2.dp, 0, -1, 8.dp),
            )
        edges.forEach { (what, colour) -> assertStandsOut(what, colour) }
        // As in light, a notice is a step apart from the controls above it, here lighter.
        val control = screen.at(hint.right + 16.dp, hint.middleY())
        val notices =
            mapOf(
                "'could not load' notice" to screen.at(notice.left - 8.dp, notice.middleY()),
                "missing-service banner" to screen.at(banner.left - 4.dp, banner.middleY()),
            )
        notices.forEach { (what, colour) ->
            assertTrue("the $what ($colour) is lighter than the controls ($control)", colour.luminance() > control.luminance())
        }
    }

    @Test
    @Config(qualifiers = "w412dp-h915dp-night")
    fun thePlaceCardOverTheDarkMapHasAnEdgeThatStandsOutFromItsLand() {
        showDark(placeCard = { PlaceCard(place = place, provider = LinkProvider.Google, onShare = {}, onClose = {}) })
        val screen = compose.onRoot().captureToImage().toPixelMap()
        val title = compose.onNodeWithText("Zürich Hauptbahnhof", useUnmergedTree = true).getUnclippedBoundsInRoot()
        // From the card's 16 dp start padding, on the name's line, leftward across its edge.
        assertStandsOut("place card", screen.edgeFrom(title.left - 8.dp, title.middleY(), -1, 0, 16.dp))
    }

    @Test
    @Config(qualifiers = "w412dp-h915dp-night")
    fun theAreaPickersBarAndCardOverTheDarkMapHaveAnEdgeThatStandsOutFromItsLand() {
        showDark(areaPicker = AreaPickerUi(fits = true))
        val screen = compose.onRoot().captureToImage().toPixelMap()
        val cancel = compose.onNodeWithContentDescription("Cancel").getUnclippedBoundsInRoot()
        val title = compose.onNodeWithText("Download this area", useUnmergedTree = true).getUnclippedBoundsInRoot()
        // The map around them is shaded, darker than its land, so the land is the stricter measure.
        // The bar from 4 dp inside its Cancel button (the icon is 24 dp in its middle), leftward
        // across the bar's start edge.
        assertStandsOut("area picker's bar", screen.edgeFrom(cancel.left + 4.dp, cancel.middleY(), -1, 0, 8.dp))
        // The card from its 16 dp start padding, on the title's line, leftward across its edge.
        assertStandsOut("area picker's card", screen.edgeFrom(title.left - 8.dp, title.middleY(), -1, 0, 16.dp))
    }

    private fun assertStandsOut(
        what: String,
        colour: Color,
    ) {
        val ratio = contrast(colour, land)
        assertTrue("on $style, the $what's edge ($colour) stands out from the land: $ratio:1, needs 1.5:1", ratio >= 1.5f)
    }

    companion object {
        /**
         * The land (background layer) of each default dark style, from its style.json. The names must
         * be the default list's, so a new default dark style needs its land here.
         */
        private val lands =
            mapOf(
                "OpenFreeMap Dark" to Color(0xFF0C0C0C),
                "VersaTiles Colorful Dark" to Color(0xFF282725),
            )

        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun styles(): List<Array<Any>> {
            val defaults = ServerDefaults.lists.mapStyles(darkTheme = true).map { it.name }
            check(defaults == lands.keys.toList()) { "the default dark styles $defaults need their land in this test" }
            return lands.map { (name, colour) -> arrayOf(name, colour.toArgb()) }
        }
    }
}
