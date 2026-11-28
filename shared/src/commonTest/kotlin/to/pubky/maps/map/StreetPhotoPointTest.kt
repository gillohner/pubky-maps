package to.pubky.maps.map

import to.pubky.maps.links.GeoPoint
import to.pubky.maps.links.OsmId
import to.pubky.maps.links.OsmType
import to.pubky.maps.place.PlaceCardInfo
import to.pubky.maps.place.PlacePageState
import to.pubky.maps.place.PlaceRef
import to.pubky.maps.place.PlaceSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Which point of a place page Panoramax is asked about, if any. */
class StreetPhotoPointTest {
    private val schober = GeoPoint(47.37159, 8.54531)
    private val node = OsmId(OsmType.Node, 270803095)

    @Test
    fun aPlaceOnTheMapIsAskedAboutAtItsOwnPoint() {
        assertEquals(schober, PlacePageState(PlaceRef(node.url, "Conditorei Schober", schober, node, PlaceSource.Map)).streetPhotoPoint)
    }

    @Test
    fun aLinksPlaceIsAskedAboutOnceItsCardGivesItsPoint() {
        val link = PlaceRef(node.url, null, null, node, PlaceSource.LinkText)
        assertNull(PlacePageState(link).streetPhotoPoint)
        val card =
            PlaceCardInfo(
                id = "1",
                uri = node.url,
                name = "Conditorei Schober",
                type = null,
                category = null,
                address = null,
                lat = schober.lat,
                lon = schober.lon,
                description = null,
                image = null,
                credits = emptyList(),
                attribution = null,
                alsoOn = emptyList(),
                refs = emptyList(),
                collections = 0,
                facts = emptyMap(),
            )
        assertEquals(schober, PlacePageState(link, card = card).streetPhotoPoint)
    }

    @Test
    fun aPointOfTheUsersOwnIsAskedOfNoServer() {
        // A dropped pin, typed coordinates, or such a point bookmarked as home.
        assertNull(PlacePageState(droppedPin(47.37031, 8.55601)).streetPhotoPoint)
        val home = PlaceRef("geo:47.37031,8.55601", "Home", GeoPoint(47.37031, 8.55601), null, PlaceSource.Coordinates)
        assertNull(PlacePageState(home).streetPhotoPoint)
    }

    @Test
    fun aMapViewOrASearchCentreIsNotThePlace() {
        val view = PlaceRef("https://maps.app.goo.gl/x", "Kunsthaus", schober, null, PlaceSource.LinkText, pointIsView = true)
        assertNull(PlacePageState(view).streetPhotoPoint)
    }
}
