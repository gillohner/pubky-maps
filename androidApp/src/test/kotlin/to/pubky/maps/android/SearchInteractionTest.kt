package to.pubky.maps.android

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import to.pubky.maps.config.ServerDefaults
import to.pubky.maps.links.GeoPoint
import to.pubky.maps.map.MapScreen
import to.pubky.maps.map.MapSearch
import to.pubky.maps.place.PlaceRef
import to.pubky.maps.place.PlaceSource
import to.pubky.maps.search.PlaceKind
import to.pubky.maps.search.SearchActions
import to.pubky.maps.search.SearchMode
import to.pubky.maps.search.SearchResult
import to.pubky.maps.search.SearchState
import to.pubky.maps.search.SourceState
import to.pubky.maps.ui.PubkyMapsTheme

/** The results sheet on a phone: its handle steps it between half, full and back; the list drags it too. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp")
class SearchInteractionTest {
    @get:Rule
    val compose = createComposeRule()

    private val results =
        (1..12).map { i ->
            SearchResult(
                PlaceRef(
                    uri = "https://www.openstreetmap.org/node/$i",
                    name = "Café $i",
                    point = GeoPoint(47.37 + i / 1000.0, 8.54),
                    osm = null,
                    source = PlaceSource.Photon,
                    category = "Café",
                ),
                photonRank = i - 1,
                kind = PlaceKind.Food,
            )
        }

    @Test
    fun theHandleOpensTheSheetFullAndBackToHalfAndPicksAResult() {
        var picked: SearchResult? = null
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
                    search =
                        MapSearch(
                            SearchState(
                                mode = SearchMode.Results,
                                query = "café",
                                resultsQuery = "café",
                                results = results,
                                photon = SourceState.Answered,
                                pinakes = SourceState.Answered,
                            ),
                            recents = emptyList(),
                            userLocation = null,
                            actions = SearchActions(onSelect = { picked = it }),
                        ),
                ) { Box(Modifier.fillMaxSize()) }
            }
        }
        compose.onNodeWithContentDescription("Results list, half").performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Results list, full").performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Results list, half").assertExists()
        compose.onNodeWithText("Café 1").performClick()
        assertEquals("Café 1", picked?.place?.title)
    }

    @Test
    fun atFullHeightNothingHiddenCoversSettingsOrTheSearchBar() {
        var settingsOpened = 0
        var locationAsked = 0
        var editing = 0
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
                    onMyLocation = { locationAsked++ },
                    onOpenSettings = { settingsOpened++ },
                    search =
                        MapSearch(
                            SearchState(
                                mode = SearchMode.Results,
                                query = "café",
                                resultsQuery = "café",
                                results = results,
                                photon = SourceState.Answered,
                                pinakes = SourceState.Answered,
                            ),
                            recents = emptyList(),
                            userLocation = null,
                            actions = SearchActions(onStartEditing = { editing++ }),
                        ),
                ) { Box(Modifier.fillMaxSize()) }
            }
        }
        compose.onNodeWithContentDescription("Show my location").assertExists()
        compose.onNodeWithContentDescription("Results list, half").performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Results list, full").assertExists()
        // The controls that ride on the sheet are gone: not drawn, not touchable, not read out.
        compose.onNodeWithContentDescription("Show my location").assertDoesNotExist()
        compose.onNodeWithText("OpenFreeMap", substring = true).assertDoesNotExist()
        // Settings and the search bar get their taps, also on their edges next to the sheet.
        compose.onNodeWithContentDescription("Settings").performTouchInput { click(Offset(centerX, top + 2f)) }
        compose.onNodeWithContentDescription("Settings").performTouchInput { click(Offset(centerX, bottom - 2f)) }
        assertEquals(2, settingsOpened)
        assertEquals(0, locationAsked)
        compose.onNode(hasSetTextAction()).performTouchInput { click(Offset(centerX, bottom - 2f)) }
        compose.waitForIdle()
        assertEquals(1, editing)
    }

    private fun showResults() {
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
                    search =
                        MapSearch(
                            SearchState(
                                mode = SearchMode.Results,
                                query = "café",
                                resultsQuery = "café",
                                results = results,
                                photon = SourceState.Answered,
                                pinakes = SourceState.Answered,
                            ),
                            recents = emptyList(),
                            userLocation = null,
                            actions = SearchActions(),
                        ),
                ) { Box(Modifier.fillMaxSize()) }
            }
        }
    }

    @Test
    fun aDragUpOnTheListRaisesTheSheetBeforeTheListScrolls() {
        showResults()
        compose.onNodeWithText("Café 2").performTouchInput {
            swipe(center, center - Offset(0f, 250.dp.toPx()), durationMillis = 400)
        }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Results list, full").assertExists()
        // The sheet took the drag: the list did not scroll, its first result is still on screen.
        compose.onNodeWithText("Café 1").assertIsDisplayed()
    }

    @Test
    fun aDragDownOnTheListAtItsTopLowersTheSheet() {
        showResults()
        compose.onNodeWithContentDescription("Results list, half").performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Results list, full").assertExists()
        compose.onNodeWithText("Café 3").performTouchInput {
            swipe(center, center + Offset(0f, 250.dp.toPx()), durationMillis = 400)
        }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Results list, half").assertExists()
    }

    @Test
    fun aShortFlickOnTheListGoesOnToTheNextHeight() {
        showResults()
        // 60 dp in 50 ms: nearer half than full when let go, but fast, so the sheet goes on to full.
        compose.onNodeWithText("Café 2").performTouchInput {
            swipe(center, center - Offset(0f, 60.dp.toPx()), durationMillis = 50)
        }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Results list, full").assertExists()
    }
}
