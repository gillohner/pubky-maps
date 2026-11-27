package to.pubky.maps.write

import to.pubky.maps.links.GeoPoint
import kotlin.test.Test
import kotlin.test.assertEquals

class PlaceTextTest {
    @Test
    fun anOpenStreetMapLinkReadsAsItsKindAndNumber() {
        assertEquals("OpenStreetMap node 2405123", placeText("https://www.openstreetmap.org/node/2405123"))
        assertEquals("OpenStreetMap relation 1682248", placeText("https://www.openstreetmap.org/relation/1682248"))
    }

    @Test
    fun aGeoLinkReadsAsItsCoordinates() {
        assertEquals("47.37689, 8.54169", placeText("geo:47.37689,8.54169"))
        assertEquals("47.1, 8.5", placeText("geo:47.1,8.5;u=10?z=16"))
    }

    @Test
    fun anyOtherLinkLosesItsSchemeAndWww() {
        assertEquals("maps.app.goo.gl/x1", placeText("https://maps.app.goo.gl/x1"))
    }

    @Test
    fun onlyANameTwoDifferentPlacesShareNeedsTellingApart() {
        val shared =
            sharedNames(
                listOf(
                    "https://www.openstreetmap.org/node/1" to "Coop",
                    "https://www.openstreetmap.org/node/2" to "coop ",
                    // The same place kept on the phone and on the homeserver is one place.
                    "https://www.openstreetmap.org/node/3" to "Lindenhof",
                    "https://www.openstreetmap.org/node/3" to "Lindenhof",
                    // Places without a name show their own link.
                    "geo:47.1,8.5" to null,
                    "geo:47.2,8.5" to null,
                ),
            )
        assertEquals(setOf("coop"), shared)
    }

    @Test
    fun aSharedNamePlaceIsToldByItsPointElseItsLink() {
        assertEquals("47.37689, 8.54169", whereText("https://www.openstreetmap.org/node/1", GeoPoint(47.37689, 8.54169)))
        assertEquals("OpenStreetMap node 1", whereText("https://www.openstreetmap.org/node/1", null))
    }
}
