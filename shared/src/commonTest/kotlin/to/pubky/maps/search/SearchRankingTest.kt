package to.pubky.maps.search

import kotlinx.serialization.Serializable
import to.pubky.maps.links.GeoPoint
import to.pubky.maps.links.OsmId
import to.pubky.maps.links.OsmType
import to.pubky.maps.place.PlaceSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

class SearchRankingTest {
    private fun rank(
        slug: String,
        query: String,
    ) = SearchRanking.rank(query, Fixtures.zurich, Fixtures.photon(slug), Fixtures.pinakes(slug))

    @Test
    fun theSameOsmPlaceFromBothSourcesIsOneRowWithTheCardAndPhotonsAddress() {
        val rows = rank("kunsthaus", "kunsthaus")
        val museum = rows.filter { it.place.osm == OsmId(OsmType.Relation, 3768127) }
        assertEquals(1, museum.size)
        val row = museum.single()
        assertEquals("https://www.openstreetmap.org/relation/3768127", row.place.uri)
        assertEquals(PlaceSource.Pinakes, row.place.source)
        assertEquals("Heimplatz 1, 8001 Zürich", row.place.address)
        assertEquals("Museum", row.place.category)
        assertEquals(0, row.photonRank)
        assertEquals(0, row.pinakesRank)
        assertEquals(2, row.collections)
        assertTrue(row.onPubky)
    }

    @Test
    fun anOsmCardAndAWikidataCardOfOnePlaceAreOneRow() {
        val rows = rank("zuerich", "zürich")
        val city = rows.filter { it.place.osm == OsmId(OsmType.Relation, 1682248) }
        assertEquals(1, city.size)
        assertEquals("https://www.openstreetmap.org/relation/1682248", city.single().place.uri)
        assertTrue(rows.none { it.place.uri == "https://www.wikidata.org/wiki/Q72" })
    }

    @Test
    fun aPlaceOnlyPinakesKnowsIsListed() {
        val rows = rank("hauptbahnhof", "hauptbahnhof")
        val hb = rows.single { it.place.osm == OsmId(OsmType.Node, 1236383343) }
        assertNull(hb.photonRank)
        assertEquals(PlaceSource.Pinakes, hb.place.source)
        assertEquals("Train station", hb.place.category)
    }

    @Test
    fun aPlaceOnlyPhotonKnowsKeepsItsOsmLink() {
        val row = rank("zoo", "zoo").single { it.place.osm == OsmId(OsmType.Relation, 9344765) }
        assertEquals("https://www.openstreetmap.org/relation/9344765", row.place.uri)
        assertEquals(PlaceSource.Photon, row.place.source)
        assertEquals("Zoo", row.place.category)
        assertEquals(17.0, row.place.zoom)
    }

    @Test
    fun aStopListedAsSeveralPlatformsIsOneRow() {
        val rows = rank("kunsthaus", "kunsthaus")
        assertEquals(1, rows.count { it.place.title == "Kunsthaus" && it.place.category == "Tram stop" })
        assertEquals(1, rows.count { it.place.title == "Kunsthaus" && it.place.category == "Bus stop" })
        // A platform of the stop is the stop.
        assertEquals(0, rows.count { it.place.title == "Kunsthaus" && it.place.category == "Platform" })
        assertEquals(1, rank("letzigrund", "letzigrund").count { it.place.category == "Platform" })
        // Same-name shops are distinct places and stay.
        assertTrue(rank("spruengli", "sprüngli").count { it.place.title == "Sprüngli" } >= 5)
    }

    @Test
    fun everyRowHasAKindForItsIcon() {
        assertEquals(PlaceKind.Culture, rank("kunsthaus", "kunsthaus").first().kind)
        assertEquals(PlaceKind.Transit, rank("hauptbahnhof", "hauptbahnhof").first().kind)
        assertEquals(PlaceKind.Area, rank("zuerich", "zürich").first().kind)
        assertEquals(PlaceKind.Street, rank("bahnhofstrasse", "bahnhofstrasse").first().kind)
        assertEquals(PlaceKind.Food, rank("conditorei-schober", "conditorei schober").first().kind)
        assertEquals(PlaceKind.Nature, rank("uetliberg", "uetliberg").first().kind)
        assertEquals(PlaceKind.Education, rank("eth", "eth").first().kind)
        assertEquals(PlaceKind.Food, rank("spruengli", "sprüngli").first().kind)
    }

    @Test
    fun textMatchLevels() {
        fun t(
            query: String,
            title: String,
            address: String? = null,
        ) = SearchRanking.textMatch(normalizeForMatch(query), title, address)
        assertEquals(1.0, t("Zurich", "Zürich"))
        assertEquals(0.9, t("zoo", "Zoo Zürich"))
        assertEquals(0.8, t("hauptbahnhof", "Zürich Hauptbahnhof"))
        assertEquals(0.75, t("zoo", "Zoologieweiher"))
        assertEquals(0.7, t("haupt zur", "Zürich Hauptbahnhof"))
        assertEquals(0.5, t("bahnhof", "Zürich Hauptbahnhof"))
        assertEquals(0.4, t("sprungli bahnhofstrasse", "Sprüngli", "Bahnhofstrasse 21, 8001 Zürich"))
        assertEquals(0.2, t("xyz", "Sprüngli"))
    }

    @Test
    fun normalizationIgnoresCaseAccentsAndPunctuation() {
        assertEquals("strasse 1 zurich", normalizeForMatch("  Straße  1,Zürich "))
        assertEquals("eth zurich zentrum", normalizeForMatch("ETH Zürich, Zentrum"))
    }

