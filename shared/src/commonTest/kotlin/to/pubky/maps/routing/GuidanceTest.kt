package to.pubky.maps.routing

import kotlinx.datetime.TimeZone
import to.pubky.maps.links.GeoPoint
import to.pubky.maps.map.arrivalText
import to.pubky.maps.search.Fixtures
import to.pubky.maps.search.formatDistance
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

class GuidanceTest {
    // FOSSGIS' answer by car from Zürich HB to the Kunsthaus: 94 points, steps at 0, 7, 12, 13, 36, 56, 65, 85, 93.
    private val route = parseValhallaRoute(Fixtures.read("hauptbahnhof-kunsthaus.valhalla.json"))
    private val along = cumulativeMeters(route.points)

    @Test
    fun theLineIsAboutAsLongAsTheServerSays() {
        assertEquals(route.meters, along.last(), route.meters * 0.02)
    }

    @Test
    fun atTheStartTheNextStepIsTheFirstTurn() {
        val p = routeProgress(route, route.points[0])
        assertEquals(1, p.nextStep)
        assertEquals("Turn right onto Museumstrasse.", route.steps[p.nextStep!!].instruction)
        assertEquals(along[7], p.toNextStep, 0.5)
        assertEquals(along.last(), p.metersLeft, 0.5)
        assertEquals(route.seconds, p.secondsLeft, 0.5)
        assertFalse(p.offRoute)
        assertFalse(p.arrived)
        // "Drive north.": the map turns to the north.
        assertTrue(p.ahead < 30 || p.ahead > 330, "ahead ${p.ahead}")
    }

    @Test
    fun onTheWayTheNextStepIsTheOneAheadAndTheTimeLeftIsItsShare() {
        // On Bahnhofquai, past "Keep left to stay on Bahnhofquai." (13): next is the bridge (36).
        val at = midpoint(route.points[20], route.points[21])
        val p = routeProgress(route, at)
        assertEquals(20, p.segment)
        assertEquals(4, p.nextStep)
        val done = (along[20] + along[21]) / 2
        assertEquals(along[36] - done, p.toNextStep, 1.0)
        assertEquals(along.last() - done, p.metersLeft, 1.0)
        assertEquals(route.seconds * p.metersLeft / along.last(), p.secondsLeft, 0.01)
        assertTrue(p.offBy < 1.0)
        // Bahnhofquai runs south along the Limmat.
        assertTrue(p.ahead in 135.0..225.0, "ahead ${p.ahead}")
    }

    @Test
    fun aPositionAStreetAwayIsStillOnTheRouteAndOneFurtherIsOff() {
        val on = routeProgress(route, east(route.points[20], 30.0))
        assertFalse(on.offRoute)
        assertEquals(4, on.nextStep)
        val off = routeProgress(route, east(route.points[20], 120.0), previousAlong = along[20])
        assertTrue(off.offRoute)
        assertEquals(120.0, off.offBy, 25.0)
        // A fix that may be 150 m off is not taken for a wrong turn.
        assertFalse(routeProgress(route, east(route.points[20], 120.0), previousAlong = along[20], accuracyMeters = 150.0).offRoute)
    }

    @Test
    fun atTheEndTheUserHasArrived() {
        val p = routeProgress(route, route.points.last(), previousAlong = along[80])
        assertTrue(p.arrived)
        assertEquals(route.steps.lastIndex, p.nextStep)
        assertEquals(0.0, p.metersLeft, 0.5)
        // 40 m before the end: not yet.
        val before = routeProgress(route, pointAlong(along.last() - 40), previousAlong = along[80])
        assertFalse(before.arrived)
    }

    @Test
    fun oneFixThatJumpedAheadIsUndoneByTheNext() {
        // A fix 30 m beside the line at segment 40 (an urban canyon), then the user where they are.
        val jumped = routeProgress(route, east(route.points[40], 30.0), previousAlong = along[3])
        assertFalse(jumped.offRoute)
        val back = routeProgress(route, route.points[4], previousAlong = jumped.onRouteAlong)
        assertFalse(back.offRoute)
        assertEquals(along[4], back.along, 1.0)
    }

    @Test
    fun offTheRouteTheUserIsLookedForWhereTheyLeftIt() {
        val off = routeProgress(route, east(route.points[20], 120.0), previousAlong = along[20])
        assertTrue(off.offRoute)
        assertEquals(along[20], off.onRouteAlong)
    }

