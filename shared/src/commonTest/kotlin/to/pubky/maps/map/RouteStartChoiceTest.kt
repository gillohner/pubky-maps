package to.pubky.maps.map

import kotlinx.coroutines.test.runTest
import org.maplibre.compose.location.LocationPermission
import to.pubky.maps.links.GeoPoint
import to.pubky.maps.place.PlacePageState
import to.pubky.maps.place.PlaceRef
import to.pubky.maps.place.PlaceSource
import to.pubky.maps.routing.DirectionsController
import to.pubky.maps.routing.RouteAnswer
import to.pubky.maps.routing.RouteStart
import to.pubky.maps.routing.Routing
import to.pubky.maps.routing.TravelMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RouteStartChoiceTest {
    private val noRouting =
        object : Routing {
            override suspend fun route(
                from: GeoPoint,
                to: GeoPoint,
                mode: TravelMode,
            ): RouteAnswer = RouteAnswer.Unreachable
        }

    private val hotel =
        PlacePageState(
            PlaceRef("osm:node/9", "Hotel Adler", GeoPoint(47.3667, 8.545), osm = null, source = PlaceSource.Photon),
        )

    @Test
    fun startHereChoosesThePlaceUnderItsNameAndClearsItAgain() =
        runTest {
            val directions = DirectionsController(noRouting, this)
            val choice = assertNotNull(routeStartChoice(directions, directions.chosenStart.value, hotel))
            assertFalse(choice.chosen)
            choice.onToggle()
            assertEquals(RouteStart("osm:node/9", GeoPoint(47.3667, 8.545), "Hotel Adler"), directions.chosenStart.value)
            val chosen = assertNotNull(routeStartChoice(directions, directions.chosenStart.value, hotel))
            assertTrue(chosen.chosen)
            chosen.onToggle()
            assertNull(directions.chosenStart.value)
        }

    @Test
    fun anotherPlaceAsTheStartLeavesThisOneUnchosenAndATapMovesTheStartHere() =
        runTest {
            val directions = DirectionsController(noRouting, this)
            directions.chooseStart(RouteStart("osm:node/1", GeoPoint(47.0, 8.0), "Kunsthaus"))
            val choice = assertNotNull(routeStartChoice(directions, directions.chosenStart.value, hotel))
            assertFalse(choice.chosen)
            choice.onToggle()
            assertEquals("osm:node/9", directions.chosenStart.value?.placeUri)
        }

    @Test
    fun noStartHereWithoutRoutingOrAPoint() =
        runTest {
            assertNull(routeStartChoice(null, null, hotel))
            val noPoint = PlacePageState(PlaceRef("osm:node/9", "Hotel Adler", null, osm = null, source = PlaceSource.Photon))
            assertNull(routeStartChoice(DirectionsController(noRouting, this), null, noPoint))
        }

    @Test
    fun theStartReachedThroughAnotherLinkIsChosenOnItsPage() =
        runTest {
            val directions = DirectionsController(noRouting, this)
            directions.chooseStart(RouteStart("https://www.openstreetmap.org/node/9", GeoPoint(47.36671, 8.54502), "Hotel Adler"))
            assertTrue(assertNotNull(routeStartChoice(directions, directions.chosenStart.value, hotel)).chosen)
        }

    @Test
    fun aChosenStartAsksNothingAboutTheUsersPosition() {
        val off = LocationPermission.NotGranted(canRequest = true)
        assertNull(directionsLocationStep(fromChosenStart = true, off, trackingUnavailable = false, hasFix = false))
        assertNull(directionsLocationStep(fromChosenStart = true, off, trackingUnavailable = true, hasFix = false))
        assertEquals(
            MyLocationStep.RequestPermission,
            directionsLocationStep(fromChosenStart = false, off, trackingUnavailable = false, hasFix = false),
        )
    }
}
