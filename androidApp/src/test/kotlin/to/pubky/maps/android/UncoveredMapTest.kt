package to.pubky.maps.android

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.LayoutDirection
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import to.pubky.maps.config.ServerDefaults
import to.pubky.maps.links.GeoPoint
import to.pubky.maps.links.OsmId
import to.pubky.maps.links.OsmType
import to.pubky.maps.map.MapScreen
import to.pubky.maps.map.PlacePageSlot
import to.pubky.maps.place.PlacePageActions
import to.pubky.maps.place.PlacePageContext
import to.pubky.maps.place.PlacePageState
import to.pubky.maps.place.PlacePanel
import to.pubky.maps.place.PlaceRef
import to.pubky.maps.place.PlaceSheet
import to.pubky.maps.place.PlaceSource
import to.pubky.maps.place.SheetStop
import to.pubky.maps.ui.PubkyMapsTheme

/**
 * While a place page shows, the map screen reports the map it leaves in view, so a tapped place it
 * would hide can be moved into it: on a phone the map above the sheet and the controls riding on it
 * and under the bar; on a wide screen the map beside the panel, under the bar and above my location.
 * Measured against the controls as they are laid out.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class UncoveredMapTest {
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

    private val page =
        PlacePageState(
            PlaceRef(
                uri = "https://www.openstreetmap.org/node/270803095",
                name = "Conditorei Schober",
                point = GeoPoint(47.3722, 8.5446),
                osm = OsmId(OsmType.Node, 270803095),
                source = PlaceSource.Pinakes,
                address = "Napfgasse 4, 8001 Zürich",
                category = "Café",
            ),
            loading = emptySet(),
        )

    private val actions =
        PlacePageActions(onClose = {}, onShare = {}, openIn = emptyList(), onOpenIn = {}, onOpenLink = {}, onLoadMorePosts = {})

    private var reported: DpRect? = null
    private var reports = 0
    private var shown by mutableStateOf(true)

    private fun show(
        placeShown: Boolean,
        direction: LayoutDirection = LayoutDirection.Ltr,
    ) {
        shown = placeShown
        compose.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides direction) {
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
                            if (shown) {
                                PlacePageSlot(
                                    full = false,
                                    sheet = {
                                        maxHeight,
                                        inset,
                                        ->
                                        PlaceSheet(page, context, actions, SheetStop.Peek, {}, maxHeight, bottomInset = inset)
                                    },
                                    panel = { modifier -> PlacePanel(page, context, actions, modifier) },
                                )
                            } else {
                                null
                            },
                        onUncoveredMap = {
                            reported = it
                            reports++
                        },
                    ) { Box(Modifier.fillMaxSize()) }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun bounds(description: String) = compose.onNodeWithContentDescription(description).getUnclippedBoundsInRoot()

    @Test
    @Config(qualifiers = "w412dp-h915dp-notnight-mdpi")
    fun onAPhoneTheMapInViewEndsAboveTheSheetAndItsControls() {
        show(placeShown = true)
        val room = checkNotNull(reported)
        val settings = bounds("Settings")
        val myLocation = bounds("Show my location")
        val name = compose.onNodeWithText("Conditorei Schober").getUnclippedBoundsInRoot()
        assertTrue("the room starts at ${room.top}, under the bar at ${settings.bottom}", room.top >= settings.bottom)
        // The controls ride on the sheet: the room ends where my location starts, above the sheet.
        assertEquals(myLocation.top.value, room.bottom.value, 1f)
        assertTrue("the room ends at ${room.bottom}, the sheet's header at ${name.top}", room.bottom < name.top)
        assertEquals(0f, room.left.value, 0f)
        assertEquals(412f, room.right.value, 0f)
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp-notnight-mdpi")
    fun onAWideScreenTheMapInViewIsBesideThePanel() {
        show(placeShown = true)
        val room = checkNotNull(reported)
        val close = bounds("Close")
        val myLocation = bounds("Show my location")
        assertTrue("the room starts at ${room.left}, the panel ends after ${close.right}", room.left >= close.right)
        // The panel's edge: the margin and its width.
        assertEquals(412f, room.left.value, 0f)
        assertTrue("the room ends at ${room.bottom}, my location starts at ${myLocation.top}", room.bottom <= myLocation.top)
        assertTrue("the room starts at ${room.top}, under the bar at ${bounds("Settings").bottom}", room.top >= bounds("Settings").bottom)
        assertEquals(1280f, room.right.value, 0f)
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp-notnight-mdpi")
    fun rightToLeftTheMapInViewIsOnTheOtherSideOfThePanel() {
        show(placeShown = true, direction = LayoutDirection.Rtl)
        val room = checkNotNull(reported)
        val close = bounds("Close")
        assertTrue("the room ends at ${room.right}, the panel starts before ${close.left}", room.right <= close.left)
        assertEquals(1280f - 412f, room.right.value, 0f)
        assertEquals(0f, room.left.value, 0f)
    }

    @Test
    @Config(qualifiers = "w412dp-h915dp-notnight-mdpi")
    fun withoutAPlacePageNoRoomIsReported() {
        show(placeShown = false)
        assertNull(reported)
        assertTrue(reports > 0)
    }

    @Test
    @Config(qualifiers = "w412dp-h915dp-notnight-mdpi")
    fun closingThePlacePageReportsNoRoom() {
        show(placeShown = true)
        checkNotNull(reported)
        shown = false
        compose.waitForIdle()
        assertNull(reported)
    }
}
