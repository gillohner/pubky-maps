package to.pubky.maps.android

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
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
import to.pubky.maps.links.OsmId
import to.pubky.maps.links.OsmType
import to.pubky.maps.place.PlaceCardInfo
import to.pubky.maps.place.PlacePageActions
import to.pubky.maps.place.PlacePageContext
import to.pubky.maps.place.PlacePageState
import to.pubky.maps.place.PlaceRef
import to.pubky.maps.place.PlaceSheet
import to.pubky.maps.place.PlaceSource
import to.pubky.maps.place.SheetStop
import to.pubky.maps.ui.PubkyMapsTheme

/**
 * The phone place sheet in a short window (split screen) at twice the font size, where the header
 * alone takes more than half the sheet's room: the header scrolls with the page, so every section
 * can still be reached, and a tap on the handle at the lowest height opens the sheet to the top. In a
 * normal window the header stays in place over the scrolling page. Native graphics, real fonts.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp-notnight-mdpi")
class PlaceSheetShortWindowTest {
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

    private val page =
        PlacePageState(
            place = place,
            card =
                PlaceCardInfo(
                    id = "49d6eb1c6a69946a41fe45ccd44c4a72",
                    uri = place.uri,
                    name = "Kunsthaus",
                    type = "Museum",
                    category = "tourism/museum",
                    address = "Heimplatz 1, 8001 Zürich",
                    lat = 47.3702241,
                    lon = 8.5479797,
                    description = null,
                    image = null,
                    credits = emptyList(),
                    attribution = null,
                    alsoOn = emptyList(),
                    refs = listOf(place.uri),
                    collections = 0,
                    facts = mapOf("opening_hours" to "Tu-Su 10:00-18:00"),
                ),
            loading = emptySet(),
        )

    private val actions =
        PlacePageActions(onClose = {}, onShare = {}, openIn = emptyList(), onOpenIn = {}, onOpenLink = {}, onLoadMorePosts = {})

    /** The Details section's last row, the end of this page. */
    private val coordinates = "47.37022, 8.54798"

    private var stopAsked: SheetStop? = null

    private var shownStop by mutableStateOf(SheetStop.Full)

    /** Scrolled to, the node shows whole: none of it is cut off by the sheet or hidden under the header. */
    private fun SemanticsNodeInteraction.assertShownWhole() {
        performScrollTo()
        val shown = getBoundsInRoot()
        val whole = getUnclippedBoundsInRoot()
        assertEquals("the visible height of a row ${whole.bottom - whole.top} tall", whole.bottom - whole.top, shown.bottom - shown.top)
    }

    private fun show(
        stop: SheetStop,
        maxHeight: Dp,
        fontScale: Float,
    ) {
        shownStop = stop
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                PubkyMapsTheme {
                    Box(Modifier.fillMaxSize()) {
                        PlaceSheet(
                            page,
                            context,
                            actions,
                            shownStop,
                            { stopAsked = it },
                            maxHeight = maxHeight,
                            modifier = Modifier.align(Alignment.BottomCenter),
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun inAShortWindowTheEndOfThePageCanBeReached() {
        // Shorter than the header at this size: kept in place it would leave the page no room at all.
        show(SheetStop.Full, maxHeight = ShortWindow, fontScale = 2f)
        compose.onNodeWithText(coordinates).assertShownWhole()
    }

    @Test
    fun inAShortWindowTheLowestHeightShowsTheHeaderAfterThePageWasScrolled() {
        show(SheetStop.Full, maxHeight = ShortWindow, fontScale = 2f)
        compose.onNodeWithText(coordinates).performScrollTo()
        shownStop = SheetStop.Peek
        compose.waitForIdle()
        val handleBottom = compose.onNodeWithContentDescription("Show more of the place").getUnclippedBoundsInRoot().bottom
        val title = compose.onNodeWithText("Kunsthaus")
        val shown = title.getBoundsInRoot()
        val whole = title.getUnclippedBoundsInRoot()
        assertEquals("the title's visible height", whole.bottom - whole.top, shown.bottom - shown.top)
        assertTrue("the title starts at ${whole.top}, under the handle at $handleBottom", whole.top >= handleBottom)
    }

    @Test
    fun inAShortWindowATapOnTheHandleAtTheLowestHeightOpensTheTop() {
        show(SheetStop.Peek, maxHeight = ShortWindow, fontScale = 2f)
        compose.onNodeWithContentDescription("Show more of the place").performClick()
        assertEquals(SheetStop.Full, stopAsked)
    }

    @Test
    fun inANormalWindowTheHeaderStaysInPlaceWhileThePageScrolls() {
        show(SheetStop.Full, maxHeight = 800.dp, fontScale = 2f)
        val before = compose.onNodeWithText("Kunsthaus").getUnclippedBoundsInRoot().top
        compose.onNodeWithText(coordinates).assertShownWhole()
        assertEquals(before, compose.onNodeWithText("Kunsthaus").getUnclippedBoundsInRoot().top)
    }

    private companion object {
        /** Less than the header and handle take at twice the font size (about 270 dp). */
        val ShortWindow = 240.dp
    }
}