    @Test
    fun aRouteThereAndBackTakesThePartTheUserIsOn() {
        val a = GeoPoint(47.0, 8.0)
        val b = GeoPoint(47.0, 8.01)
        val back =
            Route(listOf(a, b, a), seconds = 200.0, meters = 1520.0, steps = listOf(RouteStep("Turn around.", Turn.UTurnLeft, 760.0, b, 1)))
        val quarter = GeoPoint(47.0, 8.0025)
        val there = routeProgress(back, quarter)
        assertEquals(0, there.segment)
        assertEquals(0, there.nextStep)
        // Past the turn, the same street is the way back.
        val afterTurn = routeProgress(back, quarter, previousAlong = 800.0)
        assertEquals(1, afterTurn.segment)
        assertEquals(null, afterTurn.nextStep)
        assertTrue(afterTurn.metersLeft < 300, "left ${afterTurn.metersLeft}")
    }

    @Test
    fun theBannerSaysTheNextStepItsDistanceAndNowWhenItIsHere() {
        val d = guiding(routeProgress(route, route.points[0]))
        val text = guidanceText(d, "Kunsthaus Zürich")
        assertEquals("Turn right onto Museumstrasse.", text.detail)
        assertEquals(GuidanceIcon.Step(Turn.Right), text.icon)
        assertTrue(text.title.endsWith(" m"), text.title)
        // The distance changes with every few metres: TalkBack reads the step instead.
        assertTrue(text.titleIsDistance)
        assertFalse(text.routeAgain)
        val near = guidanceText(guiding(routeProgress(route, pointAlong(along[7] - 4))), "Kunsthaus Zürich")
        assertEquals("Now", near.title)
        assertEquals("5 min · 1.4 km", guidanceLeftText(d))
    }

    @Test
    fun theBannerSaysArrivedOffTheRouteAndWhyThereIsNoNewRoute() {
        val arrived = guidanceText(guiding(routeProgress(route, route.points.last(), previousAlong = along[80])), "Kunsthaus Zürich")
        assertEquals(GuidanceText("You have arrived", "Kunsthaus Zürich", GuidanceIcon.Arrived), arrived)
        val off = guidanceText(guiding(routeProgress(route, east(route.points[20], 120.0), previousAlong = along[20])), "Kunsthaus Zürich")
        assertEquals("You're off the route", off.title)
        assertTrue(off.routeAgain)
        assertFalse(off.titleIsDistance)
        val offBy120 = guiding(routeProgress(route, east(route.points[20], 120.0), previousAlong = along[20]))
        val finding = guidanceText(offBy120.copy(rerouting = true), "K")
        assertEquals("Finding a new route…", finding.title)
        assertEquals("You're about ${formatDistance(offBy120.progress!!.offBy)} off the route", finding.detail)
        assertFalse(finding.routeAgain)
        val failed = guidanceText(offBy120.copy(noNewRoute = RouteStatus.Unreachable), "K")
        assertEquals(
            GuidanceText("No new route", "Can't reach the routing server. Check your connection.", GuidanceIcon.Other, routeAgain = true),
            failed,
        )
        // Back on the route, a new route not found is no longer the banner's news.
        val onIt = guiding(routeProgress(route, route.points[20], previousAlong = along[20])).copy(noNewRoute = RouteStatus.Unreachable)
        assertTrue(guidanceText(onIt, "K").titleIsDistance)
        assertFalse(guidanceText(onIt, "K").routeAgain)
        assertEquals("Finding your location…", guidanceText(guiding(null), "K").title)
        assertEquals(null, guidanceLeftText(guiding(null)))
    }

    @Test
    fun theArrivalIsTheClockTimeInTheUsersZone() {
        assertEquals("13:05", arrivalText(Instant.parse("2026-11-25T12:05:00Z"), TimeZone.of("Europe/Zurich")))
        assertEquals("00:00", arrivalText(Instant.parse("2026-11-25T23:00:00Z"), TimeZone.of("Europe/Zurich")))
    }

    private fun guiding(progress: RouteProgress?) =
        Directions(
            "osm:way/1",
            route.points.last(),
            TravelMode.Car,
            route.points[0],
            RouteStatus.Found(route, "https://v.test"),
            guiding = true,
            progress = progress,
        )

    private fun midpoint(
        a: GeoPoint,
        b: GeoPoint,
    ) = GeoPoint((a.lat + b.lat) / 2, (a.lon + b.lon) / 2)

    /** [meters] east of [p]. */
    private fun east(
        p: GeoPoint,
        meters: Double,
    ) = GeoPoint(p.lat, p.lon + meters / (111_195.0 * kotlin.math.cos(p.lat * kotlin.math.PI / 180)))

    /** The point [meters] along the route's line. */
    private fun pointAlong(meters: Double): GeoPoint {
        val i = (0 until along.size - 1).first { along[it + 1] >= meters }
        val t = (meters - along[i]) / (along[i + 1] - along[i])
        val a = route.points[i]
        val b = route.points[i + 1]
        return GeoPoint(a.lat + t * (b.lat - a.lat), a.lon + t * (b.lon - a.lon))
    }
}