    @Test
    fun aTransitStopRanksBelowThePlaceItIsNamedAfter() {
        val rows = rank("zoo", "zoo")
        assertEquals("Zoo Zürich", rows.first().place.title)
        assertEquals(Prominence.Minor, rows.first { it.place.title == "Zoo" }.prominence)
        assertEquals(Prominence.Landmark, rows.first().prominence)
    }

    @Test
    fun aFarExactMatchBeatsANearPartialOne() {
        val rows = rank("lugano", "lugano")
        assertEquals("Lugano", rows.first().place.title)
        assertTrue(rows.indexOfFirst { it.place.title == "Lugano Bar" } > 0)
    }

    @Test
    fun aPlacePeopleUseOnPubkyWinsAmongEqualMatches() {
        val area = Fixtures.zurich
        val photon = listOf(photonPlace(1, "Cafe Nord", 47.3770, 8.5418, rank = 0), photonPlace(2, "Cafe Nord", 47.3771, 8.5419, rank = 1))
        val pinakes =
            listOf(
                PinakesPlace(
                    "https://www.openstreetmap.org/node/2",
                    "Cafe Nord",
                    GeoPoint(47.3771, 8.5419),
                    "CafeOrCoffeeShop",
                    OsmId(OsmType.Node, 2),
                    collections = 4,
                    rank = 0,
                ),
            )
        val rows = SearchRanking.rank("cafe nord", area, photon, pinakes)
        assertEquals(OsmId(OsmType.Node, 2), rows.first().place.osm)
    }

    @Test
    fun atMostFifteenRows() {
        val photon = (1..20).map { photonPlace(it.toLong(), "Spot $it", 47.37, 8.54, rank = it - 1) }
        assertEquals(SearchRanking.MAX_RESULTS, SearchRanking.rank("spot", Fixtures.zurich, photon, emptyList()).size)
    }

    @Test
    fun distanceFormatting() {
        assertEquals("0 m", formatDistance(3.0))
        assertEquals("350 m", formatDistance(347.0))
        assertEquals("1 km", formatDistance(998.0))
        assertEquals("1.2 km", formatDistance(1_240.0))
        assertEquals("10 km", formatDistance(9_960.0))
        assertEquals("156 km", formatDistance(155_800.0))
        assertEquals(155.8, distanceMeters(Fixtures.zurich.centre, GeoPoint(46.0038007, 8.9512275)) / 1000, 0.1)
    }

    /**
     * The Zürich query set (fixtures recorded from the live Photon and Pinakes): for every query the
     * first result is the place a person typing it means. Prints the top three of each query.
     */
    @Test
    fun zurichQuerySet() {
        val spec = SearchJson.decodeFromString(QuerySet.serializer(), Fixtures.read("queries.json"))
        val area = SearchArea(GeoPoint(spec.lat, spec.lon), spec.zoom.toDouble(), spec.halfDiagonalM)
        assertEquals(spec.radiusM, area.pinakesRadiusMeters)
        val failures = ArrayList<String>()
        for (case in spec.queries) {
            val rows = SearchRanking.rank(case.query, area, Fixtures.photon(case.fixture), Fixtures.pinakes(case.fixture))
            val top =
                rows.take(3).joinToString(" | ") { r ->
                    val km = r.place.point?.let { formatDistance(distanceMeters(area.centre, it)) }
                    "${r.place.title} (${r.place.category ?: "-"}, $km${if (r.onPubky) ", Pubky" else ""}, ${(r.score * 1000).toInt() / 1000.0})"
                }
            println("query-set ${case.query}: $top")
            val first = rows.firstOrNull()
            val e = case.expect
            val problem =
                when {
                    first == null -> "no result"
                    first.place.title != e.title -> "first is ${first.place.title}"
                    e.category != null && first.place.category != e.category -> "category ${first.place.category}"
                    e.onPubky != null && first.onPubky != e.onPubky -> "onPubky ${first.onPubky}"
                    distanceMeters(first.place.point!!, GeoPoint(e.lat, e.lon)) > e.withinM -> "too far from the expected point"
                    else -> null
                }
            if (problem != null) failures += "${case.query}: $problem"
        }
        assertTrue(spec.queries.size >= 10)
        failures.forEach { println("query-set miss: $it") }
        val hits = spec.queries.size - failures.size
        println("query-set: $hits of ${spec.queries.size} first results right")
        // The plan's target: the first result is the right one for 9 in 10 queries.
        if (hits * 10 < spec.queries.size * 9) fail("only $hits of ${spec.queries.size} right:\n" + failures.joinToString("\n"))
    }

    @Serializable
    private data class QuerySet(
        val lat: Double,
        val lon: Double,
        val zoom: Int,
        @kotlinx.serialization.SerialName("radius_m") val radiusM: Int,
        @kotlinx.serialization.SerialName("half_diagonal_m") val halfDiagonalM: Double,
        val queries: List<QueryCase>,
    )

    @Serializable
    private data class QueryCase(
        val query: String,
        val fixture: String,
        val expect: Expected,
    )

    @Serializable
    private data class Expected(
        val title: String,
        val category: String? = null,
        @kotlinx.serialization.SerialName("on_pubky") val onPubky: Boolean? = null,
        val lat: Double,
        val lon: Double,
        @kotlinx.serialization.SerialName("within_m") val withinM: Double,
    )
}

internal fun photonPlace(
    id: Long,
    name: String,
    lat: Double,
    lon: Double,
    rank: Int,
    key: String = "amenity",
    value: String = "cafe",
) = PhotonPlace(
    osm = OsmId(OsmType.Node, id),
    name = name,
    point = GeoPoint(lat, lon),
    osmKey = key,
    osmValue = value,
    type = "house",
    street = null,
    houseNumber = null,
    postcode = null,
    city = "Zürich",
    state = null,
    country = null,
    rank = rank,
)
