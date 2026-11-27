package to.pubky.maps.map

import to.pubky.maps.links.GeoPoint
import to.pubky.maps.links.OsmId
import to.pubky.maps.links.OsmType
import to.pubky.maps.place.PlaceCardInfo
import to.pubky.maps.place.PlacePageState
import to.pubky.maps.place.PlaceRef
import to.pubky.maps.place.PlaceSource
import to.pubky.maps.write.Kept
import to.pubky.maps.write.SavedEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

private const val OSM = "https://www.openstreetmap.org/node/270803095"
private val SCHOBER = GeoPoint(47.37159, 8.54531)

class SavedPinsTest {
    @Test
    fun aSavedPlaceWithAPointGetsOnePinEvenWhenSavedTwice() {
        val lake = GeoPoint(47.35, 8.55)
        val saved =
            listOf(
                SavedEntry(OSM, Kept.Phone, "Conditorei Schober", point = SCHOBER),
                SavedEntry("https://www.openstreetmap.org/way/7", Kept.Private, "Bench"),
                SavedEntry(OSM, Kept.Private, "Conditorei Schober", point = SCHOBER),
                SavedEntry("geo:47.35,8.55", Kept.Private, null, pending = true, point = lake),
            )

        assertEquals(listOf(OSM, "geo:47.35,8.55"), savedPins(saved).map { it.place })
        assertEquals(emptyList(), savedPins(emptyList()))
    }

    @Test
    fun aPinOpensItsPlaceAsSaved() {
        val place = SavedEntry(OSM, Kept.Private, "Conditorei Schober", point = SCHOBER).toPlaceRef()

        assertEquals(
            PlaceRef(OSM, "Conditorei Schober", SCHOBER, OsmId(OsmType.Node, 270803095), PlaceSource.Map),
            place,
        )
        assertEquals(true, place.pinned)
    }

    @Test
    fun aPointSavedFromItsOwnPageOpensAsCoordinatesNotAsAPlaceOfTheMap() {
        val pin = GeoPoint(47.37031, 8.55601)
        // A dropped pin or typed coordinates, saved: still a point that no server hears of.
        assertEquals(
            PlaceRef("geo:47.37031,8.55601", "Home", pin, null, PlaceSource.Coordinates),
            SavedEntry("geo:47.37031,8.55601", Kept.Phone, "Home", point = pin, pointPage = true).toPlaceRef(),
        )
        // A geo: link from another app or a Pubky place with a geo: URI, saved from its page: a public place.
        assertEquals(
            PlaceRef("geo:47.37031,8.55601", "Kunsthalle", pin, null, PlaceSource.Map),
            SavedEntry("geo:47.37031,8.55601", Kept.Private, "Kunsthalle", point = pin).toPlaceRef(),
        )
        // From the Saved page, which knows the link and the name: the point is the link's.
        assertEquals(
            PlaceRef("geo:47.35,8.55", null, GeoPoint(47.35, 8.55), null, PlaceSource.Coordinates),
            savedPointPlace("geo:47.35,8.55", null),
        )
        assertNull(savedPointPlace(OSM, "Conditorei Schober"), "an OpenStreetMap place is read as its link is")
        assertNull(savedPointPlace("geo:0,0?q=Kunsthaus", null), "a geo: search names no point")
    }

    @Test
    fun onlyAPageOfAPointOfItsOwnSavesThePlaceAsOne() {
        val typed = PlaceRef("geo:47.3703,8.556", null, GeoPoint(47.3703, 8.556), null, PlaceSource.Coordinates)
        assertEquals(true, PlacePageState(typed).writeTarget.pointPage)
        assertEquals(true, PlacePageState(droppedPin(47.37031, 8.55601)).writeTarget.pointPage)
        // A geo: link from another app, a Pubky place with a geo: URI, a place of the map: public places.
        val shared = typed.copy(source = PlaceSource.LinkText)
        assertEquals(false, PlacePageState(shared).writeTarget.pointPage)
        assertEquals(false, PlacePageState(shared.copy(source = PlaceSource.Pinakes)).writeTarget.pointPage)
        assertEquals(false, PlacePageState(PlaceRef(OSM, "Conditorei Schober", SCHOBER, null, PlaceSource.Map)).writeTarget.pointPage)
    }

    @Test
    fun aSaveKeepsThePlacesOwnPointElseItsCardsNeverAMapView() {
        val pinned = PlaceRef(OSM, "Conditorei Schober", SCHOBER, null, PlaceSource.Map)
        assertEquals(SCHOBER, PlacePageState(pinned).placePoint)

        val view = PlaceRef("https://maps.google.com/?ll=47,8", null, GeoPoint(47.0, 8.0), null, PlaceSource.LinkText, pointIsView = true)
        assertNull(PlacePageState(view).placePoint, "the centre of a map view is not the place")
        assertEquals(GeoPoint(47.1, 8.2), PlacePageState(view, card = card(47.1, 8.2)).placePoint)
        assertNull(PlacePageState(view, card = card(null, 8.2)).placePoint)
        assertNull(PlacePageState(view, card = card(91.0, 8.2)).placePoint, "a point out of range is no point")
    }

    private fun card(
        lat: Double?,
        lon: Double?,
    ) = PlaceCardInfo(
        id = "1",
        uri = OSM,
        name = null,
        type = null,
        category = null,
        address = null,
        lat = lat,
        lon = lon,
        description = null,
        image = null,
        credits = emptyList(),
        attribution = null,
        alsoOn = emptyList(),
        refs = emptyList(),
        collections = 0,
        facts = emptyMap(),
    )
}
