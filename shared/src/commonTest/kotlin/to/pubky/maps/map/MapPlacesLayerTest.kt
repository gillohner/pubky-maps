package to.pubky.maps.map

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import okio.FileSystem
import okio.Path.Companion.toPath
import to.pubky.maps.links.GeoPoint
import to.pubky.maps.links.OsmId
import to.pubky.maps.links.OsmType
import to.pubky.maps.place.CollectionRefs
import to.pubky.maps.place.GeoBox
import to.pubky.maps.place.MapPlacesPage
import to.pubky.maps.place.PlaceCardInfo
import to.pubky.maps.place.PlaceData
import to.pubky.maps.place.PlaceList
import to.pubky.maps.place.PlacePerson
import to.pubky.maps.place.PlacePost
import to.pubky.maps.place.PlaceSource
import to.pubky.maps.place.ResourceView
import to.pubky.maps.place.parseMapPlaces
import to.pubky.maps.search.SourceAnswer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

private class AreaData : PlaceData {
    val asked = mutableListOf<Pair<GeoBox, String?>>()
    var down = false

    /** Pages served one after the other; past the end, an empty last page. */
    var pages: List<MapPlacesPage> =
        listOf(parseMapPlaces(FileSystem.SYSTEM.read("src/commonTest/fixtures/place/pinakes-bbox-zurich.json".toPath()) { readUtf8() }))

    override suspend fun placesIn(
        bbox: GeoBox,
        cursor: String?,
    ): SourceAnswer<MapPlacesPage> {
        asked += bbox to cursor
        if (down) return SourceAnswer.Unreachable
        val index = cursor?.toInt() ?: 0
        return SourceAnswer.Answered(pages.getOrElse(index) { MapPlacesPage(emptyList(), null) }, "p")
    }

    override suspend fun card(uris: List<String>): SourceAnswer<PlaceCardInfo?> = error("not used")

    override suspend fun collections(
        cardId: String,
        viewer: String?,
    ): SourceAnswer<CollectionRefs> = error("not used")

    override suspend fun views(
        uris: List<String>,
        viewer: String?,
    ): SourceAnswer<List<ResourceView>> = error("not used")

    override suspend fun posts(
        resourceId: String,
        embeds: Boolean,
        skip: Int,
        limit: Int,
    ): SourceAnswer<List<PlacePost>> = error("not used")

    override suspend fun people(
        ids: List<String>,
        viewer: String?,
    ): SourceAnswer<List<PlacePerson>> = error("not used")

    override suspend fun lists(
        postUris: List<String>,
        viewer: String?,
    ): SourceAnswer<List<PlaceList>> = error("not used")
}

private class TestClock(
    var now: Instant = Instant.fromEpochMilliseconds(1_800_000_000_000),
) : Clock {
    override fun now() = now
}

class MapPlacesLayerTest {
    private val oldTown = GeoBox(west = 8.535, south = 47.365, east = 8.55, north = 47.375)

    private fun TestScope.layer(
        data: PlaceData,
        clock: Clock = TestClock(),
    ) = MapPlacesLayer(data, this, clock)

    @Test
    fun theViewGrownByHalfIsAskedOnce() =
        runTest {
            val data = AreaData()
            val layer = layer(data)
            layer.onCameraIdle(oldTown, zoom = 15.0)
            advanceUntilIdle()
            assertEquals(11, layer.state.value.places.size)
            assertFalse(layer.state.value.hidden)
            val asked = data.asked.single().first
            assertTrue(asked.contains(oldTown))
            assertEquals(oldTown.west - (oldTown.east - oldTown.west) * 0.5, asked.west, 1e-9)
            // A small pan stays inside: no request.
            layer.onCameraIdle(oldTown.copy(west = oldTown.west + 0.002, east = oldTown.east + 0.002), zoom = 15.0)
            advanceUntilIdle()
            assertEquals(1, data.asked.size)
            // Leaving the area asks again.
            layer.onCameraIdle(GeoBox(8.6, 47.4, 8.62, 47.41), zoom = 15.0)
            advanceUntilIdle()
            assertEquals(2, data.asked.size)
        }

