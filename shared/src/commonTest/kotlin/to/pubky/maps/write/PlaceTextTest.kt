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
        val coop1 = RowText("https://www.openstreetmap.org/node/1", "Coop", null)
        val coop2 = RowText("https://www.openstreetmap.org/node/2", "coop ", null)
        val rows =
            listOf(
                coop1,
                coop2,
                // The same place kept on the phone and on the homeserver is one place.
                RowText("https://www.openstreetmap.org/node/3", "Lindenhof", null),
                RowText("https://www.openstreetmap.org/node/3", "Lindenhof", null),
                // Places without a name show their own link.
                RowText("geo:47.1,8.5", null, null),
                RowText("geo:47.2,8.5", null, null),
            )
        assertEquals(setOf(coop1.key(), coop2.key()), rowsToTellApart(rows))
    }

    @Test
    fun sameNamedPlacesWithDifferentSummariesAreToldApartByThem() {
        val migros1 = RowText("https://www.openstreetmap.org/node/3", "Migros", "Supermarket")
        val migros2 = RowText("https://www.openstreetmap.org/node/4", "migros ", "supermarket")
        val rows =
            listOf(
                RowText("https://www.openstreetmap.org/node/1", "Coop", "Supermarket · Bahnhofstrasse 1, 8001 Zürich"),
                RowText("https://www.openstreetmap.org/node/2", "Coop", "Supermarket · Limmatquai 2, 8001 Zürich"),
                // The same summary does not tell two Migros apart: their points do.
                migros1,
                migros2,
            )
        assertEquals(setOf(migros1.key(), migros2.key()), rowsToTellApart(rows))
    }

    @Test
    fun ofTwoSameNamedPlacesOnlyTheOneWithoutASummaryNeedsItsPoint() {
        val bare = RowText("https://www.openstreetmap.org/node/2", "Coop", null)
        val rows =
            listOf(
                // Its summary tells this Coop already; the other, with no summary, would have no line at all.
                RowText("https://www.openstreetmap.org/node/1", "Coop", "Supermarket · Bahnhofstrasse 1, 8001 Zürich"),
                bare,
            )
        assertEquals(setOf(bare.key()), rowsToTellApart(rows))
    }

    @Test
    fun ofThreeSameNamedPlacesOneWithItsOwnSummaryNeedsNoPoint() {
        val bahnhof = RowText("https://www.openstreetmap.org/node/1", "Coop", "Supermarket · Bahnhofstrasse 1")
        val limmatquai1 = RowText("https://www.openstreetmap.org/node/2", "Coop", "Supermarket · Limmatquai 2")
        val limmatquai2 = RowText("https://www.openstreetmap.org/node/3", "Coop", "Supermarket · Limmatquai 2")
        val bare = RowText("https://www.openstreetmap.org/node/4", "Coop", null)
        assertEquals(
            setOf(limmatquai1.key(), limmatquai2.key(), bare.key()),
            rowsToTellApart(listOf(bahnhof, limmatquai1, limmatquai2, bare)),
        )
    }

    @Test
    fun aPlaceKeptTwiceWithOneSummaryShowsThePointOnlyOnItsBareCopy() {
        val phoneCopy = RowText("https://www.openstreetmap.org/node/1", "Coop", "Supermarket · Bahnhofstrasse 1")
        val homeCopy = RowText("https://www.openstreetmap.org/node/1", "Coop", null)
        // Alone, the place kept twice is one place: neither copy needs a point.
        assertEquals(emptySet(), rowsToTellApart(listOf(phoneCopy, homeCopy)))
        val other = RowText("https://www.openstreetmap.org/node/2", "Coop", null)
        assertEquals(setOf(homeCopy.key(), other.key()), rowsToTellApart(listOf(phoneCopy, homeCopy, other)))
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
