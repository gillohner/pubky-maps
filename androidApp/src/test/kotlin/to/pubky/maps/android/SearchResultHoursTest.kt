package to.pubky.maps.android

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import kotlinx.datetime.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import to.pubky.maps.links.GeoPoint
import to.pubky.maps.links.OsmId
import to.pubky.maps.links.OsmType
import to.pubky.maps.place.PlaceRef
import to.pubky.maps.place.PlaceSource
import to.pubky.maps.search.SearchActions
import to.pubky.maps.search.SearchList
import to.pubky.maps.search.SearchMode
import to.pubky.maps.search.SearchResult
import to.pubky.maps.search.SearchState
import to.pubky.maps.search.SourceState
import to.pubky.maps.ui.PubkyMapsTheme

/** A search result with kept opening hours says whether it is open now under its kind and address. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp")
class SearchResultHoursTest {
    @get:Rule
    val compose = createComposeRule()

    private fun result(
        name: String,
        id: Long,
        hours: String?,
    ) = SearchResult(
        PlaceRef(
            uri = "https://www.openstreetmap.org/node/$id",
            name = name,
            point = GeoPoint(47.3702, 8.5479),
            osm = OsmId(OsmType.Node, id),
            source = PlaceSource.Photon,
            category = "Museum",
            address = "Heimplatz 1, 8001 Zürich",
        ),
        photonRank = 0,
        openingHours = hours,
    )

    private val results =
        listOf(
            result("Kunsthaus", 1, "Tu-Su 10:00-18:00"),
            result("Kunsthaus Bar", 2, "Mo-Sa 17:00-24:00"),
            result("Kunsthaus Shop", 3, "24/7"),
            // Hours the app cannot work out (sunset) and a place with none say nothing.
            result("Kunsthaus Garden", 4, "sunrise-sunset"),
            result("Kunsthaus Stop", 5, null),
        )

    private fun show(now: LocalDateTime?) {
        val state =
            SearchState(
                mode = SearchMode.Editing,
                query = "kunsthaus",
                resultsQuery = "kunsthaus",
                results = results,
                photon = SourceState.Answered,
                pinakes = SourceState.Answered,
            )
        compose.setContent {
            PubkyMapsTheme { SearchList(state, recents = emptyList(), userLocation = null, actions = SearchActions(), now = now) }
        }
    }

    @Test
    fun eachRowSaysWhetherItIsOpenNow() {
        show(now = LocalDateTime(2026, 11, 25, 12, 0))
        compose.onNodeWithText("Open · Closes 18:00").assertExists()
        compose.onNodeWithText("Closed · Opens 17:00").assertExists()
        compose.onNodeWithText("Open 24 hours").assertExists()
        // The three above ("Opens 17:00" too): the garden and the stop add none.
        assertEquals(3, compose.onAllNodesWithText("Open", substring = true).fetchSemanticsNodes().size)
        assertEquals(1, compose.onAllNodesWithText("Closed", substring = true).fetchSemanticsNodes().size)
    }

    @Test
    fun withoutTheTimeRowsShowNoHours() {
        show(now = null)
        compose.onNodeWithText("Kunsthaus Bar").assertExists()
        assertEquals(0, compose.onAllNodesWithText("Open", substring = true).fetchSemanticsNodes().size)
        assertEquals(0, compose.onAllNodesWithText("Closed", substring = true).fetchSemanticsNodes().size)
    }
}
