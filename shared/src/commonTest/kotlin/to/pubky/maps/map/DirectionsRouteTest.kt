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
        // The phone sheet at its top: the map left between the search bar and the sheet has no height.
        assertFalse(roomHoldsRoute(DpRect(0.dp, 80.dp, 412.dp, 80.dp)))
        assertFalse(roomHoldsRoute(DpRect(0.dp, 80.dp, 412.dp, 200.dp)))
        // At its lowest stop, with the header and the route card.
        assertTrue(roomHoldsRoute(DpRect(0.dp, 80.dp, 412.dp, 490.dp)))
        assertTrue(roomHoldsRoute(DpRect(424.dp, 80.dp, 1280.dp, 800.dp)))
    }

    @Test
    fun aRouteFoundWithTheSheetAtItsTopIsFittedWhereTheSheetStops() =
        runTest {
            // The steps open at Full: the map left has no height.
            val rooms = MutableStateFlow<DpRect?>(DpRect(0.dp, 80.dp, 412.dp, 80.dp))
            var got: DpRect? = null
            val job = launch { got = routeRoom(rooms) }
            advanceTimeBy(ROOM_SETTLE_MS * 4)
            runCurrent()
            assertNull(got)
            // The sheet lowered to Half: a new, taller room every frame, the first ones already tall
            // enough for a route.
            for (bottom in listOf(160, 240, 320, 400, 460)) {
                rooms.value = DpRect(0.dp, 80.dp, 412.dp, bottom.dp)
                advanceTimeBy(16)
                runCurrent()
                assertNull(got)
            }
            val half = DpRect(0.dp, 80.dp, 412.dp, 500.dp)
            rooms.value = half
            advanceTimeBy(ROOM_SETTLE_MS + 1)
            runCurrent()
            assertEquals(half, got)
            assertTrue(job.isCompleted)
        }

    @Test
    fun aRouteFoundAtRestIsFittedIntoTheRoomThere() =
        runTest {
            val half = DpRect(0.dp, 80.dp, 412.dp, 500.dp)
            var got: DpRect? = null
            launch { got = routeRoom(MutableStateFlow(half)) }
            advanceTimeBy(ROOM_SETTLE_MS + 1)
            runCurrent()
            assertEquals(half, got)
        }

    @Test
    fun aRouteFoundBeforeThePageReportsARoomIsFittedAtOnce() =
        runTest {
            var done = false
            launch {
                assertNull(routeRoom(MutableStateFlow(null)))
                done = true
            }
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
