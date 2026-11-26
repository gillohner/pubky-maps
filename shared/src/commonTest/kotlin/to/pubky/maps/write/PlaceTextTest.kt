package to.pubky.maps.write

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
}
