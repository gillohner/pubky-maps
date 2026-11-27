package to.pubky.maps.map

import to.pubky.maps.links.GeoPoint
import to.pubky.maps.place.PlaceSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DroppedPinTest {
    @Test
    fun aDroppedPinIsNamedByItsCoordinatesAndPinned() {
        val pin = droppedPin(47.3703123, 8.5560149)
        assertEquals("geo:47.37031,8.55601", pin.uri)
        assertEquals("47.37031, 8.55601", pin.title)
        assertEquals(GeoPoint(47.3703123, 8.5560149), pin.point)
        assertEquals("Dropped pin", pin.category)
        assertNull(pin.name)
        assertNull(pin.osm)
        assertNull(pin.address)
        // Typed coordinates' source: the page asks no server, and the map pins the point.
        assertEquals(PlaceSource.Coordinates, pin.source)
        assertTrue(pin.pinned)
    }

    @Test
    fun aPointPastTheAntimeridianIsWrappedIntoTheWorld() {
        assertEquals(GeoPoint(47.0, 8.5), mapPoint(47.0, 8.5))
        assertEquals(-170.0, mapPoint(10.0, 190.0).lon, 1e-9)
        assertEquals(170.0, mapPoint(10.0, -190.0).lon, 1e-9)
        assertEquals(8.5, mapPoint(10.0, 8.5 + 3 * 360.0).lon, 1e-9)
        assertEquals(8.5, mapPoint(10.0, 8.5 - 3 * 360.0).lon, 1e-9)
        assertEquals(180.0, mapPoint(0.0, 180.0).lon)
        assertEquals(-180.0, mapPoint(0.0, -180.0).lon)
        assertEquals(90.0, mapPoint(90.5, 0.0).lat)
        assertEquals(-90.0, mapPoint(-90.5, 0.0).lat)
        assertEquals("geo:-33.86785,-170", droppedPin(-33.86785, 190.0).uri)
    }
}
