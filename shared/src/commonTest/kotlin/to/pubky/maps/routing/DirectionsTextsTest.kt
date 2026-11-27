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
    fun aStepSaysWhereItIsInTheRouteAndHowFarItGoes() {
        assertEquals("Step 2 of 9 · 50 m", stepPositionText(1, 9, 52.0))
        assertEquals("Step 1 of 9 · 1.4 km", stepPositionText(0, 9, 1_401.0))
        // The end, and a step of a few metres, have no distance.
        assertEquals("Step 9 of 9", stepPositionText(8, 9, 0.0))
        assertEquals("Step 3 of 9", stepPositionText(2, 9, 4.9))
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

    @Test
    fun swappedDirectionsSayTheyEndAtTheUsersLocation() {
        fun directions(
            status: RouteStatus,
            reversed: Boolean,
        ) = Directions("osm:node/1", GeoPoint(47.0, 8.0), TravelMode.Walk, null, status, reversed = reversed)
        val found = RouteStatus.Found(Route(emptyList(), 1.0, 1.0), "s")
        assertEquals("On foot from your location", routeWayText(directions(found, reversed = false)))
        assertEquals("On foot to your location", routeWayText(directions(found, reversed = true)))
        assertEquals(
            "No route on foot from this place to your location.",
            routeStatusText(directions(RouteStatus.NoRoute(NoRouteReason.NotConnected), reversed = true)),
        )
        assertEquals(
            "Directions end at your location. Is location turned on?",
            routeStatusText(directions(RouteStatus.NoLocation(false), reversed = true)),
        )
        assertEquals(
            "Directions start from your location. Is location turned on?",
            routeStatusText(directions(RouteStatus.NoLocation(false), reversed = false)),
        )
        // The way round does not change what is waited for, nor a reason that names both ends.
        assertEquals("Finding your location…", routeStatusText(directions(RouteStatus.Locating, reversed = true)))
        assertEquals(
            "No road or path on foot near your location or the place.",
            routeStatusText(directions(RouteStatus.NoRoute(NoRouteReason.NoRoadNear), reversed = true)),
        )
    }

    @Test
    fun aChosenStartIsNamedWhereTheUsersLocationWouldBe() {
        val hotel = RouteStart("osm:node/9", GeoPoint(47.3667, 8.545), "Hotel Adler")

        fun directions(
            status: RouteStatus,
            reversed: Boolean = false,
        ) = Directions("osm:node/1", GeoPoint(47.0, 8.0), TravelMode.Walk, null, status, reversed = reversed, from = hotel)
        val found = RouteStatus.Found(Route(emptyList(), 60.0, 80.0), "s")
        assertEquals("On foot from Hotel Adler", routeWayText(directions(found)))
        assertEquals("On foot to Hotel Adler", routeWayText(directions(found, reversed = true)))
        assertEquals(
            "No route on foot from Hotel Adler to this place.",
            routeStatusText(directions(RouteStatus.NoRoute(NoRouteReason.NotConnected))),
        )
        assertEquals(
            "No route on foot from this place to Hotel Adler.",
            routeStatusText(directions(RouteStatus.NoRoute(NoRouteReason.NotConnected), reversed = true)),
        )
        assertEquals(
            "No road or path on foot near Hotel Adler or the place.",
            routeStatusText(directions(RouteStatus.NoRoute(NoRouteReason.NoRoadNear))),
        )
    }
}
