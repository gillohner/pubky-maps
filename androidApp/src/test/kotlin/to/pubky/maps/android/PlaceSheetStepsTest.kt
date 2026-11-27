package to.pubky.maps.android

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
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
import to.pubky.maps.links.OsmId
import to.pubky.maps.links.OsmType
import to.pubky.maps.place.PlacePageActions
import to.pubky.maps.place.PlacePageContext
import to.pubky.maps.place.PlacePageState
import to.pubky.maps.place.PlaceRef
import to.pubky.maps.place.PlaceSheet
import to.pubky.maps.place.PlaceSource
import to.pubky.maps.place.SheetStop
import to.pubky.maps.routing.Directions
import to.pubky.maps.routing.DirectionsUi
import to.pubky.maps.routing.Route
import to.pubky.maps.routing.RouteStatus
import to.pubky.maps.routing.RouteStep
import to.pubky.maps.routing.TravelMode
import to.pubky.maps.routing.Turn
import to.pubky.maps.ui.PubkyMapsTheme

/**
 * The route's steps open in the phone sheet's page, under its header: the lowest stop keeps the height
 * of the header with its route card, so the map with the route stays in view, and opening the steps
 * raises the sheet to the top, where they show; a step tapped there lowers it again, the step shown on
 * the map and in the route card. Native graphics, so text is measured with real fonts.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp-notnight-mdpi")
class PlaceSheetStepsTest {
    @get:Rule
    val compose = createComposeRule()

    private val context =
        PlacePageContext(
            now = LocalDateTime(LocalDate.parse("2026-11-25"), LocalTime(12, 0)),
            nowMillis = 1_795_608_000_000,
            timeZone = TimeZone.UTC,
            language = "en",
            distance = "850 m",
        )

    private val here = GeoPoint(47.3779, 8.5403)

    private val place =
        PlaceRef(
            uri = "https://www.openstreetmap.org/relation/3768127",
            name = "Kunsthaus",
            point = GeoPoint(47.3702241, 8.5479797),
            osm = OsmId(OsmType.Relation, 3768127),
            source = PlaceSource.Pinakes,
            address = "Heimplatz 1, 8001 Zürich",
            category = "Museum",
        )

    private val route =
        Route(
            listOf(here, place.point!!),
            seconds = 289.0,
            meters = 1401.0,
            steps =
                listOf(
                    RouteStep("Drive north.", Turn.Depart, 35.0, here),
                    RouteStep("Turn right onto Museumstrasse.", Turn.Right, 52.0, here),
                    RouteStep("Keep left to stay on Bahnhofquai.", Turn.KeepLeft, 406.0, here),
                    RouteStep("Turn left onto Rudolf-Brun-Brücke.", Turn.Left, 329.0, here),
                    RouteStep("Turn right onto Seilergraben.", Turn.Right, 205.0, here),
                    RouteStep("Bear right onto Heimplatz.", Turn.SlightRight, 81.0, here),
                    RouteStep("Your destination is on the right.", Turn.Arrive, 0.0, here),
                ),
        )

    private var stepsShown by mutableStateOf(false)
    private var shownStep by mutableStateOf<Int?>(null)
    private val stepsTapped = ArrayList<Int>()
    private var stop by mutableStateOf(SheetStop.Peek)
    private val stopsAsked = ArrayList<SheetStop>()

    /** The sheet at [stop]; it moves to a stop it asks for only when [follow]. */
    private fun show(follow: Boolean) {
        compose.setContent {
            PubkyMapsTheme {
                Box(Modifier.fillMaxSize()) {
                    PlaceSheet(
                        PlacePageState(place, loading = emptySet()),
                        context,
                        PlacePageActions(
                            onClose = {},
                            onShare = {},
                            openIn = emptyList(),
                            onOpenIn = {},
                            onOpenLink = {},
                            onLoadMorePosts = {},
                            onDirections = {},
                            directions =
                                DirectionsUi(
                                    Directions(
                                        place.uri,
                                        place.point!!,
                                        TravelMode.Car,
                                        here,
                                        RouteStatus.Found(route, "s"),
                                        stepsShown = stepsShown,
                                        shownStep = shownStep,
                                    ),
                                    onMode = {},
                                    onSteps = { stepsShown = it },
                                    onStep = {
                                        stepsTapped += it
                                        shownStep = it
                                    },
                                    onClose = {},
                                    onSwap = {},
                                    onRetry = {},
                                    onOpenSettings = null,
                                    credits = emptyList(),
                                    onFromYourLocation = {},
                                ),
                        ),
                        stop,
                        {
                            stopsAsked += it
                            if (follow) stop = it
                        },
                        maxHeight = 860.dp,
                        modifier = Modifier.align(Alignment.BottomCenter).testTag("sheet"),
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    private fun sheetHeight() = compose.onNodeWithTag("sheet").getUnclippedBoundsInRoot().let { it.bottom - it.top }

    @Test
    fun openStepsLeaveTheLowestStopAtTheHeadersHeight() {
        show(follow = false)
        val peek = sheetHeight()
        compose.onNodeWithText("Steps").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Hide steps").assertIsDisplayed()
        assertEquals(SheetStop.Peek, stop)
        assertEquals(peek, sheetHeight())
        assertEquals(listOf(SheetStop.Full), stopsAsked)
    }

    @Test
    fun openingTheStepsRaisesTheSheetWhereTheyShow() {
        show(follow = true)
        val peek = sheetHeight()
        compose.onNodeWithText("Steps").performClick()
        compose.waitForIdle()
        assertEquals(SheetStop.Full, stop)
        compose.onNodeWithText("Turn right onto Museumstrasse.").assertIsDisplayed()
        // Closing them leaves the sheet where it is; opening them again at the top asks for no move.
        compose.onNodeWithText("Hide steps").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Steps").performClick()
        compose.waitForIdle()
        assertEquals(listOf(SheetStop.Full), stopsAsked)
        // Lowered again, the sheet is back at the header's height with the steps still open.
        stop = SheetStop.Peek
        compose.waitForIdle()
        assertEquals(peek, sheetHeight())
        compose.onNodeWithText("Hide steps").assertIsDisplayed()
    }

    @Test
    fun aStepTappedAtTheTopLowersTheSheetWhereItsCardGoesOn() {
        show(follow = true)
        val peek = sheetHeight()
        compose.onNodeWithText("Steps").performClick()
        compose.waitForIdle()
        assertEquals(SheetStop.Full, stop)
        compose.onNodeWithText("Turn left onto Rudolf-Brun-Brücke.").performClick()
        compose.waitForIdle()
        assertEquals(listOf(3), stepsTapped)
        // The map shows the step over the lowest stop, the route card shows it, a little taller.
        assertEquals(SheetStop.Peek, stop)
        compose.onNodeWithText("Step 4 of 7 · 330 m").assertIsDisplayed()
        assertTrue(sheetHeight() > peek)
        // The next step from the card moves the map and leaves the sheet where it is.
        compose.onNodeWithContentDescription("Next step").assertIsDisplayed().performClick()
        compose.waitForIdle()
        assertEquals(listOf(3, 4), stepsTapped)
        compose.onNodeWithText("Step 5 of 7 · 210 m").assertIsDisplayed()
        assertEquals(listOf(SheetStop.Full, SheetStop.Peek), stopsAsked)
    }
}
