package to.pubky.maps.android

import android.content.ClipboardManager
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import to.pubky.maps.links.GeoPoint
import to.pubky.maps.links.LinkProvider
import to.pubky.maps.place.PlaceHeader
import to.pubky.maps.place.PlacePageActions
import to.pubky.maps.place.PlacePageContext
import to.pubky.maps.place.PlacePageState
import to.pubky.maps.place.PlaceRef
import to.pubky.maps.place.PlaceSections
import to.pubky.maps.place.PlaceSource
import to.pubky.maps.ui.PubkyMapsTheme

/** The place page copies its coordinates (a tap on their row) and its address (a long press on the line under the category). */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-notnight-mdpi")
class PlaceCopyTest {
    @get:Rule
    val compose = createComposeRule()

    private val context =
        PlacePageContext(
            now = LocalDateTime(LocalDate.parse("2026-11-25"), LocalTime(12, 0)),
            nowMillis = 1_795_608_000_000,
            timeZone = TimeZone.UTC,
            language = "en",
            distance = null,
            provider = LinkProvider.Google,
        )

    private val page =
        PlacePageState(
            place =
                PlaceRef(
                    "https://www.openstreetmap.org/node/1",
                    "Kunsthaus",
                    GeoPoint(47.3701, 8.5481),
                    null,
                    PlaceSource.Map,
                    address = "Heimplatz 1, 8001 Zürich",
                ),
            loading = emptySet(),
        )

    private val actions =
        PlacePageActions(onClose = {}, onShare = null, openIn = emptyList(), onOpenIn = {}, onOpenLink = {}, onLoadMorePosts = {})

    private val clipboard: ClipboardManager
        get() = RuntimeEnvironment.getApplication().getSystemService(ClipboardManager::class.java)

    private val clipText: String?
        get() =
            clipboard.primaryClip
                ?.getItemAt(0)
                ?.text
                ?.toString()

    @Before
    fun showPage() {
        compose.setContent {
            PubkyMapsTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    PlaceHeader(page, context, actions)
                    PlaceSections(page, context, actions)
                }
            }
        }
        compose.waitForIdle()
        // The clock moves only when a test moves it, so the "Copied" mark is seen before its timer ends.
        compose.mainClock.autoAdvance = false
    }

    @Test
    fun aTapOnTheCoordinatesCopiesThemAndSaysSoForAMoment() {
        compose.onNodeWithText("47.3701, 8.5481").performClick()
        frames()

        assertEquals("47.3701, 8.5481", clipText)
        compose.onNodeWithText("Copied").assertIsDisplayed()
        compose.mainClock.advanceTimeBy(2_500)
        assertEquals(0, compose.onAllNodesWithText("Copied").fetchSemanticsNodes().size)
    }

    @Test
    fun aLongPressOnTheAddressCopiesTheAddressAloneNotWhereTheLinkCameFrom() {
        // A long press waits out its timeout on the clock.
        compose.mainClock.autoAdvance = true
        compose.onNodeWithText("Heimplatz 1, 8001 Zürich · From a Google Maps link").performTouchInput { longClick() }

        assertEquals("Heimplatz 1, 8001 Zürich", clipText)
    }

    @Test
    fun talkBackOffersCopyAddressAsTheLongPressActionAndTheLineSaysItCopied() {
        val line =
            compose.onNode(
                SemanticsMatcher("long press copies the address") {
                    it.config.getOrElseNullable(SemanticsActions.OnLongClick) { null }?.label == "Copy address"
                },
            )
        line.performSemanticsAction(SemanticsActions.OnLongClick)
        frames()

        assertEquals("Heimplatz 1, 8001 Zürich", clipText)
        compose.onNodeWithText("Address copied").assertIsDisplayed()
        compose.mainClock.advanceTimeBy(2_500)
        assertEquals(0, compose.onAllNodesWithText("Address copied").fetchSemanticsNodes().size)
    }

    /** Draws two frames, enough for a tap's state to show, without running the two-second timer. */
    private fun frames() {
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeByFrame()
    }
}
