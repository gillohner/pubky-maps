package to.pubky.maps.android

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
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
import to.pubky.maps.config.ServerDefaults
import to.pubky.maps.links.GeoPoint
import to.pubky.maps.map.MapScreen
import to.pubky.maps.map.PlacePageSlot
import to.pubky.maps.place.PlacePageActions
import to.pubky.maps.place.PlacePageContext
import to.pubky.maps.place.PlacePageState
import to.pubky.maps.place.PlacePost
import to.pubky.maps.place.PlaceRef
import to.pubky.maps.place.PlaceSheet
import to.pubky.maps.place.PlaceSource
import to.pubky.maps.place.SheetStop
import to.pubky.maps.ui.PubkyMapsTheme

/** The phone place sheet driven by touch: a released drag animates to its stop; the controls stay clear of the search bar. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w412dp-h915dp-notnight-mdpi")
class PlaceSheetInteractionTest {
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

    /** A page taller than the screen, so its full stop is the whole height. */
    private val page =
        PlacePageState(
            place = PlaceRef("https://www.openstreetmap.org/node/1", "Conditorei Schober", GeoPoint(47.37, 8.54), null, PlaceSource.Map),
            posts =
                (1..30).map {
                    PlacePost(
                        "pubky://a/pub/social/v1/posts/$it",
                        "a",
                        "note",
                        "Post number $it about the hot chocolate.",
                        1_795_000_000_000,
                        emptyList(),
                    )
                },
            loading = emptySet(),
        )

    private val actions =
        PlacePageActions(onClose = {}, onShare = null, openIn = emptyList(), onOpenIn = {}, onOpenLink = {}, onLoadMorePosts = {})

    @Test
    fun aReleasedDragAnimatesToItsStopInsteadOfJumping() {
        var stop by mutableStateOf(SheetStop.Half)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            PubkyMapsTheme {
                Box(Modifier.fillMaxSize()) {
                    PlaceSheet(
                        page,
                        context,
                        actions,
                        stop,
                        { stop = it },
                        maxHeight = 800.dp,
                        modifier = Modifier.align(Alignment.BottomCenter),
                    )
                }
            }
        }
        compose.mainClock.advanceTimeBy(2_000)
        val title = compose.onNodeWithText("Conditorei Schober")
        val halfTop = title.getUnclippedBoundsInRoot().top

        title.performTouchInput { swipeUp(startY = centerY, endY = centerY - 150f, durationMillis = 100) }
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeByFrame()
        val onTheWay = title.getUnclippedBoundsInRoot().top
        compose.mainClock.advanceTimeBy(2_000)
        val fullTop = title.getUnclippedBoundsInRoot().top

        assertEquals(SheetStop.Full, stop)
        assertTrue("the sheet went up: $halfTop -> $fullTop", fullTop < halfTop)
        assertTrue("two frames after the release it is still on its way ($onTheWay, full at $fullTop)", onTheWay > fullTop + 1.dp)
    }

    @Test
    fun myLocationAndTheCreditsRideOnTheSheetOnlyWhileTheyFitUnderTheSearchBar() {
        var sheetHeight by mutableStateOf(300.dp)
        compose.setContent {
            PubkyMapsTheme {
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
                    placePage =
                        PlacePageSlot(
                            full = false,
                            sheet = { _, _ -> Box(Modifier.fillMaxWidth().height(sheetHeight)) },
                            panel = {},
                        ),
                ) { Box(Modifier.fillMaxSize()) }
            }
        }
        compose.onNodeWithContentDescription("Show my location").assertIsDisplayed()

        // Dragged up to near the bar (not yet the full stop): the controls would reach under it.
        sheetHeight = 800.dp
        compose.waitForIdle()

        compose.onNodeWithContentDescription("Show my location").assertIsNotDisplayed()
    }
}
