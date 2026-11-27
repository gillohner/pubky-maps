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
    fun sameNamedPlacesWithDifferentSummariesAreToldApartByThem() {
        val coopBahnhof = "Supermarket · Bahnhofstrasse 1, 8001 Zürich"
        val shared =
            sharedNames(
                listOf(
                    "https://www.openstreetmap.org/node/1" to sameLine("Coop", coopBahnhof),
                    "https://www.openstreetmap.org/node/2" to sameLine("Coop", "Supermarket · Limmatquai 2, 8001 Zürich"),
                    // The same summary (or none) does not tell two Migros apart: their points do.
                    "https://www.openstreetmap.org/node/3" to sameLine("Migros", "Supermarket"),
                    "https://www.openstreetmap.org/node/4" to sameLine("migros ", "supermarket"),
                    "https://www.openstreetmap.org/node/5" to sameLine("Lindenhof", null),
                    "https://www.openstreetmap.org/node/6" to sameLine("Lindenhof", null),
                ),
            )
        assertEquals(setOf(sameLine("Migros", "Supermarket"), sameLine("Lindenhof", null)), shared)
        assertEquals(null, sameLine(null, coopBahnhof), "a row without a name shows its link")
    }

    @Test
    fun theLineUnderANameSaysWhatAndWhereThenThePointWhenNeeded() {
        assertEquals("Café · Napfgasse 4, 8001 Zürich", savedLine("Café · Napfgasse 4, 8001 Zürich", null))
        assertEquals("Supermarket · 47.37689, 8.54169", savedLine("Supermarket", "47.37689, 8.54169"))
        assertEquals("47.37689, 8.54169", savedLine(null, "47.37689, 8.54169"))
        assertEquals(null, savedLine(null, null))
    }

    @Test
    fun aSharedNamePlaceIsToldByItsPointElseItsLink() {
        assertEquals("47.37689, 8.54169", whereText("https://www.openstreetmap.org/node/1", GeoPoint(47.37689, 8.54169)))
        assertEquals("OpenStreetMap node 1", whereText("https://www.openstreetmap.org/node/1", null))
    }
}
