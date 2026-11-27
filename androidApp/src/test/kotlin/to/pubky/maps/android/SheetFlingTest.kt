package to.pubky.maps.android

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.dp
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
import to.pubky.maps.map.MapScreen
import to.pubky.maps.map.MapSearch
import to.pubky.maps.place.PlacePageActions
import to.pubky.maps.place.PlacePageContext
import to.pubky.maps.place.PlacePageState
import to.pubky.maps.place.PlacePost
import to.pubky.maps.place.PlaceRef
import to.pubky.maps.place.PlaceSheet
import to.pubky.maps.place.PlaceSource
import to.pubky.maps.place.SheetStop
import to.pubky.maps.search.PlaceKind
import to.pubky.maps.search.SearchActions
import to.pubky.maps.search.SearchMode
import to.pubky.maps.search.SearchResult
import to.pubky.maps.search.SearchState
import to.pubky.maps.search.SourceState
import to.pubky.maps.ui.PubkyMapsTheme

/**
 * Both phone sheets settle by one rule wherever a finger lets go of them: a quick flick (400 dp/s)
 * goes on to the next height, a slower release to the nearest one, at every screen density. Each
 * release here moves the finger a short way (40-60 dp, less than half way to the next height), so
 * the nearest height is the one the sheet started at.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-notnight-mdpi")
class SheetFlingTest {
    @get:Rule
    val compose = createComposeRule()

    // The results sheet over the map.

    private val results =
        (1..12).map { n ->
            SearchResult(
                PlaceRef(
                    uri = "https://www.openstreetmap.org/node/$n",
                    name = "Museum $n",
                    point = GeoPoint(47.3702 + n / 1000.0, 8.5479),
                    osm = null,
                    source = PlaceSource.Photon,
                    category = "Museum",
                    address = "Teststrasse $n, 8001 Zürich",
                ),
                photonRank = n,
                pinakesRank = null,
                collections = 0,
                kind = PlaceKind.Culture,
            )
        }

    private fun showResults() {
        val state =
            SearchState(
                mode = SearchMode.Results,
                query = "museum",
                resultsQuery = "museum",
                results = results,
                photon = SourceState.Answered,
                pinakes = SourceState.Answered,
            )
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
                    search = MapSearch(state, recents = emptyList(), userLocation = null, actions = SearchActions()),
                ) {
                    Box(Modifier.fillMaxSize().background(Color(0xFFF2EFE9)))
                }
            }
        }
        compose.waitForIdle()
    }

    /** A drag down the handle by 60 dp, at [dpPerSecond]. */
    private fun dragHandleDown(dpPerSecond: Int) {
        compose.onNodeWithContentDescription("Results list, half").performTouchInput {
            swipe(center, center + Offset(0f, 60.dp.toPx()), durationMillis = 60L * 1000 / dpPerSecond)
        }
        compose.waitForIdle()
    }

    @Test
    fun aSlowReleaseOfTheResultsHandleGoesBackToTheNearestHeight() {
        // 300 dp/s: Compose's default for draggable anchors (125 dp/s) took this on to the lowest height,
        // where the same release of the list stayed at half.
        showResults()
        dragHandleDown(dpPerSecond = 300)
        compose.onNodeWithContentDescription("Results list, half").assertExists()
    }

    @Test
    fun aFlickOfTheResultsHandleGoesOnToTheNextHeight() {
        showResults()
        dragHandleDown(dpPerSecond = 1000)
        compose.onNodeWithContentDescription("Results list, peek").assertExists()
    }

    @Test
    fun theResultsListSettlesTheSheetLikeItsHandle() {
        showResults()
        val list = compose.onNode(hasScrollToIndexAction())
        list.performTouchInput { swipe(center, center + Offset(0f, 60.dp.toPx()), durationMillis = 200) }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Results list, half").assertExists()
        list.performTouchInput { swipe(center, center + Offset(0f, 60.dp.toPx()), durationMillis = 60) }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Results list, peek").assertExists()
    }

    // The place sheet.

    private val context =
        PlacePageContext(
            now = LocalDateTime(LocalDate.parse("2026-11-25"), LocalTime(12, 0)),
            nowMillis = 1_795_608_000_000,
            timeZone = TimeZone.UTC,
            language = "en",
            distance = null,
        )

    /** A page taller than the screen, so its top stop is the whole height. */
    private val page =
        PlacePageState(
            place = PlaceRef("https://www.openstreetmap.org/node/1", "Conditorei Schober", GeoPoint(47.37, 8.54), null, PlaceSource.Map),
            posts =
                (1..30).map {
                    PlacePost("pubky://a/pub/social/v1/posts/$it", "a", "note", "Post number $it.", 1_795_000_000_000, emptyList())
                },
            loading = emptySet(),
        )

    private val actions =
        PlacePageActions(onClose = {}, onShare = null, openIn = emptyList(), onOpenIn = {}, onOpenLink = {}, onLoadMorePosts = {})

    /** Where the place sheet settles after its header is dragged up by 40 dp at [dpPerSecond]. */
    private fun placeSheetAfterDragUp(dpPerSecond: Int): SheetStop {
        var stop by mutableStateOf(SheetStop.Half)
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
        compose.waitForIdle()
        compose.onNodeWithText("Conditorei Schober").performTouchInput {
            swipe(center, center - Offset(0f, 40.dp.toPx()), durationMillis = 40L * 1000 / dpPerSecond)
        }
        compose.waitForIdle()
        return stop
    }

    @Test
    fun aFlickOfThePlaceSheetGoesOnToTheNextStopOnALessDenseScreen() {
        // 1000 dp/s is 1000 px/s at 1x: below the old 1200 px/s, so a flick that moved the sheet on a
        // dense phone screen fell back on a less dense one.
        assertEquals(SheetStop.Full, placeSheetAfterDragUp(dpPerSecond = 1000))
    }

    @Test
    @Config(qualifiers = "w412dp-h915dp-notnight-xxhdpi")
    fun aFlickOfThePlaceSheetGoesOnToTheNextStopOnADenseScreen() {
        assertEquals(SheetStop.Full, placeSheetAfterDragUp(dpPerSecond = 1000))
    }

    @Test
    fun aSlowReleaseOfThePlaceSheetGoesBackToTheNearestStop() {
        assertEquals(SheetStop.Half, placeSheetAfterDragUp(dpPerSecond = 200))
    }
}
