package to.pubky.maps.offline

import to.pubky.maps.links.OsmId
import to.pubky.maps.links.OsmType
import to.pubky.maps.map.tappedPlace
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OfflinePlacesTest {
    // The z14 tile over Zürich's main station.
    private val z = 14
    private val x = 8580
    private val y = 5737

    private val poiProperties =
        mapOf(
            "Café Rosa" to mapOf("name" to "Café Rosa", "class" to "cafe", "subclass" to "cafe"),
            "Zürich HB" to mapOf("name" to "Zürich HB", "class" to "railway", "subclass" to "station"),
            "Beck" to mapOf("name" to "Beck", "class" to "shop", "subclass" to "bakery"),
        )

    private fun places(layers: Map<String, List<TestFeature>>) = placesOfTile(z, x, y, decodeVectorTile(vectorTile(layers)))

    @Test
    fun decodesLayersFeaturesAndValues() {
        val bytes =
            vectorTile(
                mapOf(
                    "poi" to
                        listOf(
                            TestFeature(
                                id = 2_708_030_951,
                                properties = mapOf("name" to "Café Rosa", "rank" to 3L, "open" to true, "height" to 1.5),
                                points = listOf(100 to 200),
                            ),
                        ),
                    "water" to listOf(TestFeature(null, emptyMap(), listOf(0 to 0, 10 to 0, 10 to 10), polygon = true)),
                ),
            )
        val layers = decodeVectorTile(bytes)
        assertEquals(listOf("poi", "water"), layers.map { it.name })
        val cafe = layers[0].features.single()
        assertEquals(2_708_030_951, cafe.id)
        assertEquals(mapOf("name" to "Café Rosa", "rank" to "3", "open" to "true", "height" to "1.5"), cafe.properties)
        assertEquals(100 to 200, cafe.x to cafe.y)
        // A polygon's point is the middle vertex of its ring.
        val water = layers[1].features.single()
        assertNull(water.id)
        assertEquals(10 to 0, water.x to water.y)
        assertEquals(4096, layers[0].extent)
    }

    @Test
    fun refusesBytesThatAreNoVectorTile() {
        // A PNG's first bytes.
        assertFailsWith<VectorTileException> { decodeVectorTile(byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a)) }
        // A layer that claims more bytes than the tile has.
        assertFailsWith<VectorTileException> { decodeVectorTile(byteArrayOf(0x1a, 0x7f, 0x0a)) }
    }

    @Test
    fun readsThePlacesOfAnOpenMapTilesTile() {
        val found =
            places(
                mapOf(
                    "poi" to
                        listOf(
                            TestFeature(2_708_030_951, poiProperties.getValue("Café Rosa"), listOf(2048 to 2048)),
                            TestFeature(1_234_561, poiProperties.getValue("Zürich HB"), listOf(10 to 10)),
                            TestFeature(55_551, poiProperties.getValue("Beck"), listOf(20 to 20)),
                            // No name: nothing to search for.
                            TestFeature(66_661, mapOf("class" to "cafe", "subclass" to "cafe"), listOf(30 to 30)),
                        ),
                    "place" to listOf(TestFeature(77_771, mapOf("name" to "Aussersihl", "class" to "suburb"), listOf(40 to 40))),
                    "transportation_name" to
                        listOf(
                            TestFeature(
                                null,
                                mapOf("name" to "Bahnhofstrasse", "class" to "minor"),
                                listOf(0 to 100, 50 to 100, 100 to 100),
                            ),
                            TestFeature(null, mapOf("name" to "S-Bahn", "class" to "rail"), listOf(0 to 200, 100 to 200)),
                        ),
                    "mountain_peak" to listOf(TestFeature(88_881, mapOf("name" to "Uetliberg", "class" to "peak"), listOf(60 to 60))),
                    "water_name" to listOf(TestFeature(99_993, mapOf("name" to "Zürichsee", "class" to "lake"), listOf(70 to 70))),
                    "building" to listOf(TestFeature(12_342, mapOf("name" to "Not searched"), listOf(80 to 80))),
                ),
            )
        // A point of interest is named as the map names it when tapped.
        for (poi in found.take(3)) {
            assertEquals(tappedPlace(poi.osm!!.id * 10 + 1, poiProperties.getValue(poi.name), poi.point)?.category, poi.category)
        }
        assertEquals(
            listOf(
                "Café Rosa" to "amenity=cafe",
                "Zürich HB" to "railway=station",
                "Beck" to "shop=bakery",
            ),
            found.take(3).map { it.name to "${it.key}=${it.value}" },
        )
        assertEquals(
            listOf(
                "Aussersihl" to "place=suburb Suburb",
                "Bahnhofstrasse" to "highway=minor Street",
                "Uetliberg" to "natural=peak Peak",
                "Zürichsee" to "natural=lake Lake",
            ),
            found.drop(3).map { it.name to "${it.key}=${it.value} ${it.category}" },
        )
        assertEquals(OsmId(OsmType.Node, 270_803_095), found[0].osm)
        assertNull(found.single { it.name == "Bahnhofstrasse" }.osm)
        assertEquals("street", found.single { it.name == "Bahnhofstrasse" }.photonType)
        assertEquals("district", found.single { it.name == "Aussersihl" }.photonType)
        val cafe = found[0].point
        assertTrue(abs(cafe.lat - 47.37603) < 0.00001 && abs(cafe.lon - 8.53638) < 0.00001, "$cafe")
    }

    @Test
    fun readsThePlacesOfAShortbreadTile() {
        val found =
            places(
                mapOf(
                    "pois" to listOf(TestFeature(2_708_030_951, mapOf("name" to "Café Rosa", "amenity" to "cafe"), listOf(10 to 10))),
                    "public_transport" to
                        listOf(TestFeature(1_234_561, mapOf("name" to "Central", "kind" to "tram_stop"), listOf(20 to 20))),
                    "place_labels" to listOf(TestFeature(77_771, mapOf("name" to "Zürich", "kind" to "city"), listOf(30 to 30))),
                    "street_labels" to
                        listOf(TestFeature(null, mapOf("name" to "Limmatquai", "kind" to "secondary"), listOf(40 to 40, 90 to 40))),
                ),
            )
        assertEquals(
            listOf(
                "Café Rosa" to "amenity=cafe",
                "Central" to "railway=tram_stop",
                "Zürich" to "place=city",
                "Limmatquai" to "highway=secondary",
            ),
            found.map { it.name to "${it.key}=${it.value}" },
        )
        assertEquals("city", found.single { it.name == "Zürich" }.photonType)
    }

    @Test
    fun aPlaceCountsInTheTileThatHoldsItNotInItsNeighboursBuffer() {
        val found =
            places(
                mapOf(
                    "poi" to
                        listOf(
                            TestFeature(11, mapOf("name" to "Inside", "class" to "cafe", "subclass" to "cafe"), listOf(4095 to 0)),
                            TestFeature(21, mapOf("name" to "Buffer east", "class" to "cafe", "subclass" to "cafe"), listOf(4096 to 10)),
                            TestFeature(31, mapOf("name" to "Buffer north", "class" to "cafe", "subclass" to "cafe"), listOf(10 to -5)),
                        ),
                ),
            )
        assertEquals(listOf("Inside"), found.map { it.name })
    }

    @Test
    fun aPlaceSeenTwiceIsTheSamePlace() {
        val (a, b, c) =
            places(
                mapOf(
                    "transportation_name" to
                        listOf(
                            TestFeature(null, mapOf("name" to "Bahnhofstrasse", "class" to "minor"), listOf(10 to 10)),
                            TestFeature(null, mapOf("name" to "Bahnhofstrasse", "class" to "minor"), listOf(20 to 20)),
                            TestFeature(null, mapOf("name" to "Bahnhofstrasse", "class" to "minor"), listOf(4000 to 4000)),
                        ),
                ),
            )
        assertEquals(a.sameness, b.sameness)
        assertTrue(a.sameness != c.sameness)
    }
}
