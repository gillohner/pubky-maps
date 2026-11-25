package to.pubky.maps.search

import to.pubky.maps.links.GeoPoint
import to.pubky.maps.links.OsmId
import to.pubky.maps.links.OsmType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PhotonTest {
    @Test
    fun readsAPlaceWithItsAddress() {
        val first = Fixtures.photon("kunsthaus").first()
        assertEquals(OsmId(OsmType.Relation, 3768127), first.osm)
        assertEquals("Kunsthaus", first.title)
        assertEquals(GeoPoint(47.3702241, 8.5479797), first.point)
        assertEquals("Heimplatz 1, 8001 Zürich", first.address)
        assertEquals("tourism" to "museum", first.osmKey to first.osmValue)
        assertEquals(0, first.rank)
    }

    @Test
    fun aTownShowsItsRegionAndCountry() {
        val lugano = Fixtures.photon("lugano").first()
        assertEquals("Lugano", lugano.title)
        assertEquals("city", lugano.type)
        assertEquals("Tessin, Schweiz", lugano.address)
        // Zürich is also its canton's name: the address does not repeat the title.
        assertEquals("Schweiz", Fixtures.photon("zuerich").first().address)
    }

    @Test
    fun anAddressWithoutANameIsTitledByItsStreet() {
        val first = Fixtures.photon("bahnhofstrasse-1").first()
        assertNull(first.name)
        assertEquals("Bahnhofstrasse 1", first.title)
        assertEquals("8001 Zürich", first.address)
    }

    @Test
    fun aStreetSplitIntoManyWaysIsListedOnce() {
        val zurichStreets = Fixtures.photon("bahnhofstrasse").filter { it.type == "street" && it.city == "Zürich" }
        assertEquals(1, zurichStreets.size)
        assertEquals(OsmId(OsmType.Way, 1121941324), zurichStreets.single().osm)
    }

    @Test
    fun theSameElementTwiceIsListedOnce() {
        val ids = Fixtures.photon("lindenhof").mapNotNull { it.osm }
        assertEquals(ids.distinct(), ids)
        assertTrue(OsmId(OsmType.Way, 10594810) in ids)
    }

    @Test
    fun featuresWithoutAPointOrWithABrokenOneAreSkipped() {
        val json =
            """
            {"type":"FeatureCollection","features":[
              {"type":"Feature","properties":{"osm_type":"N","osm_id":1,"name":"No point"}},
              {"type":"Feature","geometry":{"type":"Point","coordinates":[8.5]},"properties":{"name":"Short"}},
              {"type":"Feature","geometry":{"type":"Point","coordinates":[200.0,47.0]},"properties":{"name":"Out of range"}},
              {"type":"Feature","geometry":{"type":"Point","coordinates":[8.5,47.4]},"properties":{"osm_type":"X","osm_id":5,"name":"Kept","extra":{"a":1}}}
            ]}
            """.trimIndent()
        val places = parsePhoton(json)
        assertEquals(listOf("Kept"), places.map { it.title })
        assertNull(places.single().osm)
        assertEquals(3, places.single().rank)
    }

    @Test
    fun searchUrlCarriesTheQueryTheMapCentreAndTheLanguage() {
        val url = photonSearchUrl("https://photon.example.org", "Café & Bar", Fixtures.zurich, "de")
        assertEquals(
            "https://photon.example.org/api?q=Caf%C3%A9+%26+Bar&lat=47.3769&lon=8.5417&zoom=13&limit=10&lang=de",
            url,
        )
        // A server with a path keeps it.
        assertTrue(
            photonSearchUrl(
                "https://example.org/photon",
                "x",
                Fixtures.zurich,
                "default",
            ).startsWith("https://example.org/photon/api?q=x&"),
        )
    }

    @Test
    fun onlyLanguagesPhotonHasAreAsked() {
        assertEquals("de", photonLanguage("de"))
        assertEquals("fr", photonLanguage("FR"))
        assertEquals("default", photonLanguage("it"))
        assertEquals("default", photonLanguage("es"))
    }
}
