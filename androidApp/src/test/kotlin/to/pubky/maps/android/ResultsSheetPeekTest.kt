package to.pubky.maps.android

import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.github.takahirom.roborazzi.captureRoboImage
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

/**
 * At its lowest height the phone results sheet shows its handle and its whole first result, and none
 * of the second, as Google Maps' collapsed results show one whole result: at the normal font size and
 * at twice it, and still when the first result changes while the sheet is down. Native graphics, so
 * text is measured with real fonts. Also its pictures (phone: light and dark, and light at the large font).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp-notnight")
class ResultsSheetPeekTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    /** The user stands near Zürich's main station, so each row has a distance. */
    private val here = GeoPoint(47.3779, 8.5403)

    private fun result(
        name: String,
        category: String,
        address: String,
        rank: Int,
        onPubky: Boolean = false,
    ) = SearchResult(
        PlaceRef(
            uri = "https://www.openstreetmap.org/node/${name.hashCode()}",
            name = name,
            point = GeoPoint(47.3702 + rank / 1000.0, 8.5479),
            osm = null,
            source = if (onPubky) PlaceSource.Pinakes else PlaceSource.Photon,
            category = category,
            address = address,
        ),
        photonRank = rank,
        pinakesRank = if (onPubky) 0 else null,
        collections = if (onPubky) 2 else 0,
        kind = PlaceKind.Culture,
    )

    private val kunsthaus = result("Kunsthaus", "Museum", "Heimplatz 1, 8001 Zürich", rank = 0, onPubky = true)
    private val shop = result("Kunsthaus Zürich Shop", "Bookshop", "Heimplatz 1, 8001 Zürich", rank = 1)
    private val aarau = result("Kunsthaus Aarau", "Museum", "Aargauerplatz, 5000 Aarau", rank = 2)

    private fun state(
        results: List<SearchResult>,
        photon: SourceState = SourceState.Answered,
        pinakes: SourceState = SourceState.Answered,
    ) = SearchState(
        mode = SearchMode.Results,
        query = "kunsthaus",
        resultsQuery = "kunsthaus",
        results = results,
        photon = photon,
        pinakes = pinakes,
    )

    private fun show(
        fontScale: Float,
        search: MutableState<SearchState> = mutableStateOf(state(listOf(kunsthaus, shop, aarau))),
        navigationBar: Dp = 0.dp,
    ) {
        // Drawn behind the system bars, as the app is: the sheet itself keeps clear of them.
        WindowCompat.setDecorFitsSystemWindows(compose.activity.window, false)
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
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
                        search = MapSearch(search.value, recents = emptyList(), userLocation = here, actions = SearchActions()),
                    ) {
                        val dark = isSystemInDarkTheme()
                        Box(
                            Modifier.fillMaxSize().background(if (dark) Color(0xFF2B2D31) else Color(0xFFF2EFE9)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("Map", color = if (dark) Color(0xFF8A8D93) else Color(0xFF9A968E))
                        }
                    }
                }
            }
        }
        if (navigationBar > 0.dp) {
            val px = (navigationBar.value * compose.activity.resources.displayMetrics.density).toInt()
            val bars = WindowInsetsCompat.Builder().setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, px)).build()
            ViewCompat.dispatchApplyWindowInsets(compose.activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0), bars)
        }
        compose.waitForIdle()
    }

    /** A fast drag down on the handle: from half the sheet goes on to its lowest height. */
    private fun lower() {
        compose.onNodeWithContentDescription("Results list, half").performTouchInput {
            swipe(center, center + Offset(0f, 400.dp.toPx()), durationMillis = 300)
        }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Results list, peek").assertExists()
    }

    private fun handleTop(): Dp = compose.onNodeWithContentDescription("Results list", substring = true).getUnclippedBoundsInRoot().top

    private fun row(detail: String): SemanticsMatcher = hasText(detail) and hasClickAction()

    /** The row with [detail] is laid out whole between the handle and the [navigationBar] at the bottom. */
    private fun assertWhole(
        detail: String,
        navigationBar: Dp = 0.dp,
    ) {
        val bottom = compose.onRoot().getUnclippedBoundsInRoot().bottom - navigationBar
        val handle = compose.onNodeWithContentDescription("Results list, peek").getUnclippedBoundsInRoot()
        val row = compose.onNode(row(detail)).getUnclippedBoundsInRoot()
        assertTrue("the row starts at ${row.top}, above the handle's end at ${handle.bottom}", row.top >= handle.bottom)
        assertTrue("the row ends at ${row.bottom}, the screen at $bottom", row.bottom <= bottom)
    }

    /** No part of the row with [detail] is on the screen (a pixel of rounding aside). */
    private fun assertHidden(detail: String) {
        val shown = compose.onAllNodes(row(detail)).fetchSemanticsNodes().map { it.boundsInRoot.height }
        assertTrue("$shown px of the row show", shown.all { it <= 1f })
    }

    @Test
    fun atTheNormalFontSizeTheLowestHeightShowsTheWholeFirstResult() {
        show(fontScale = 1f)
        lower()
        assertWhole("Museum · Heimplatz 1, 8001 Zürich")
        assertHidden("Bookshop · Heimplatz 1, 8001 Zürich")
    }

    @Test
    fun atTwiceTheFontSizeTheLowestHeightShowsTheWholeFirstResult() {
        show(fontScale = 2f)
        lower()
        assertWhole("Museum · Heimplatz 1, 8001 Zürich")
        assertHidden("Bookshop · Heimplatz 1, 8001 Zürich")
    }

    @Test
    fun theLowestHeightShowsTheWholeFirstResultAboveTheNavigationBar() {
        show(fontScale = 2f, navigationBar = 48.dp)
        lower()
        assertWhole("Museum · Heimplatz 1, 8001 Zürich", navigationBar = 48.dp)
        // Not even behind the bar.
        assertHidden("Bookshop · Heimplatz 1, 8001 Zürich")
    }

    @Test
    fun aNewFirstResultKeepsTheSheetDownAndShowsWhole() {
        val search = mutableStateOf(state(listOf(aarau)))
        show(fontScale = 2f, search)
        lower()
        val before = handleTop()
        // Other results while the sheet is down: a first row of another height.
        search.value = state(listOf(shop, kunsthaus))
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Results list, peek").assertExists()
        assertTrue("the sheet stayed at ${handleTop()}", handleTop() != before)
        assertWhole("Bookshop · Heimplatz 1, 8001 Zürich")
        assertHidden("Museum · Heimplatz 1, 8001 Zürich")
    }

    @Test
    fun resultsUnderANewLineAboveThemGrowTheLowSheetToTheWholeFirstResult() {
        // Lowered while the search runs: only the progress line is there to measure.
        val search = mutableStateOf(state(emptyList(), photon = SourceState.Loading, pinakes = SourceState.Loading))
        show(fontScale = 2f, search)
        lower()
        // The results come with a line on a missing source above them.
        search.value = state(listOf(kunsthaus, shop), pinakes = SourceState.Unreachable)
        compose.waitForIdle()
        assertWhole("Museum · Heimplatz 1, 8001 Zürich")
        assertHidden("Bookshop · Heimplatz 1, 8001 Zürich")
    }

    @Test
    @Config(qualifiers = "w412dp-h340dp")
    fun onAShortScreenTheLowestHeightIsNoHigherThanHalf() {
        // A phone in split screen: at twice the font size the first result alone is taller than half the
        // window, so the lowest height is half, and a short slow drag up from half comes back to half.
        show(fontScale = 2f)
        val middle = compose.onRoot().getUnclippedBoundsInRoot().bottom / 2
        compose.onNodeWithContentDescription("Results list, half").performTouchInput {
            swipe(center, center - Offset(0f, 60.dp.toPx()), durationMillis = 2000)
        }
        compose.waitForIdle()
        assertTrue("the sheet starts at ${handleTop()}, above the middle $middle", handleTop() >= middle - 1.dp)
    }

    /** Twelve results, each with an address of its own, so the list scrolls at half height. */
    private val many =
        (1..12).map { n -> result("Museum $n", "Museum", "Teststrasse $n, 8001 Zürich", rank = n) }

    @Test
    fun aSheetLoweredWhileItsListIsScrolledShowsTheWholeFirstResult() {
        show(fontScale = 1f, mutableStateOf(state(many)))
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(6)
        compose.waitForIdle()
        lower()
        assertWhole("Museum · Teststrasse 1, 8001 Zürich")
        assertHidden("Museum · Teststrasse 2, 8001 Zürich")
        assertHidden("Museum · Teststrasse 7, 8001 Zürich")
    }

    @Test
    fun aNewResultAboveTheShownOneAtTheLowestHeightIsTheOneShown() {
        val search = mutableStateOf(state(listOf(aarau)))
        show(fontScale = 1f, search)
        lower()
        // The old first result is still there, now second: the sheet shows the new first one.
        search.value = state(listOf(kunsthaus, aarau))
        compose.waitForIdle()
        assertWhole("Museum · Heimplatz 1, 8001 Zürich")
        assertHidden("Museum · Aargauerplatz, 5000 Aarau")
    }

    @Test
    @Config(qualifiers = "w412dp-h340dp")
    fun onAShortScreenTheSheetHasNoLowestHeightOfItsOwn() {
        // In split screen at twice the font size the first result alone takes more than half the window:
        // a lowest height would look the same as half, so there is none, and a tap on the handle (or
        // TalkBack's resize action) at once goes to full and back.
        show(fontScale = 2f)
        compose.onNodeWithContentDescription("Results list, half").performTouchInput {
            swipe(center, center + Offset(0f, 200.dp.toPx()), durationMillis = 200)
        }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Results list, half").performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Results list, full").performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Results list, half").assertExists()
    }

    @Test
    fun peekPhoneLight() {
        show(fontScale = 1f)
        lower()
        compose.onRoot().captureRoboImage("screenshots/search-results-peek-phone-light.png")
    }

    @Test
    @Config(qualifiers = "+night")
    fun peekPhoneDark() {
        show(fontScale = 1f)
        lower()
        compose.onRoot().captureRoboImage("screenshots/search-results-peek-phone-dark.png")
    }

    @Test
    fun peekLargeFontPhoneLight() {
        show(fontScale = 2f)
        lower()
        compose.onRoot().captureRoboImage("screenshots/search-results-peek-large-font-phone-light.png")
    }
}
