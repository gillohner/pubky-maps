package to.pubky.maps.map

import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.maplibre.compose.util.DpPadding
import to.pubky.maps.config.AppSettings
import to.pubky.maps.config.AttributionLink
import to.pubky.maps.config.ServerDefaults
import to.pubky.maps.config.Service
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class DirectionsRouteTest {
    @Test
    fun aRouteIsFittedInsideTheMapThePageLeavesInView() {
        // A phone: under the search bar, above the half-open sheet.
        val padding = routeFitPadding(DpRect(0.dp, 80.dp, 412.dp, 500.dp), DpSize(412.dp, 915.dp))
        assertEquals(DpPadding(left = 32.dp, top = 112.dp, right = 32.dp, bottom = 447.dp), padding)
        // A tablet: beside the panel.
        val wide = routeFitPadding(DpRect(424.dp, 80.dp, 1280.dp, 700.dp), DpSize(1280.dp, 800.dp))
        assertEquals(DpPadding(left = 456.dp, top = 112.dp, right = 32.dp, bottom = 132.dp), wide)
    }

    @Test
    fun aRouteWaitsWhileThePageCoversTheMap() {
        // The phone sheet at its top: no map left under the bar (less than none under the notices),
        // or a strip when the page is short.
        assertFalse(PageRoom(DpRect(0.dp, 80.dp, 412.dp, 80.dp), sheetAtTop = true).holdsRoute)
        assertFalse(PageRoom(DpRect(0.dp, 120.dp, 412.dp, 80.dp), sheetAtTop = true).holdsRoute)
        assertFalse(PageRoom(DpRect(0.dp, 80.dp, 412.dp, 200.dp), sheetAtTop = true).holdsRoute)
        // A short page at its top that leaves much of the map.
        assertTrue(PageRoom(DpRect(0.dp, 80.dp, 412.dp, 636.dp), sheetAtTop = true).holdsRoute)
        // A lower stop, however small its room; the panel of a wide screen.
        assertTrue(PageRoom(DpRect(0.dp, 68.dp, 412.dp, 185.dp), sheetAtTop = false).holdsRoute)
        assertTrue(PageRoom(DpRect(0.dp, 92.dp, 412.dp, 137.dp), sheetAtTop = false).holdsRoute)
        assertTrue(PageRoom(DpRect(424.dp, 80.dp, 1280.dp, 800.dp), sheetAtTop = false).holdsRoute)
    }

    @Test
    fun aRouteWaitsWhileThereIsAboutNoMapInView() {
        // The sheet dragged up to its top: it is not at its top stop until let go.
        assertFalse(PageRoom(DpRect(0.dp, 80.dp, 412.dp, 80.dp), sheetAtTop = false).holdsRoute)
        assertFalse(PageRoom(DpRect(0.dp, 80.dp, 412.dp, 100.dp), sheetAtTop = false).holdsRoute)
    }

    @Test
    fun aSmallRoomKeepsAtLeastHalfOfItForTheRoute() {
        // The reference phone at the largest font: Peek leaves 117 dp between the bar and my location.
        assertEquals(
            DpPadding(left = 32.dp, top = 97.25.dp, right = 32.dp, bottom = 759.25.dp),
            routeFitPadding(DpRect(0.dp, 68.dp, 412.dp, 185.dp), DpSize(412.dp, 915.dp)),
        )
        val padding = routeFitPadding(DpRect(0.dp, 68.dp, 412.dp, 128.dp), DpSize(412.dp, 640.dp))
        assertEquals(15.dp, padding.top - 68.dp)
        assertEquals(15.dp, padding.bottom - 512.dp)
    }

    @Test
    fun aRouteFoundWithTheSheetAtItsTopIsFittedWhereTheSheetStops() =
        runTest {
            // The steps open at Full: the map left has no height.
            val rooms = MutableStateFlow<PageRoom?>(PageRoom(DpRect(0.dp, 80.dp, 412.dp, 80.dp), sheetAtTop = true))
            var got: DpRect? = null
            val job = launch { got = routeRoom(rooms) }
            advanceTimeBy(ROOM_SETTLE_MS * 4)
            runCurrent()
            assertNull(got)
            assertTrue(job.isActive)
            // The sheet lowered to Half: a new, taller room every frame.
            for (bottom in listOf(160, 240, 320, 400, 460)) {
                rooms.value = PageRoom(DpRect(0.dp, 80.dp, 412.dp, bottom.dp), sheetAtTop = false)
                advanceTimeBy(16)
                runCurrent()
                assertNull(got)
            }
            val half = DpRect(0.dp, 80.dp, 412.dp, 500.dp)
            rooms.value = PageRoom(half, sheetAtTop = false)
            advanceTimeBy(ROOM_SETTLE_MS + 1)
            runCurrent()
            assertEquals(half, got)
            assertTrue(job.isCompleted)
        }

    @Test
    fun aShortPageAtItsTopStillCoversTheMap() =
        runTest {
            // A short page's top stop leaves a strip of map: the route waits for the sheet to come down.
            val rooms = MutableStateFlow<PageRoom?>(PageRoom(DpRect(0.dp, 80.dp, 412.dp, 200.dp), sheetAtTop = true))
            var got: DpRect? = null
            val job = launch { got = routeRoom(rooms) }
            advanceTimeBy(ROOM_SETTLE_MS * 4)
            runCurrent()
            assertNull(got)
            assertTrue(job.isActive)
            job.cancel()
        }

    @Test
    fun aRouteFoundAtRestIsFittedIntoTheRoomThere() =
        runTest {
            val half = DpRect(0.dp, 80.dp, 412.dp, 500.dp)
            var got: DpRect? = null
            launch { got = routeRoom(MutableStateFlow(PageRoom(half, sheetAtTop = false))) }
            advanceTimeBy(ROOM_SETTLE_MS + 1)
            runCurrent()
            assertEquals(half, got)
        }

    @Test
    fun aRouteFoundAtTheLowestStopIsFittedThereHoweverSmall() =
        runTest {
            // The reference phone at the largest font: 117 dp; with a status and a navigation bar, 45 dp.
            for (peek in listOf(DpRect(0.dp, 68.dp, 412.dp, 185.dp), DpRect(0.dp, 92.dp, 412.dp, 137.dp))) {
                var got: DpRect? = null
                launch { got = routeRoom(MutableStateFlow(PageRoom(peek, sheetAtTop = false))) }
                advanceTimeBy(ROOM_SETTLE_MS + 1)
                runCurrent()
                assertEquals(peek, got)
            }
        }

    @Test
    fun aRouteFoundAsTheSheetIsDraggedUpWaitsForTheMapInView() =
        runTest {
            val rooms = MutableStateFlow<PageRoom?>(PageRoom(DpRect(0.dp, 80.dp, 412.dp, 90.dp), sheetAtTop = false))
            var got: DpRect? = null
            val job = launch { got = routeRoom(rooms) }
            advanceTimeBy(ROOM_SETTLE_MS * 4)
            runCurrent()
            assertNull(got)
            // Let go: the sheet comes back to Half.
            val half = DpRect(0.dp, 80.dp, 412.dp, 500.dp)
            rooms.value = PageRoom(half, sheetAtTop = false)
            advanceTimeBy(ROOM_SETTLE_MS + 1)
            runCurrent()
            assertEquals(half, got)
            assertTrue(job.isCompleted)
        }

    @Test
    fun aRouteFoundAsThePageIsLaidOutAnewWaitsForItsRoom() =
        runTest {
            // Back from Settings with the steps open: the sheet reports its room in its first frames.
            val rooms = MutableStateFlow<PageRoom?>(null)
            var got: DpRect? = null
            val job = launch { got = routeRoom(rooms) }
            advanceTimeBy(100)
            rooms.value = PageRoom(DpRect(0.dp, 80.dp, 412.dp, 80.dp), sheetAtTop = true)
            advanceTimeBy(OPEN_ROOM_WAIT_MS * 4)
            runCurrent()
            assertNull(got)
            assertTrue(job.isActive)
            job.cancel()
        }

    @Test
    fun aRouteWhosePageReportsNoRoomIsFittedToTheWholeMap() =
        runTest {
            var done = false
            launch {
                assertNull(routeRoom(MutableStateFlow<PageRoom?>(null)))
                done = true
            }
            advanceTimeBy(OPEN_ROOM_WAIT_MS - 1)
            runCurrent()
            assertFalse(done)
            advanceTimeBy(2)
            runCurrent()
            assertTrue(done)
        }

    @Test
    fun withoutARoomYetTheBottomHalfIsLeftForTheSheet() {
        assertEquals(DpPadding(left = 32.dp, top = 136.dp, right = 32.dp, bottom = 400.dp), routeFitPadding(null, DpSize(412.dp, 800.dp)))
    }

    @Test
    fun theCreditsAreThoseOfTheServerThatFoundTheRouteAndTheFixTheMapLink() {
        val defaults = AppSettings().servers
        val fossgis = defaults[Service.Valhalla].first()
        assertEquals(fossgis.attribution + ServerDefaults.fixTheMap, routeCredits(defaults, fossgis.url))
        val withOwn = AppSettings().withServerAdded(Service.Valhalla, "https://valhalla.example.org")
        val own = (withOwn as AppSettings.AddServerResult.Added).settings.servers[Service.Valhalla].last()
        assertEquals(
            listOf(
                AttributionLink("valhalla.example.org", "https://valhalla.example.org"),
                ServerDefaults.openStreetMap,
                ServerDefaults.fixTheMap,
            ),
            routeCredits((withOwn as AppSettings.AddServerResult.Added).settings.servers, own.url),
        )
    }
}
