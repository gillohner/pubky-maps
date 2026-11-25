package to.pubky.maps.search

import to.pubky.maps.links.OsmId
import to.pubky.maps.links.OsmType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PinakesTest {
    @Test
    fun readsAPlaceCard() {
        val card = Fixtures.pinakes("conditorei-schober").single()
        assertEquals("https://www.openstreetmap.org/node/270803095", card.uri)
        assertEquals("Conditorei schober", card.name)
        assertEquals("CafeOrCoffeeShop", card.type)
        assertEquals(OsmId(OsmType.Node, 270803095), card.osm)
        assertEquals(3, card.collections)
    }

    @Test
    fun aWikidataCardIsTheOsmElementItsExactAlsoOnLinkNames() {
        val q72 = Fixtures.pinakes("zuerich").single { it.uri == "https://www.wikidata.org/wiki/Q72" }
        assertEquals(OsmId(OsmType.Relation, 1682248), q72.osm)
    }

    @Test
    fun aMaybeLinkIsNoIdentityAndCardsWithoutAPointAreDropped() {
        val json =
            """
            {"items":[
              {"id":"a","uri":"https://example.org/cafe","status":"ok","type":"Place","name":"Cafe","point":{"lat":47.1,"lon":8.1},
               "also_on":[{"uri":"https://www.openstreetmap.org/node/9","kind":"maybe"}],"refs":[],"collections":0,"type_path":[],"open_in":[],"credits":[]},
              {"id":"b","uri":"https://example.org/nowhere","status":"ok","type":"Place","name":"Nowhere","point":null,
               "also_on":[],"refs":[],"collections":0,"type_path":[],"open_in":[],"credits":[]},
              {"id":"c","uri":"https://example.org/old","status":"ok","type":"Place","name":"Old spelling","point":{"lat":47.2,"lon":8.2},
               "also_on":[],"refs":["http://openstreetmap.org/way/12/"],"collections":1,"type_path":[],"open_in":[],"credits":[]}
            ],"cursor":null,"reach_truncated":false}
            """.trimIndent()
        val cards = parsePinakes(json)
        assertEquals(listOf("Cafe", "Old spelling"), cards.map { it.name })
        assertNull(cards[0].osm)
        assertEquals(OsmId(OsmType.Way, 12), cards[1].osm)
        assertEquals(2, cards[1].rank)
    }

    @Test
    fun osmLinksInEverySpelling() {
        assertEquals(OsmId(OsmType.Node, 1), osmIdFromUrl("https://www.openstreetmap.org/node/1"))
        assertEquals(OsmId(OsmType.Way, 2), osmIdFromUrl("http://openstreetmap.org/way/2/"))
        assertEquals(OsmId(OsmType.Relation, 3), osmIdFromUrl(" https://openstreetmap.org/relation/3 "))
        assertNull(osmIdFromUrl("https://www.openstreetmap.org/node/0"))
        assertNull(osmIdFromUrl("https://www.openstreetmap.org/changeset/5"))
        assertNull(osmIdFromUrl("https://www.openstreetmap.org/node/5/history"))
        assertNull(osmIdFromUrl("https://evil.example/openstreetmap.org/node/5"))
    }

    @Test
    fun searchUrlAsksForPlacesAroundTheMap() {
        val url = pinakesSearchUrl("https://pinakes.example.org", "kunst haus", Fixtures.zurich, viewer = null)
        assertEquals(
            "https://pinakes.example.org/v1/search?q=kunst+haus&type=Place&near=47.3769%2C8.5417&radius=10000&limit=10",
            url,
        )
        assertFalse("boost" in url)
    }

    @Test
    fun signedInSearchesRankWhatTheFollowedReferencedHigher() {
        val url =
            pinakesSearchUrl(
                "https://pinakes.example.org",
                "cafe",
                Fixtures.zurich,
                viewer = "o4dksfbqk85ogzdb5osziw6befigbuxmuxkuxq8434q89uj56uyy",
            )
        assertTrue(url.endsWith("&boost=following&viewer=o4dksfbqk85ogzdb5osziw6befigbuxmuxkuxq8434q89uj56uyy"), url)
    }

    @Test
    fun theRadiusFollowsTheMap() {
        assertEquals(10_000, Fixtures.zurich.pinakesRadiusMeters)
        assertEquals(30_000, Fixtures.zurich.copy(halfDiagonalMeters = 10_000.0).pinakesRadiusMeters)
        assertEquals(200_000, Fixtures.zurich.copy(halfDiagonalMeters = 5_000_000.0).pinakesRadiusMeters)
        assertTrue(Fixtures.zurich.proximityScaleMeters == 3333.0)
    }
}
