package to.pubky.maps.android

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import to.pubky.maps.links.GeoPoint
import to.pubky.maps.place.PlaceRef
import to.pubky.maps.place.PlaceSource
import to.pubky.maps.search.PlaceKind
import to.pubky.maps.search.SearchActions
import to.pubky.maps.search.SearchList
import to.pubky.maps.search.SearchMode
import to.pubky.maps.search.SearchResult
import to.pubky.maps.search.SearchState
import to.pubky.maps.search.SourceState
import to.pubky.maps.ui.PubkyMapsTheme

/**
 * At Android's largest font size (200 %) on a phone, a search result row shows the place's whole name
 * and its whole kind and address, beside the distance and the "On Pubky" mark: no line ends in "…".
 * Native graphics, so text is measured with real fonts.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp")
class SearchRowLargeFontTest {
    @get:Rule
    val compose = createComposeRule()

    /** The user stands near Zürich's main station, so each row has a distance. */
    private val here = GeoPoint(47.3779, 8.5403)

    private fun result(
        name: String,
        category: String,
        address: String,
        onPubky: Boolean = false,
    ) = SearchResult(
        PlaceRef(
            uri = "https://www.openstreetmap.org/node/${name.hashCode()}",
            name = name,
            point = GeoPoint(47.3702, 8.5479),
            osm = null,
            source = if (onPubky) PlaceSource.Pinakes else PlaceSource.Photon,
            category = category,
            address = address,
        ),
        photonRank = 0,
        pinakesRank = if (onPubky) 0 else null,
        collections = if (onPubky) 2 else 0,
        kind = PlaceKind.Culture,
    )

    private fun showAtLargeFont(vararg results: SearchResult) {
        val state =
            SearchState(
                mode = SearchMode.Editing,
                query = "kunsthaus",
                resultsQuery = "kunsthaus",
                results = results.toList(),
                photon = SourceState.Answered,
                pinakes = SourceState.Answered,
            )
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) {
                PubkyMapsTheme {
                    SearchList(
                        state,
                        recents = emptyList(),
                        userLocation = here,
                        actions = SearchActions(),
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }

    /** Each of [texts] is in the list and laid out without cutting a line. */
    private fun assertShownWhole(vararg texts: String) {
        texts.forEach { text ->
            val node = compose.onNodeWithText(text, useUnmergedTree = true).fetchSemanticsNode()
            val layouts = mutableListOf<TextLayoutResult>()
            node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
            // Not hasVisualOverflow: it reports an ellipsized text as too wide even when it fits.
            val layout = layouts.single()
            assertFalse("'$text' is cut", (0 until layout.lineCount).any(layout::isLineEllipsized))
        }
    }

    @Test
    fun aResultOnPubkyShowsItsWholeAddress() {
        showAtLargeFont(result("Kunsthaus", "Museum", "Heimplatz 1, 8001 Zürich", onPubky = true))
        assertShownWhole("Kunsthaus", "Museum · Heimplatz 1, 8001 Zürich")
    }

    @Test
    fun aResultWithALongerTownShowsItsWholeAddress() {
        showAtLargeFont(result("Kunsthaus Aarau", "Museum", "Aargauerplatz, 5000 Aarau"))
        assertShownWhole("Kunsthaus Aarau", "Museum · Aargauerplatz, 5000 Aarau")
    }

    @Test
    fun aLongNameWraps() {
        showAtLargeFont(result("Kunsthaus Zürich Shop", "Bookshop", "Heimplatz 1, 8001 Zürich"))
        assertShownWhole("Kunsthaus Zürich Shop", "Bookshop · Heimplatz 1, 8001 Zürich")
    }
}
