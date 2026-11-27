package to.pubky.maps.android

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import to.pubky.maps.links.GeoPoint
import to.pubky.maps.place.PlacePageActions
import to.pubky.maps.place.PlacePageContext
import to.pubky.maps.place.PlacePageState
import to.pubky.maps.place.PlacePanel
import to.pubky.maps.place.PlaceRef
import to.pubky.maps.place.PlaceSource
import to.pubky.maps.place.RouteStartChoice
import to.pubky.maps.routing.Directions
import to.pubky.maps.routing.DirectionsUi
import to.pubky.maps.routing.Route
import to.pubky.maps.routing.RouteStart
import to.pubky.maps.routing.RouteStatus
import to.pubky.maps.routing.TravelMode
import to.pubky.maps.ui.PubkyMapsTheme

/**
 * "Start here" on a place page chooses the place as the start of directions to other places and
 * says so; directions from a chosen start name it and go back to the user's position with a tap.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp")
class StartHereTest {
    @get:Rule
    val compose = createComposeRule()

    private val context =
        PlacePageContext(
            now = LocalDateTime(LocalDate.parse("2026-11-25"), LocalTime(12, 0)),
            nowMillis = 1_795_608_000_000,
            timeZone = TimeZone.UTC,
            language = "en",
            distance = null,
        )

    private val kunsthaus = GeoPoint(47.3702, 8.5486)
    private val hotel = RouteStart("osm:node/9", GeoPoint(47.3667, 8.545), "Hotel Adler")

    private fun page(
        routeStart: RouteStartChoice?,
        directions: DirectionsUi? = null,
    ) {
        compose.setContent {
            PubkyMapsTheme(darkTheme = false) {
                PlacePanel(
                    PlacePageState(PlaceRef("osm:node/1", "Kunsthaus", kunsthaus, null, PlaceSource.Pinakes), loading = emptySet()),
                    context,
                    PlacePageActions(
                        onClose = {},
                        onShare = null,
                        openIn = emptyList(),
                        onOpenIn = {},
                        onOpenLink = {},
                        onLoadMorePosts = {},
                        onDirections = {},
                        directions = directions,
                        routeStart = routeStart,
                    ),
                )
            }
        }
    }

    @Test
    fun startHereChoosesThePlaceAndSaysWhatThatDoes() {
        var chosen by mutableStateOf(false)
        page(RouteStartChoice(chosen = false, onToggle = { chosen = !chosen }))
        compose.onNodeWithText("Directions to other places start here.").assertDoesNotExist()
        compose.onNodeWithText("Start here").performClick()
        assertEquals(true, chosen)
    }

    @Test
    fun aChosenStartReadsStartingHereAndATapClearsIt() {
        var taps = 0
        page(RouteStartChoice(chosen = true, onToggle = { taps += 1 }))
        compose.onNodeWithText("Directions to other places start here.").assertExists()
        compose.onNodeWithText("Starting here").performClick()
        assertEquals(1, taps)
    }

    @Test
    fun noStartHereWithoutRouting() {
        page(routeStart = null)
        compose.onNodeWithText("Start here").assertDoesNotExist()
    }

    @Test
    fun directionsFromAChosenStartNameItAndGoBackToTheUsersPosition() {
        var back = 0
        page(
            RouteStartChoice(chosen = false, onToggle = {}),
            DirectionsUi(
                Directions(
                    "osm:node/1",
                    kunsthaus,
                    TravelMode.Walk,
                    position = null,
                    status = RouteStatus.Found(Route(listOf(hotel.point, kunsthaus), 600.0, 700.0), "s"),
                    from = hotel,
                ),
                onMode = {},
                onSteps = {},
                onStep = {},
                onClose = {},
                onSwap = {},
                onRetry = {},
                onOpenSettings = null,
                credits = emptyList(),
                onFromYourLocation = { back += 1 },
            ),
        )
        compose.onNodeWithText("On foot from Hotel Adler").assertExists()
        compose
            .onNodeWithContentDescription("Swap start and destination")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "From Hotel Adler"))
        compose.onNode(hasText("From your location")).performClick()
        assertEquals(1, back)
    }

    @Test
    fun aRouteNotFoundFromAChosenStartStillHasTheWayBack() {
        var back = 0
        page(
            RouteStartChoice(chosen = false, onToggle = {}),
            DirectionsUi(
                Directions("osm:node/1", kunsthaus, TravelMode.Walk, position = null, status = RouteStatus.Unreachable, from = hotel),
                onMode = {},
                onSteps = {},
                onStep = {},
                onClose = {},
                onSwap = {},
                onRetry = {},
                onOpenSettings = null,
                credits = emptyList(),
                onFromYourLocation = { back += 1 },
            ),
        )
        compose.onNodeWithText("Try again").assertExists()
        compose.onNodeWithText("Steps").assertDoesNotExist()
        compose.onNodeWithText("From your location").performClick()
        assertEquals(1, back)
    }

    @Test
    fun directionsFromTheUsersPositionHaveNoWayBack() {
        page(
            RouteStartChoice(chosen = false, onToggle = {}),
            DirectionsUi(
                Directions(
                    "osm:node/1",
                    kunsthaus,
                    TravelMode.Walk,
                    position = GeoPoint(47.3779, 8.5403),
                    status = RouteStatus.Found(Route(listOf(kunsthaus, kunsthaus), 600.0, 700.0), "s"),
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
        )
        compose.onNodeWithText("On foot from your location").assertExists()
        compose.onNodeWithText("From your location").assertDoesNotExist()
    }
}
