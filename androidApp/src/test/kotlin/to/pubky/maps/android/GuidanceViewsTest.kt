package to.pubky.maps.android

import androidx.compose.ui.test.junit4.createComposeRule
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
import to.pubky.maps.config.ServerDefaults
import to.pubky.maps.links.GeoPoint
import to.pubky.maps.map.routeCredits
import to.pubky.maps.place.PlaceHeader
import to.pubky.maps.place.PlacePageActions
import to.pubky.maps.place.PlacePageContext
import to.pubky.maps.place.PlacePageState
import to.pubky.maps.place.PlaceRef
import to.pubky.maps.place.PlaceSource
import to.pubky.maps.routing.Directions
import to.pubky.maps.routing.DirectionsUi
import to.pubky.maps.routing.GuidanceBanner
import to.pubky.maps.routing.GuidanceBar
import to.pubky.maps.routing.GuidanceUi
import to.pubky.maps.routing.Route
import to.pubky.maps.routing.RouteProgress
import to.pubky.maps.routing.RouteStart
import to.pubky.maps.routing.RouteStatus
import to.pubky.maps.routing.TravelMode
import to.pubky.maps.ui.PubkyMapsTheme

/**
 * Start on a found route's card (only for a route from the user's position to the place), and what
 * the guidance banner and bar do: End, Find a new route off the route, Re-centre once the user moved
 * the map, the routing server's credits.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp")
class GuidanceViewsTest {
    @get:Rule
    val compose = createComposeRule()

    private val hb = GeoPoint(47.3779, 8.5403)
    private val kunsthaus = GeoPoint(47.3702, 8.5486)
    private val found = RouteStatus.Found(Route(listOf(hb, kunsthaus), seconds = 290.0, meters = 1401.0), "https://v.test")

    private fun card(
        directions: Directions,
        onStart: () -> Unit,
    ) {
        compose.setContent {
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
                                directions,
                                onMode = {},
                                onSteps = {},
                                onStep = {},
                                onClose = {},
                                onSwap = {},
                                onRetry = {},
                                onOpenSettings = {},
                                credits = emptyList(),
                                onFromYourLocation = {},
                                onStart = onStart,
                            ),
                    ),
                )
            }
        }
    }

    @Test
    fun startOnARouteFromTheUsersPositionStartsGuidance() {
        var started = 0
        card(Directions("osm:node/1", kunsthaus, TravelMode.Car, hb, found)) { started++ }
        compose.onNodeWithText("Start").performClick()
        assertEquals(1, started)
    }

    @Test
    fun aRouteBackToTheUserHasNoStart() {
        card(Directions("osm:node/1", kunsthaus, TravelMode.Car, hb, found, reversed = true)) {}
        compose.onNodeWithText("By car to your location").assertExists()
        compose.onNodeWithText("Start").assertDoesNotExist()
    }

    @Test
    fun aRouteFromAChosenStartHasNoStart() {
        card(Directions("osm:node/1", kunsthaus, TravelMode.Car, hb, found, from = RouteStart("osm:node/9", hb, "Hotel Adler"))) {}
        compose.onNodeWithText("From your location").assertExists()
        compose.onNodeWithText("Start").assertDoesNotExist()
    }

    private var ended = 0
    private var again = 0
    private var recentred = 0

    private fun guiding(
        progress: RouteProgress,
        following: Boolean,
        rerouting: Boolean = false,
        noNewRoute: RouteStatus? = null,
    ) {
        compose.setContent {
            PubkyMapsTheme(darkTheme = false) {
                val ui =
                    GuidanceUi(
                        Directions(
                            "osm:node/1",
                            kunsthaus,
                            TravelMode.Car,
                            hb,
                            found,
                            guiding = true,
                            progress = progress,
                            rerouting = rerouting,
                            noNewRoute = noNewRoute,
                        ),
                        placeName = "Kunsthaus",
                        arrival = "12:05",
                        following = following,
                        onRecenter = { recentred++ },
                        onRouteAgain = { again++ },
                        onEnd = { ended++ },
                        credits = routeCredits(ServerDefaults.lists, found.server),
                    )
                androidx.compose.foundation.layout.Column {
                    GuidanceBanner(ui)
                    GuidanceBar(ui)
                }
            }
        }
    }

    private val onTheWay = RouteProgress(0, 500.0, 3.0, false, false, null, 900.0, 900.0, 186.0, 120.0)

    @Test
    fun theBarSaysTheTimeLeftAndWhenAndEndEndsIt() {
        guiding(onTheWay, following = true)
        compose.onNodeWithText("3 min · 900 m").assertExists()
        compose.onNodeWithText("Arrive at 12:05 · Kunsthaus").assertExists()
        compose.onNodeWithText("Re-centre").assertDoesNotExist()
        compose.onNodeWithText("Find a new route").assertDoesNotExist()
        compose.onNodeWithText("End").performClick()
        assertEquals(1, ended)
    }

    @Test
    fun theBarCreditsTheRoutingServerAndNamesReportAMapError() {
        guiding(onTheWay, following = true)
        compose.onNodeWithText("Routing: Valhalla by FOSSGIS · © OpenStreetMap contributors · Report a map error").assertExists()
    }

    @Test
    fun offTheRouteANewRouteIsOfferedAndReCentreFollowsAgain() {
        guiding(onTheWay.copy(offRoute = true, offBy = 120.0), following = false)
        compose.onNodeWithText("You're off the route").assertExists()
        compose.onNodeWithText("Find a new route").performClick()
        assertEquals(1, again)
        compose.onNodeWithText("Re-centre").performClick()
        assertEquals(1, recentred)
    }

    @Test
    fun whileANewRouteIsAskedForTheRouteStillSaysWhatIsLeftAndOffersNoOther() {
        guiding(onTheWay.copy(offRoute = true, offBy = 120.0), following = true, rerouting = true)
        compose.onNodeWithText("Finding a new route…").assertExists()
        compose.onNodeWithText("You're about 120 m off the route").assertExists()
        compose.onNodeWithText("Find a new route").assertDoesNotExist()
        compose.onNodeWithText("3 min · 900 m").assertExists()
        compose.onNodeWithText("Routing: Valhalla by FOSSGIS · © OpenStreetMap contributors · Report a map error").assertExists()
    }

    @Test
    fun noNewRouteSaysWhyAndOffersOneAgain() {
        guiding(onTheWay.copy(offRoute = true, offBy = 120.0), following = true, noNewRoute = RouteStatus.Unreachable)
        compose.onNodeWithText("No new route").assertExists()
        compose.onNodeWithText("Can't reach the routing server. Check your connection.").assertExists()
        compose.onNodeWithText("Find a new route").performClick()
        assertEquals(1, again)
        compose.onNodeWithText("3 min · 900 m").assertExists()
    }
}
