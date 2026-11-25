package to.pubky.maps.place

import kotlin.test.Test
import kotlin.test.assertEquals

class PlaceSheetTest {
    private val stops = SheetStops(peek = 300f, half = 900f, full = 1800f)

    @Test
    fun aSlowReleaseGoesToTheNearestStop() {
        assertEquals(SheetStop.Peek, stops.settle(500f, velocity = 0f))
        assertEquals(SheetStop.Half, stops.settle(700f, velocity = 0f))
        assertEquals(SheetStop.Full, stops.settle(1500f, velocity = 100f))
    }

    @Test
    fun aFlingGoesToTheNextStopInItsDirection() {
        assertEquals(SheetStop.Half, stops.settle(320f, velocity = 2000f))
        assertEquals(SheetStop.Full, stops.settle(950f, velocity = 2000f))
        assertEquals(SheetStop.Half, stops.settle(1700f, velocity = -2000f))
        assertEquals(SheetStop.Peek, stops.settle(850f, velocity = -2000f))
        assertEquals(SheetStop.Full, stops.settle(1800f, velocity = 2000f))
        assertEquals(SheetStop.Peek, stops.settle(300f, velocity = -2000f))
    }

    @Test
    fun dragsStayBetweenTheLowestAndHighestStop() {
        assertEquals(300f, stops.clamp(10f))
        assertEquals(1800f, stops.clamp(5000f))
        // A short screen: half is never below peek.
        assertEquals(300f, SheetStops(peek = 300f, half = 200f, full = 400f).height(SheetStop.Half))
    }

    @Test
    fun aTapOnTheHandleStepsUpThenBack() {
        assertEquals(SheetStop.Half, SheetStops.afterTap(SheetStop.Peek))
        assertEquals(SheetStop.Full, SheetStops.afterTap(SheetStop.Half))
        assertEquals(SheetStop.Half, SheetStops.afterTap(SheetStop.Full))
    }
}
