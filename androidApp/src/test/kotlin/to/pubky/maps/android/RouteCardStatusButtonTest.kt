package to.pubky.maps.android

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
import to.pubky.maps.routing.RouteStatus
import to.pubky.maps.routing.TravelMode
import to.pubky.maps.ui.PubkyMapsTheme

/**
 * The buttons under a route card's status (Allow location when location access is off, Turn on
 * location and Try again when the phone's location is off, Try again when there is no position or no
 * routing server) say what they are for, and the first one's label starts where the status text
 * starts, as the Steps button's label starts under the route's time.
 */
@RunWith(RobolectricTestRunner::class)
// Real text measuring: a label narrower than the button's minimum width would be centred in it.
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp")
class RouteCardStatusButtonTest {
    @get:Rule
    val compose = createComposeRule()

    private val kunsthaus = GeoPoint(47.3702, 8.5486)

    private var turnedOn = 0
    private var retried = 0

    private fun card(
        status: RouteStatus,
        canTurnOn: Boolean = true,
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
                                Directions("osm:node/1", kunsthaus, TravelMode.Car, position = null, status = status),
                                onMode = {},
                                onSteps = {},
                                onStep = {},
                                onClose = {},
                                onSwap = {},
                                onRetry = { retried++ },
                                onOpenSettings = {},
                                onTurnOnLocation = if (canTurnOn) ({ turnedOn++ }) else null,
                                credits = emptyList(),
                                onFromYourLocation = {},
                            ),
                    ),
                )
            }
        }
    }

    private fun left(
        text: String,
        substring: Boolean = false,
    ): Float =
        compose
            .onNodeWithText(text, substring = substring, useUnmergedTree = true)
            .fetchSemanticsNode()
            .boundsInRoot.left

    @Test
    fun allowLocationStartsUnderTheStatusText() {
        card(RouteStatus.NoLocation(permissionOff = true))
        assertEquals(left("Location access is off", substring = true), left("Allow location"), 1f)
        compose.onNodeWithText("Settings").assertDoesNotExist()
    }

    @Test
    fun withThePhonesLocationOffTurnOnLocationComesFirstAndTryAgainBesideIt() {
        card(RouteStatus.NoLocation(permissionOff = false, locationOff = true))
        assertEquals(left("Is location turned on?", substring = true), left("Turn on location"), 1f)
        assertTrue(left("Try again") > left("Turn on location"))
        compose.onNodeWithText("Turn on location").performClick()
        assertEquals(1, turnedOn)
        compose.onNodeWithText("Try again").performClick()
        assertEquals(1, retried)
    }

    @Test
    fun withoutALocationSettingToOpenOnlyTryAgainIsOffered() {
        card(RouteStatus.NoLocation(permissionOff = false, locationOff = true), canTurnOn = false)
        compose.onNodeWithText("Turn on location").assertDoesNotExist()
        assertEquals(left("Is location turned on?", substring = true), left("Try again"), 1f)
    }

    @Test
    fun aSlowPositionIsNotAskedToTurnLocationOn() {
        // The wait for the first position ended, or the permission dialog was dismissed: location may be on.
        card(RouteStatus.NoLocation(permissionOff = false))
        compose.onNodeWithText("Turn on location").assertDoesNotExist()
        compose.onNodeWithText("Try again").assertExists()
    }

    @Test
    fun tryAgainStartsUnderTheStatusTextWithoutALocation() {
        card(RouteStatus.NoLocation(permissionOff = false))
        assertEquals(left("Directions start from your location", substring = true), left("Try again"), 1f)
    }

    @Test
    fun tryAgainStartsUnderTheStatusTextWithoutARoutingServer() {
        card(RouteStatus.Unreachable)
        assertEquals(left("Can't reach the routing server", substring = true), left("Try again"), 1f)
    }
}
