package to.pubky.maps.android

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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

/** The results sheet on a phone: its handle steps it between half, full and back. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w412dp-h915dp")
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
}
