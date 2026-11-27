package to.pubky.maps.map

import to.pubky.maps.links.GeoPoint
import to.pubky.maps.links.OsmId
import to.pubky.maps.links.OsmType
import to.pubky.maps.osm.NoteTarget
import to.pubky.maps.place.PlacePageState
import to.pubky.maps.place.PlaceRef
import to.pubky.maps.place.PlaceSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NoteTargetTest {
    private val schober = GeoPoint(47.37159, 8.54531)
    private val node = OsmId(OsmType.Node, 270803095)

    @Test
    fun aNoteIsAboutThePlacesOwnPointNameAndElement() {
        val place = PlaceRef(node.url, "Conditorei Schober", schober, node, PlaceSource.Map)
        assertEquals(NoteTarget(schober, "Conditorei Schober", node), PlacePageState(place).noteTarget)
        assertEquals(NoteTarget(GeoPoint(47.37031, 8.55601)), PlacePageState(droppedPin(47.37031, 8.55601)).noteTarget)
    }

    @Test
    fun aPointOfItsOwnGoesWithoutTheUsersLabel() {
        // Typed coordinates or a point saved from its page, named by the user: the public note leaves the name out.
        val home = PlaceRef("geo:47.37031,8.55601", "Home", GeoPoint(47.37031, 8.55601), null, PlaceSource.Coordinates)
        assertEquals(NoteTarget(GeoPoint(47.37031, 8.55601)), PlacePageState(home).noteTarget)
    }

    @Test
    fun aPlaceWhosePointIsOnlyAViewOrASearchHasNoNote() {
        // The centre of a map view or of a search is not where the problem is: no note goes there.
        val view = PlaceRef("https://maps.app.goo.gl/x", "Kunsthaus", schober, null, PlaceSource.LinkText, pointIsView = true)
        assertNull(PlacePageState(view).noteTarget)
        assertNull(PlacePageState(PlaceRef("https://example.org", null, null, null, PlaceSource.LinkText)).noteTarget)
    }
}
