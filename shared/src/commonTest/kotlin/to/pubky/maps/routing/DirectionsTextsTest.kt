package to.pubky.maps.routing

import to.pubky.maps.links.GeoPoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DirectionsTextsTest {
    @Test
    fun durationsAreMinutesThenHours() {
        assertEquals("1 min", durationText(5.0))
        assertEquals("5 min", durationText(289.373))
        assertEquals("59 min", durationText(59 * 60.0))
        assertEquals("1 h", durationText(3_600.0))
        assertEquals("1 h 5 min", durationText(3_900.0))
        assertEquals("26 h 40 min", durationText(96_000.0))
    }

    @Test
    fun eachStatusSaysWhatStandsInTheWay() {
        fun text(
            status: RouteStatus,
            mode: TravelMode = TravelMode.Walk,
        ) = routeStatusText(Directions("osm:node/1", GeoPoint(47.0, 8.0), mode, null, status))
        assertEquals("Finding your location…", text(RouteStatus.Locating))
        assertEquals("Finding a route on foot…", text(RouteStatus.Finding))
        assertEquals("Too far for a route on foot.", text(RouteStatus.NoRoute(NoRouteReason.TooFar)))
        assertEquals(
            "No route by bike from your location to this place.",
            text(RouteStatus.NoRoute(NoRouteReason.NotConnected), TravelMode.Bike),
        )
        assertEquals("Directions start from your location. Location access is off for Pubky Maps.", text(RouteStatus.NoLocation(true)))
        assertEquals("Can't reach the routing server. Check your connection.", text(RouteStatus.Unreachable))
        assertNull(text(RouteStatus.Found(Route(emptyList(), 1.0, 1.0), "s")))
    }
}
