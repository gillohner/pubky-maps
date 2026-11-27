package to.pubky.maps.android

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import to.pubky.maps.links.GeoPoint
import to.pubky.maps.place.PlaceHeader
import to.pubky.maps.place.PlacePageActions
import to.pubky.maps.place.PlacePageContext
import to.pubky.maps.place.PlacePageState
import to.pubky.maps.place.PlaceRef
import to.pubky.maps.place.PlaceSource
import to.pubky.maps.routing.Directions
import to.pubky.maps.routing.DirectionsUi
import to.pubky.maps.routing.Route
import to.pubky.maps.routing.RouteStatus
import to.pubky.maps.routing.TravelMode
import to.pubky.maps.ui.PubkyMapsTheme

/**
 * The route card on a phone at twice the font size: every travel mode is shown whole (none cut at
 * the card's edge, as a row that scrolls sideways would cut "Walk" to "Wa"), and the swap button sits
 * beside the line that says which way the route goes, not in the modes' row.
 */
@RunWith(RobolectricTestRunner::class)
// Real text measuring: the default graphics give every font size about the same width.
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp")
class RouteCardLargeFontTest {
    @get:Rule
    val compose = createComposeRule()

    private val here = GeoPoint(47.3779, 8.5403)
    private val kunsthaus = GeoPoint(47.3702, 8.5486)

    /** The route card's travel modes, as their chips read. */
    private val modes = listOf("Car", "Bike", "Walk")

    private fun card(fontScale: Float) {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                PubkyMapsTheme(darkTheme = false) {
                    PlaceHeader(
                        PlacePageState(PlaceRef("osm:node/1", "Kunsthaus", kunsthaus, null, PlaceSource.Pinakes), loading = emptySet()),
                        PlacePageContext(
                            now = LocalDateTime(LocalDate.parse("2026-11-25"), LocalTime(12, 0)),
                            nowMillis = 1_795_608_000_000,
                            timeZone = TimeZone.UTC,
                            language = "en",
                            distance = "850 m",
                        ),
                        PlacePageActions(
                            onClose = {},
                            onShare = null,
                            openIn = emptyList(),
                            onOpenIn = {},
                            onOpenLink = {},
                            onLoadMorePosts = {},
                            directions =
                                DirectionsUi(
                                    Directions(
                                        "osm:node/1",
                                        kunsthaus,
                                        TravelMode.Car,
                                        here,
                                        RouteStatus.Found(Route(listOf(here, kunsthaus), 289.0, 1401.0), "s"),
                                    ),
                                    onMode = {},
                                    onSteps = {},
                                    onStep = {},
                                    onClose = {},
                                    onSwap = {},
                                    onRetry = {},
                                    onOpenSettings = null,
                                    credits = emptyList(),
                                    onFromYourLocation = {},
                                ),
                        ),
                    )
                }
            }
        }
    }

    private fun node(text: String): SemanticsNode = compose.onNodeWithText(text).fetchSemanticsNode()

    /** The part of the chip the user sees (its bounds cut by its parents) is the whole chip. */
    private fun assertWhole(label: String) {
        val chip = node(label)
        assertEquals("shown width of $label", chip.size.width.toFloat(), chip.boundsInRoot.width, 0.5f)
    }

    @Test
    fun everyModeIsShownWholeAtTwiceTheFontSize() {
        card(fontScale = 2f)
        for (mode in modes) assertWhole(mode)
    }

    @Test
    fun theSwapButtonSitsBesideTheWayLineAtTwiceTheFontSize() {
        card(fontScale = 2f)
        val swap = compose.onNodeWithContentDescription("Swap start and destination").fetchSemanticsNode().boundsInRoot
        val time = node("5 min · 1.4 km").boundsInRoot
        val way = node("By car from your location").boundsInRoot
        val modesBottom = modes.maxOf { node(it).boundsInRoot.bottom }
        assertTrue("swap $swap under the modes ending at $modesBottom", swap.top >= modesBottom)
        assertTrue("swap $swap beside the time $time and the way $way", swap.center.y in time.top..way.bottom)
        assertTrue("swap $swap right of the way line $way", swap.left >= way.right)
    }

    @Test
    fun theModesKeepOneRowAtTheNormalFontSize() {
        card(fontScale = 1f)
        val tops = modes.map { node(it).boundsInRoot.top }.toSet()
        assertEquals("one row: $tops", 1, tops.size)
    }
}
