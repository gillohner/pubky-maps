package to.pubky.maps.map

import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class KeepInViewTest {
    /** A phone with the place sheet at its lowest height: the map in view ends above the sheet's controls. */
    private val phone = DpRect(left = 0.dp, top = 72.dp, right = 412.dp, bottom = 600.dp)

    @Test
    fun aPlaceInTheMapLeftInViewStaysWhereItIs() {
        assertNull(panIntoView(DpOffset(200.dp, 300.dp), phone))
        assertNull(panIntoView(DpOffset(40.dp, 560.dp), phone))
    }

    @Test
    fun aPlaceUnderTheSheetMovesToTheMiddleOfTheMapInView() {
        val shift = panIntoView(DpOffset(300.dp, 800.dp), phone)
        // The middle of the room is (206, 336): the camera's centre moves by the place's distance to it.
        assertEquals(DpOffset(94.dp, 464.dp), shift)
    }

    @Test
    fun aPlaceAtTheEdgeOfTheMapInViewMovesToo() {
        // Its pin would sit on the controls riding on the sheet.
        assertEquals(DpOffset(0.dp, 254.dp), panIntoView(DpOffset(206.dp, 590.dp), phone))
        // Under the search bar.
        assertEquals(DpOffset(0.dp, -256.dp), panIntoView(DpOffset(206.dp, 80.dp), phone))
    }

    @Test
    fun aPlaceUnderTheSidePanelMovesBesideIt() {
        val tablet = DpRect(left = 412.dp, top = 72.dp, right = 1280.dp, bottom = 692.dp)
        val shift = panIntoView(DpOffset(200.dp, 400.dp), tablet)
        assertEquals(DpOffset(-646.dp, 18.dp), shift)
        assertNull(panIntoView(DpOffset(900.dp, 400.dp), tablet))
    }

    @Test
    fun aSheetThatTakesTheScreenMovesNothing() {
        assertNull(panIntoView(DpOffset(200.dp, 800.dp), DpRect(0.dp, 72.dp, 412.dp, 100.dp)))
        assertNull(panIntoView(DpOffset(200.dp, 800.dp), DpRect(0.dp, 72.dp, 412.dp, 40.dp)))
    }

    @Test
    fun theRoomIsTakenOnceItStopsChanging() =
        runTest {
            val rooms = MutableStateFlow<DpRect?>(null)
            var got: DpRect? = null
            val job = launch { got = settled(rooms).first() }
            runCurrent()
            // The sheet animating up: a new room every frame.
            for (bottom in listOf(900, 800, 700, 620, 600)) {
                rooms.value = phone.copy(bottom = bottom.dp)
                advanceTimeBy(16)
                runCurrent()
                assertNull(got)
            }
            advanceTimeBy(ROOM_SETTLE_MS)
            runCurrent()
            assertEquals(phone, got)
            assertTrue(job.isCompleted)
        }

    @Test
    fun noRoomWhileNoPlacePageShows() =
        runTest {
            val rooms = MutableStateFlow<DpRect?>(null)
            var got: DpRect? = null
            val job = launch { got = settled(rooms).first() }
            advanceTimeBy(ROOM_SETTLE_MS * 4)
            runCurrent()
            assertNull(got)
            // A room that goes away before it settles is not taken either.
            rooms.value = phone
            advanceTimeBy(ROOM_SETTLE_MS / 2)
            rooms.value = null
            advanceTimeBy(ROOM_SETTLE_MS * 4)
            runCurrent()
            assertNull(got)
            job.cancel()
        }
}
