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