    @Test
    fun zoomedOutTheLayerHidesAndAsksNothing() =
        runTest {
            val data = AreaData()
            val layer = layer(data)
            layer.onCameraIdle(GeoBox(5.0, 45.0, 11.0, 48.0), zoom = 8.0)
            advanceUntilIdle()
            assertTrue(layer.state.value.hidden)
            assertEquals(0, data.asked.size)
        }

    @Test
    fun atMostTwoPagesThenZoomingInAsksAgain() =
        runTest {
            val one =
                parseMapPlaces(FileSystem.SYSTEM.read("src/commonTest/fixtures/place/pinakes-bbox-zurich.json".toPath()) { readUtf8() })
            val data =
                AreaData().apply {
                    pages =
                        (0 until 3).map { i ->
                            MapPlacesPage(one.places.map { it.copy(uri = "${it.uri}#$i") }, cursor = (i + 1).toString())
                        }
                }
            val layer = layer(data)
            layer.onCameraIdle(oldTown, zoom = 13.0)
            advanceUntilIdle()
            assertEquals(listOf(null, "1"), data.asked.map { it.second })
            assertEquals(22, layer.state.value.places.size)
            assertTrue(layer.state.value.truncated)
            // Cut at the cap: two levels closer asks for the denser view.
            layer.onCameraIdle(oldTown, zoom = 15.0)
            advanceUntilIdle()
            assertEquals(4, data.asked.size)
        }

    @Test
    fun anOldAnswerIsRefreshedAndAFailureKeepsWhatIsShown() =
        runTest {
            val clock = TestClock()
            val data = AreaData()
            val layer = layer(data, clock)
            layer.onCameraIdle(oldTown, zoom = 15.0)
            advanceUntilIdle()
            clock.now += 6.minutes
            data.down = true
            layer.onCameraIdle(oldTown, zoom = 15.0)
            advanceUntilIdle()
            assertEquals(2, data.asked.size)
            assertEquals(11, layer.state.value.places.size)
            // After a failure the next rest asks again.
            data.down = false
            layer.onCameraIdle(oldTown, zoom = 15.0)
            advanceUntilIdle()
            assertEquals(3, data.asked.size)
        }

    @Test
    fun viewsOnOtherWorldCopiesAreWrapped() {
        assertEquals(GeoBox(8.5, 47.25, 8.75, 47.5), viewBox(368.5, 47.25, 368.75, 47.5))
        assertEquals(GeoBox(170.0, -10.0, 180.0, 10.0), viewBox(170.0, -10.0, 180.0, 10.0))
        assertEquals(GeoBox(-180.0, -90.0, 180.0, 90.0), viewBox(-200.0, -95.0, 300.0, 95.0))
        // Across the antimeridian there is no single box.
        assertNull(viewBox(175.0, 0.0, 185.0, 1.0))
    }

    @Test
    fun tileFeatureIdsNameOsmElements() {
        // OpenFreeMap's Zürich tile: the Kunsthaus museum, the Grossmünster, a café, a tram stop.
        assertEquals(OsmId(OsmType.Relation, 3768127), osmIdOfTileFeature(37681273))
        assertEquals(OsmId(OsmType.Way, 36916418), osmIdOfTileFeature(369164182))
        assertEquals(OsmId(OsmType.Node, 9201875603), osmIdOfTileFeature(92018756031))
        assertEquals(OsmId(OsmType.Node, 4878436673), osmIdOfTileFeature(48784366730))
        assertNull(osmIdOfTileFeature(12345678905))
        assertNull(osmIdOfTileFeature(3))
    }

    @Test
    fun aTappedPointOfInterestOpensByItsOsmLink() {
        val point = GeoPoint(47.3702, 8.548)
        val museum = tappedPlace(37681273, mapOf("name" to "Kunsthaus", "class" to "museum", "subclass" to "museum"), point)!!
        assertEquals("https://www.openstreetmap.org/relation/3768127", museum.uri)
        assertEquals("Museum", museum.category)
        assertEquals(PlaceSource.Map, museum.source)
        // Shortbread (VersaTiles) names the kind by its OSM key.
        val school = tappedPlace(273068472, mapOf("name" to "Rämibühl", "amenity" to "school"), point)!!
        assertEquals("School", school.category)
        assertEquals(OsmId(OsmType.Way, 27306847), school.osm)
        assertNull(tappedPlace(37681273, emptyMap(), point))
        assertNull(tappedPlace(null, mapOf("name" to "x"), point))
    }
}
