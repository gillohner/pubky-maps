package to.pubky.maps.routing

import to.pubky.maps.links.GeoPoint
import to.pubky.maps.ui.AppIcons
import kotlin.test.Test
import kotlin.test.assertSame

class RoutingIconsTest {
    private fun directions(
        reversed: Boolean,
        from: RouteStart? = null,
    ) = Directions("osm:node/1", GeoPoint(47.0, 8.0), TravelMode.Walk, GeoPoint(47.01, 8.01), RouteStatus.Locating, reversed, from)

    private val hotel = RouteStart("osm:node/9", GeoPoint(47.02, 8.02), "Hotel Adler")

    @Test
    fun aRouteToThePlaceArrivesAtItsPin() {
        assertSame(AppIcons.Place, directions(reversed = false).stepIcon(Turn.Arrive))
        assertSame(AppIcons.Place, directions(reversed = false, from = hotel).stepIcon(Turn.Arrive))
    }

    @Test
    fun aRouteBackToTheUserArrivesAtTheirLocation() {
        assertSame(AppIcons.MyLocation, directions(reversed = true).stepIcon(Turn.Arrive))
    }

    @Test
    fun aRouteBackToAChosenStartArrivesAtAPlacesPin() {
        assertSame(AppIcons.Place, directions(reversed = true, from = hotel).stepIcon(Turn.Arrive))
    }

    @Test
    fun theOtherStepsKeepTheirTurnsIconWhicheverWayTheRouteGoes() {
        for (reversed in listOf(false, true)) {
            assertSame(RoutingIcons.TripOrigin, directions(reversed).stepIcon(Turn.Depart))
            assertSame(RoutingIcons.TurnLeft, directions(reversed).stepIcon(Turn.Left))
        }
    }
}
