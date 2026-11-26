package to.pubky.maps.map

import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import to.pubky.maps.links.GeoPoint
import to.pubky.maps.place.SheetStop
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
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
    fun theCameraCentreMovesByThePlacesDistanceToTheMiddleOfTheRoom() {
        val size = DpSize(412.dp, 915.dp)
        // The map's middle (206, 457.5) moves by (94, 464).
        assertEquals(DpOffset(300.dp, 921.5.dp), keepInViewCentre(DpOffset(300.dp, 800.dp), phone, size))
        assertNull(keepInViewCentre(DpOffset(200.dp, 300.dp), phone, size))
    }

    @Test
    fun aPlaceOffTheMapStaysWhereItIs() {
        val size = DpSize(412.dp, 915.dp)
        assertNull(keepInViewCentre(null, phone, size))
    }

    @Test
    fun aPlaceIsKeptInViewOncePerMapSize() {
        val uri = "https://www.openstreetmap.org/node/270803095"
        // The same place on the same map (the sheet dragged, back from Settings): already done.
        assertEquals(keptInViewKey(uri, DpSize(412.dp, 915.dp)), keptInViewKey(uri, DpSize(412.dp, 915.dp)))
        // Rotated or folded: the new layout may hide it, so it is checked again.
        assertNotEquals(keptInViewKey(uri, DpSize(412.dp, 915.dp)), keptInViewKey(uri, DpSize(915.dp, 412.dp)))
        assertNotEquals(keptInViewKey(uri, DpSize(412.dp, 915.dp)), keptInViewKey("geo:47.37,8.54", DpSize(412.dp, 915.dp)))
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

    /** A map that shows the tapped place at [at] on the screen and records where it was centred. */
    private class FakeMap(
        var at: DpOffset?,
        override var size: DpSize? = DpSize(412.dp, 915.dp),
    ) : KeepInViewMap {
        override val moving = MutableStateFlow(false)
        val centred = mutableListOf<DpOffset>()

        override fun screenPoint(point: GeoPoint) = at

        override suspend fun centreOn(centre: DpOffset) {
            centred += centre
            // The place is now in the middle of the map (the pan is not undone by a later room).
            at = centre
        }
    }

    /** One tap kept in view by [keepTappedInView], driven from the test. */
    private class Tap(
        scope: TestScope,
        val map: FakeMap,
        var kept: String? = null,
    ) {
        val rooms = MutableStateFlow<DpRect?>(null)
        val done = MutableStateFlow(false)
        val userMoved = MutableStateFlow(false)
        val stop = MutableStateFlow(SheetStop.Peek)
        val job =
            scope.launch {
                keepTappedInView(
                    uri = "https://www.openstreetmap.org/node/1",
                    point = GeoPoint(47.37, 8.54),
                    rooms = rooms,
                    pageDone = done,
                    userMoved = userMoved,
                    sheetStops = stop,
                    map = map,
                    kept = { kept },
                    onKept = { kept = it },
                )
            }
    }

    private fun TestScope.settle() {
        advanceTimeBy(ROOM_SETTLE_MS + 1)
        runCurrent()
    }

    /** The phone's room once the card made the sheet's header taller: the controls moved up 60 dp. */
    private val phoneAfterCard = DpRect(left = 0.dp, top = 72.dp, right = 412.dp, bottom = 540.dp)

    @Test
    fun aPlaceTheGrownHeaderHidesMovesOnceTheCardArrives() =
        runTest {
            // Just above the first header's controls, in view at the first settle.
            val tap = Tap(this, FakeMap(at = DpOffset(200.dp, 550.dp)))
            tap.rooms.value = phone
            settle()
            assertTrue(tap.map.centred.isEmpty())
            // The card arrives: the header grows, the controls ride up over the place.
            tap.rooms.value = phoneAfterCard
            tap.done.value = true
            settle()
            assertEquals(listOf(DpOffset(200.dp, 915.dp / 2 + (550.dp - 306.dp))), tap.map.centred)
            tap.job.cancel()
        }

    @Test
    fun aHiddenPlaceMovesOnceAndADraggedSheetMovesNothingAfter() =
        runTest {
            val tap = Tap(this, FakeMap(at = DpOffset(300.dp, 800.dp)))
            tap.rooms.value = phone
            settle()
            assertEquals(1, tap.map.centred.size)
            // The user drags the sheet up over the moved place: the map stays.
            tap.map.at = DpOffset(300.dp, 400.dp)
            tap.rooms.value = phone.copy(bottom = 300.dp)
            tap.done.value = true
            settle()
            assertEquals(1, tap.map.centred.size)
            tap.job.cancel()
        }

    @Test
    fun onceThePageHasLoadedARoomThatShowsThePlaceIsFinal() =
        runTest {
            val tap = Tap(this, FakeMap(at = DpOffset(200.dp, 300.dp)))
            tap.rooms.value = phone
            settle()
            assertNull(tap.kept)
            // Every part answered while the room stayed the same: that room is the one judged.
            tap.done.value = true
            settle()
            assertEquals(keptInViewKey("https://www.openstreetmap.org/node/1", DpSize(412.dp, 915.dp)), tap.kept)
            // A sheet dragged over the place afterwards moves nothing.
            tap.rooms.value = phone.copy(bottom = 250.dp)
            settle()
            assertTrue(tap.map.centred.isEmpty())
            tap.job.cancel()
        }

    @Test
    fun nothingMovesAfterTheUserMovedTheMap() =
        runTest {
            val tap = Tap(this, FakeMap(at = DpOffset(200.dp, 550.dp)))
            tap.rooms.value = phone
            settle()
            tap.userMoved.value = true
            runCurrent()
            tap.rooms.value = phoneAfterCard
            tap.done.value = true
            settle()
            assertTrue(tap.map.centred.isEmpty())
            tap.job.cancel()
        }

    @Test
    fun aRoomSeenWhileTheCameraMovesIsLeftAlone() =
        runTest {
            val map = FakeMap(at = DpOffset(300.dp, 800.dp)).apply { moving.value = true }
            val tap = Tap(this, map)
            tap.rooms.value = phone
            settle()
            assertTrue(map.centred.isEmpty())
            assertNull(tap.kept)
            // The fling ends, the room unchanged: the place is judged now.
            map.moving.value = false
            settle()
            assertEquals(1, map.centred.size)
            tap.job.cancel()
        }

    @Test
    fun aRoomThatKeepsChangingMovesNothing() =
        runTest {
            val tap = Tap(this, FakeMap(at = DpOffset(300.dp, 800.dp)))
            repeat(30) { frame ->
                tap.rooms.value = phone.copy(bottom = 600.dp - frame.dp)
                advanceTimeBy(16)
                runCurrent()
            }
            assertTrue(tap.map.centred.isEmpty())
            tap.job.cancel()
        }

    @Test
    fun theSameTapAgainMovesNothingButANewMapSizeIsCheckedAgain() =
        runTest {
            val size = DpSize(412.dp, 915.dp)
            val map = FakeMap(at = DpOffset(300.dp, 800.dp), size = size)
            // Back from Settings: the tap was kept at this size already.
            val again = Tap(this, map, kept = keptInViewKey("https://www.openstreetmap.org/node/1", size))
            again.rooms.value = phone
            settle()
            assertTrue(map.centred.isEmpty())
            // Turned to landscape: the new layout hides the place, so it moves.
            map.size = DpSize(915.dp, 412.dp)
            again.rooms.value = DpRect(0.dp, 72.dp, 915.dp, 300.dp)
            settle()
            assertEquals(1, map.centred.size)
            again.job.cancel()
        }

    @Test
    fun aSheetDraggedWhileThePageLoadsMovesNothing() =
        runTest {
            val tap = Tap(this, FakeMap(at = DpOffset(200.dp, 550.dp)))
            tap.rooms.value = phone
            settle()
            // The user pulls the sheet up to half before the page has loaded: its room is theirs.
            tap.stop.value = SheetStop.Half
            runCurrent()
            tap.rooms.value = phone.copy(bottom = 457.dp)
            settle()
            assertTrue(tap.map.centred.isEmpty())
            assertEquals(keptInViewKey("https://www.openstreetmap.org/node/1", DpSize(412.dp, 915.dp)), tap.kept)
            tap.job.cancel()
        }
}
