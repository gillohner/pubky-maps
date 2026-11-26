package to.pubky.maps.map

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import to.pubky.maps.links.GeoPoint
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.tan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ShowPlaceTest {
    private val phone = DpSize(412.dp, 915.dp)

    /** A phone with a picked place's sheet at half: under the search bar, above the controls riding on the sheet. */
    private val phoneAtHalf = DpRect(left = 0.dp, top = 92.dp, right = 412.dp, bottom = 430.dp)

    private val tablet = DpSize(1280.dp, 800.dp)

    /** Beside the place panel, above the credits. */
    private val besidePanel = DpRect(left = 412.dp, top = 92.dp, right = 1280.dp, bottom = 692.dp)

    private val kunsthaus = GeoPoint(47.3704, 8.5481)

    /**
     * Where [point] shows on a map of [size] centred on [centre]: Web Mercator at 512 dp a world at
     * zoom 0, worked out here on its own so the test does not check the code against itself.
     */
    private fun onScreen(
        point: GeoPoint,
        centre: GeoPoint,
        size: DpSize,
        zoom: Double,
        bearing: Double = 0.0,
    ): DpOffset {
        val world = 512 * 2.0.pow(zoom)

        fun x(p: GeoPoint) = (p.lon + 180) / 360 * world

        fun y(p: GeoPoint) = (1 - ln(tan(PI / 4 + p.lat * PI / 360)) / PI) / 2 * world
        var wx = x(point) - x(centre)
        if (wx > world / 2) wx -= world
        if (wx < -world / 2) wx += world
        val wy = y(point) - y(centre)
        val turn = bearing * PI / 180
        val dx = wx * cos(turn) + wy * sin(turn)
        val dy = -wx * sin(turn) + wy * cos(turn)
        return DpOffset(size.width / 2 + Dp(dx.toFloat()), size.height / 2 + Dp(dy.toFloat()))
    }

    private fun assertAt(
        expected: DpOffset,
        actual: DpOffset,
    ) {
        assertTrue(
            abs((expected.x - actual.x).value) < 0.5f && abs((expected.y - actual.y).value) < 0.5f,
            "expected $expected, got $actual",
        )
    }

    @Test
    fun aPickedPlaceLandsInTheMiddleOfTheMapAboveItsSheet() {
        val centre = centreShowing(kunsthaus, phoneAtHalf, phone, zoom = 16.0, bearing = 0.0)
        // The middle of the room, (206, 261); the map's own middle (206, 457.5) is on the controls.
        assertAt(DpOffset(206.dp, 261.dp), onScreen(kunsthaus, centre, phone, 16.0))
        assertTrue(centre.lat < kunsthaus.lat, "the camera looks south of the place, so it shows higher up")
    }

    @Test
    fun aPickedPlaceLandsBesideThePanelOnAWideScreen() {
        val centre = centreShowing(kunsthaus, besidePanel, tablet, zoom = 17.0, bearing = 0.0)
        assertAt(DpOffset(846.dp, 392.dp), onScreen(kunsthaus, centre, tablet, 17.0))
    }

    @Test
    fun theShiftIsTheRoomsDistanceFromTheMiddleAtTheZoomTheMapGoesTo() {
        // At the equator at zoom 16 a dp is 360 / (512 * 2^16) degrees: 200 dp up is 0.0021458 degrees.
        val room = DpRect(0.dp, 0.dp, 412.dp, 515.dp) // middle (206, 257.5): 200 dp above the map's
        val centre = centreShowing(GeoPoint(0.0, 10.0), room, phone, zoom = 16.0, bearing = 0.0)
        assertEquals(-0.0021458, centre.lat, 1e-6)
        assertEquals(10.0, centre.lon, 1e-9)
    }

    @Test
    fun onATurnedMapTheShiftTurnsWithIt() {
        // The screen's top faces east: the place goes above the middle, so the camera looks west of it.
        val centre = centreShowing(kunsthaus, phoneAtHalf, phone, zoom = 16.0, bearing = 90.0)
        assertEquals(kunsthaus.lat, centre.lat, 1e-6)
        assertTrue(centre.lon < kunsthaus.lon)
        assertAt(DpOffset(206.dp, 261.dp), onScreen(kunsthaus, centre, phone, 16.0, bearing = 90.0))
        // And turned by 30 degrees.
        val turned = centreShowing(kunsthaus, besidePanel, tablet, zoom = 15.0, bearing = 30.0)
        assertAt(DpOffset(846.dp, 392.dp), onScreen(kunsthaus, turned, tablet, 15.0, bearing = 30.0))
    }

    @Test
    fun aPlaceByTheAntimeridianStaysOnTheMap() {
        val room = DpRect(312.dp, 0.dp, 412.dp, 400.dp) // to the right: the camera looks west of the place
        val east = GeoPoint(10.0, -179.99999)
        val centre = centreShowing(east, room, phone, zoom = 16.0, bearing = 0.0)
        assertTrue(centre.lon in 179.0..180.0, "wrapped to ${centre.lon}")
        assertAt(DpOffset(362.dp, 200.dp), onScreen(east, centre, phone, 16.0))
    }

    @Test
    fun withoutARoomThePlaceGoesToTheMapsMiddle() {
        assertEquals(kunsthaus, centreShowing(kunsthaus, null, phone, 16.0, 0.0))
        assertEquals(kunsthaus, centreShowing(kunsthaus, phoneAtHalf, null, 16.0, 0.0))
        // The page takes the whole screen.
        assertEquals(kunsthaus, centreShowing(kunsthaus, DpRect(0.dp, 92.dp, 412.dp, 120.dp), phone, 16.0, 0.0))
    }

    @Test
    fun theRoomIsTakenOnceThePageHasOpened() =
        runTest {
            val rooms = MutableStateFlow<DpRect?>(null)
            var got: DpRect? = null
            var done = false
            val job =
                launch {
                    got = openedRoom(rooms)
                    done = true
                }
            runCurrent()
            advanceTimeBy(16)
            // A page at another height animates to its stop, one room a frame.
            for (bottom in 600 downTo 430 step 17) {
                rooms.value = DpRect(0.dp, 92.dp, 412.dp, bottom.dp)
                advanceTimeBy(16)
            }
            assertTrue(!done)
            advanceTimeBy(OPEN_ROOM_SETTLE_MS + 1)
            assertEquals(DpRect(0.dp, 92.dp, 412.dp, 430.dp), got)
            job.join()
        }

    @Test
    fun withoutAPageTheMoveWaitsOnlySoLong() =
        runTest {
            var got: DpRect? = DpRect(0.dp, 0.dp, 1.dp, 1.dp)
            var done = false
            launch {
                got = openedRoom(MutableStateFlow(null))
                done = true
            }
            advanceTimeBy(OPEN_ROOM_WAIT_MS - 1)
            assertTrue(!done)
            advanceTimeBy(2)
            assertTrue(done)
            assertNull(got)
        }
}
