package to.pubky.maps.android

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Dp
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
import to.pubky.maps.place.PlacePageActions
import to.pubky.maps.place.PlacePageContext
import to.pubky.maps.place.PlacePageState
import to.pubky.maps.place.PlacePart
import to.pubky.maps.place.PlacePost
import to.pubky.maps.place.PlaceRef
import to.pubky.maps.place.PlaceSheet
import to.pubky.maps.place.PlaceSource
import to.pubky.maps.place.SheetStop
import to.pubky.maps.ui.PubkyMapsTheme

/**
 * At half a place page that is still loading its card takes half the room, as the page it becomes
 * does, so the sheet does not jump up when the card arrives (and the map left in view, where a picked
 * place is centred, stays the same). Once the card is in, or has failed, a short page is as tall as
 * its content.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp-notnight-mdpi")
class PlaceSheetLoadingTest {
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

    private val place = PlaceRef("https://www.openstreetmap.org/node/1", "Conditorei Schober", GeoPoint(47.37, 8.54), null, PlaceSource.Map)

    /** Just opened: every part still loading. */
    private val loading = PlacePageState(place = place)

    /** Loaded with more than half a screen of posts. */
    private val long =
        PlacePageState(
            place = place,
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

    /** Loaded with nothing to show under the header. */
    private val short = PlacePageState(place = place, loading = emptySet())

    private val actions =
        PlacePageActions(onClose = {}, onShare = null, openIn = emptyList(), onOpenIn = {}, onOpenLink = {}, onLoadMorePosts = {})

    private var shown by mutableStateOf(loading)

    private fun showAtHalf(page: PlacePageState) {
        shown = page
        compose.setContent {
            PubkyMapsTheme {
                Box(Modifier.fillMaxSize()) {
                    PlaceSheet(
                        shown,
                        context,
                        actions,
                        SheetStop.Half,
                        {},
                        maxHeight = MaxHeight,
                        modifier = Modifier.align(Alignment.BottomCenter).testTag("sheet"),
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    private fun sheetHeight(): Dp {
        val bounds = compose.onNodeWithTag("sheet").getUnclippedBoundsInRoot()
        return bounds.bottom - bounds.top
    }

    private fun assertNear(
        expected: Dp,
        actual: Dp,
    ) = assertEquals(expected.value, actual.value, 1f)

    @Test
    fun aLoadingPageTakesHalfTheRoomAtHalf() {
        showAtHalf(loading)
        assertNear(MaxHeight / 2, sheetHeight())
    }

    @Test
    fun theSheetKeepsItsHeightWhenTheLoadedPageArrives() {
        showAtHalf(loading)
        val before = sheetHeight()
        shown = long
        compose.waitForIdle()
        assertNear(before, sheetHeight())
    }

    @Test
    fun aShortPageShrinksToItsContentOnceItsCardIsIn() {
        showAtHalf(loading)
        shown = short
        compose.waitForIdle()
        val after = sheetHeight()
        assertTrue("the short page's sheet is $after tall", after < MaxHeight / 2 - 50.dp)
    }

    @Test
    fun aPageWhoseCardFailedIsAsTallAsItsContent() {
        showAtHalf(loading.copy(loading = loading.loading - PlacePart.Card, failed = setOf(PlacePart.Card)))
        assertTrue("the sheet is ${sheetHeight()} tall", sheetHeight() < MaxHeight / 2 - 50.dp)
    }

    private companion object {
        val MaxHeight = 800.dp
    }
}
