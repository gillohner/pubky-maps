package to.pubky.maps.offline

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import to.pubky.maps.map.MapPlacesLayer
import to.pubky.maps.place.CollectionRefs
import to.pubky.maps.place.GeoBox
import to.pubky.maps.place.MapPlace
import to.pubky.maps.place.MapPlacesPage
import to.pubky.maps.place.PlaceCardInfo
import to.pubky.maps.place.PlaceData
import to.pubky.maps.place.PlaceList
import to.pubky.maps.place.PlacePerson
import to.pubky.maps.place.PlacePost
import to.pubky.maps.place.ResourceView
import to.pubky.maps.search.SourceAnswer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** Pinakes' places of any box: [perPage] places a page, [pages] pages. */
private class PinakesPlaces : PlaceData {
    val asked = mutableListOf<Pair<GeoBox, String?>>()
    var down = false
    var pages = 1
    var perPage = 3

    override suspend fun placesIn(
        bbox: GeoBox,
        cursor: String?,
    ): SourceAnswer<MapPlacesPage> {
        asked += bbox to cursor
        if (down) return SourceAnswer.Unreachable
        val page = cursor?.toInt() ?: 0
        val places =
            (0 until perPage).map { i ->
                MapPlace("geo:${bbox.south},${bbox.west};p=$page;i=$i", "Place $page.$i", bbox.south, bbox.west, "Cafe", "amenity/cafe")
            }
        return SourceAnswer.Answered(MapPlacesPage(places, (page + 1).takeIf { it < pages }?.toString()), "p")
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

private class MemoryStore : OfflinePubkyStore {
    class Kept(
        val box: GeoBox,
        val fetchedAt: Long,
        val places: List<MapPlace>,
    )

    val kept = mutableMapOf<String, Kept>()
    var broken = false

    override fun areas(): Map<String, Long> {
        if (broken) error("disk")
        return kept.mapValues { it.value.fetchedAt }
    }

    override fun replace(
        area: String,
        box: GeoBox,
        fetchedAt: Long,
        places: List<MapPlace>,
    ) {
        kept[area] = Kept(box, fetchedAt, places)
    }

    override fun delete(area: String) {
        kept.remove(area)
    }

    override fun placesIn(
        box: GeoBox,
        limit: Long,
    ): List<MapPlace>? = error("not used")
}

private class TestClock(
    val scope: TestScope,
) : Clock {
    override fun now() = Instant.fromEpochMilliseconds(1_800_000_000_000 + scope.testScheduler.currentTime)
}

class OfflinePubkyPlacesTest {
    private val zurich = AreaBounds(west = 8.51, south = 47.36, east = 8.56, north = 47.39)
    private val bern = AreaBounds(west = 7.42, south = 46.93, east = 7.47, north = 46.96)

    private fun area(
        id: String,
        bounds: AreaBounds?,
        status: OfflineMapStatus = OfflineMapStatus.Ready,
    ) = OfflineMap(id = id, name = id, createdAt = 0, status = status, bytes = 0, bounds = bounds)

    private class Running(
        val maps: MutableStateFlow<OfflineMapsState>,
        val data: PinakesPlaces,
        val store: MemoryStore,
        var unmetered: Boolean,
    )

    /**
     * Runs [test] with the places of the areas of [maps] kept from the start of the test clock: the
     * first look at them runs at the test's first wait. Stopped after it.
     */
    private fun keeping(
        vararg maps: OfflineMap,
        unmetered: Boolean = true,
        test: suspend TestScope.(Running) -> Unit,
    ) = runTest {
        val running =
            Running(MutableStateFlow<OfflineMapsState>(OfflineMapsState.Ready(maps.toList())), PinakesPlaces(), MemoryStore(), unmetered)
        val job = Job()
        OfflinePubkyPlaces(
            maps = running.maps,
            data = running.data,
            store = running.store,
            scope = CoroutineScope(coroutineContext + job),
            unmetered = { running.unmetered },
            clock = TestClock(this),
            io = StandardTestDispatcher(testScheduler),
        ).start()
        try {
            test(running)
        } finally {
            job.cancel()
        }
    }

    private fun TestScope.after(time: Duration) {
        advanceTimeBy(time)
        runCurrent()
    }

    @Test
    fun aListedAreasPlacesAreFetchedForTheAreaOnTheLayersGridAndKept() =
        keeping(area("zh", zurich, OfflineMapStatus.Downloading(percent = 10))) { running ->
            running.data.pages = 3
            after(5.seconds)
            val box = GeoBox(8.5, 47.3125, 8.5625, 47.4375)
            assertEquals(box, GeoBox(8.51, 47.36, 8.56, 47.39).snappedOutward(MapPlacesLayer.GRID_DEGREES))
            assertEquals(listOf(box to null, box to "1", box to "2"), running.data.asked)
            val kept = running.store.kept.getValue("zh")
            assertEquals(box, kept.box)
            assertEquals(9, kept.places.size)
            assertEquals(1_800_000_000_000, kept.fetchedAt)
        }

    @Test
    fun aFetchTakesAtMostTwentyPagesOneEveryHalfSecond() =
        keeping(area("zh", zurich)) { running ->
            running.data.pages = 50
            after(9.seconds)
            // 18 gaps of 0.5 s before the 19th page.
            assertEquals(19, running.data.asked.size)
            after(1.seconds)
            assertEquals(20, running.data.asked.size)
            assertEquals(
                60,
                running.store.kept
                    .getValue("zh")
                    .places.size,
            )
            after(1.hours)
            assertEquals(20, running.data.asked.size)
        }

    @Test
    fun aDeletedAreasPlacesGoAndANewAreasCome() =
        keeping(area("zh", zurich)) { running ->
            after(5.seconds)
            assertEquals(setOf("zh"), running.store.kept.keys)
            running.maps.value = OfflineMapsState.Ready(listOf(area("be", bern)))
            after(5.seconds)
            assertEquals(setOf("be"), running.store.kept.keys)
            assertEquals(
                GeoBox(7.375, 46.875, 7.5, 47.0),
                running.store.kept
                    .getValue("be")
                    .box,
            )
        }

    @Test
    fun withoutPinakesNothingIsKeptAndAllAreTriedAgainLater() =
        keeping(area("zh", zurich), area("be", bern), area("lost", bounds = null)) { running ->
            running.data.down = true
            after(5.seconds)
            // One request, not one per area.
            assertEquals(1, running.data.asked.size)
            assertTrue(running.store.kept.isEmpty())
            running.data.down = false
            after(OfflinePubkyPlaces.RETRY)
            // An area without bounds (its pack lost its metadata) has nothing to ask for.
            assertEquals(setOf("zh", "be"), running.store.kept.keys)
        }

    @Test
    fun keptPlacesAreFetchedAgainAfterADayOnlyOnAnUnmeteredNetwork() =
        keeping(area("zh", zurich), unmetered = false) { running ->
            // The first fetch does not wait for Wi-Fi: the user just asked for the area.
            after(5.seconds)
            assertEquals(1, running.data.asked.size)
            after(1.days)
            assertEquals(1, running.data.asked.size)
            running.unmetered = true
            after(OfflinePubkyPlaces.RETRY)
            assertEquals(2, running.data.asked.size)
            assertTrue(
                running.store.kept
                    .getValue("zh")
                    .fetchedAt > 1_800_000_000_000 + 1.days.inWholeMilliseconds,
            )
            after(23.hours)
            assertEquals(2, running.data.asked.size)
        }

    @Test
    fun aFailingDiskIsTriedAgainOnTheNextTick() =
        keeping(area("zh", zurich)) { running ->
            running.store.broken = true
            after(5.seconds)
            assertTrue(running.data.asked.isEmpty())
            running.store.broken = false
            after(OfflinePubkyPlaces.RETRY)
            assertEquals(setOf("zh"), running.store.kept.keys)
        }

    @Test
    fun aKeptAreaIsNotAskedAgainWithinADay() =
        keeping(area("zh", zurich)) { running ->
            after(5.seconds)
            after(23.hours + 50.minutes)
            assertEquals(1, running.data.asked.size)
        }
}
