package to.pubky.maps.offline

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import to.pubky.maps.links.GeoPoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class OfflinePlaceIndexerTest {
    private class FakeStore : OfflinePlaceStore {
        val stored = mutableMapOf<String, List<String>>()
        val readAt = mutableMapOf<String, Long>()
        val replaced = ArrayList<String>()

        override fun areas() = stored.keys.associateWith { readAt.getValue(it) }

        override fun replace(
            area: String,
            newDataAt: Long,
            places: Sequence<OfflinePlace>,
        ) {
            replaced += area
            stored[area] = places.map { it.name }.toList()
            readAt[area] = newDataAt
        }

        override fun delete(area: String) {
            stored -= area
            readAt -= area
        }
    }

    /** The tiles of each area; [busy] makes every open find them busy, [broken] makes it fail. */
    private class FakeTiles : AreaTiles {
        val areas = mutableMapOf<String, List<String>>()
        var busy = false
        var broken = false
        var opened = 0

        /** How long a read takes. */
        var takes = Duration.ZERO

        override suspend fun <T> open(read: (TilePlaces) -> T): T? {
            opened++
            delay(takes)
            if (busy) return null
            if (broken) throw IllegalStateException("MapLibre's offline database has version 7")
            return read(
                object : TilePlaces {
                    override fun places(area: String) =
                        areas[area]?.asSequence()?.map { OfflinePlace(it, null, "amenity", "cafe", "Café", GeoPoint(47.0, 8.0)) }
                },
            )
        }
    }

    private val maps = MutableStateFlow<OfflineMapsState>(OfflineMapsState.Loading)
    private val fetching = MutableStateFlow(false)
    private val store = FakeStore()
    private val tiles = FakeTiles()

    private fun map(
        id: String,
        status: OfflineMapStatus,
        newDataAt: Long = 0,
    ) = OfflineMap(id, "Map $id", 0, status, 0, newDataAt)

    private fun TestScope.start() {
        val dispatcher = StandardTestDispatcher(testScheduler)
        OfflinePlaceIndexer(maps, fetching, store, tiles, backgroundScope, io = dispatcher).start()
        runCurrent()
    }

    @Test
    fun aReadyAreaIsIndexedOnceAndDeletedWithTheArea() =
        runTest {
            tiles.areas["a"] = listOf("Café Rosa", "Beck")
            start()
            maps.value = OfflineMapsState.Ready(listOf(map("a", OfflineMapStatus.Downloading(40))))
            runCurrent()
            assertEquals(emptyMap(), store.stored)
            maps.value = OfflineMapsState.Ready(listOf(map("a", OfflineMapStatus.Ready)))
            runCurrent()
            assertEquals(mapOf("a" to listOf("Café Rosa", "Beck")), store.stored)
            // A check that finds nothing new leaves the area Ready: its places are not read again.
            maps.value = OfflineMapsState.Ready(listOf(map("a", OfflineMapStatus.Ready).copy(bytes = 10)))
            runCurrent()
            assertEquals(listOf("a"), store.replaced)
            maps.value = OfflineMapsState.Ready(emptyList())
            runCurrent()
            assertEquals(emptyMap(), store.stored)
        }

    @Test
    fun noAreaIsReadWhileOneDownloads() =
        runTest {
            tiles.areas["a"] = listOf("Café Rosa")
            start()
            maps.value =
                OfflineMapsState.Ready(listOf(map("a", OfflineMapStatus.Ready), map("b", OfflineMapStatus.Downloading(10))))
            runCurrent()
            assertEquals(0, tiles.opened)
            maps.value = OfflineMapsState.Ready(listOf(map("a", OfflineMapStatus.Ready), map("b", OfflineMapStatus.Paused(10))))
            runCurrent()
            assertEquals(setOf("a"), store.stored.keys)
        }

    @Test
    fun noAreaIsReadWhileACheckFetchesTilesAndItsAreaStaysReady() =
        runTest {
            tiles.areas["a"] = listOf("Café Rosa")
            fetching.value = true
            start()
            maps.value = OfflineMapsState.Ready(listOf(map("a", OfflineMapStatus.Ready)))
            runCurrent()
            assertEquals(0, tiles.opened)
            // The walk's end alone, with the list as it was, starts the read.
            fetching.value = false
            runCurrent()
            assertEquals(setOf("a"), store.stored.keys)
        }

    @Test
    fun anAreaIsReadAgainOnceItIsReadyWithNewMapData() =
        runTest {
            tiles.areas["a"] = listOf("Café Rosa")
            start()
            maps.value = OfflineMapsState.Ready(listOf(map("a", OfflineMapStatus.Ready)))
            runCurrent()
            tiles.areas["a"] = listOf("Neu")
            maps.value = OfflineMapsState.Ready(listOf(map("a", OfflineMapStatus.Downloading(20, update = true))))
            runCurrent()
            assertEquals(listOf("a"), store.replaced)
            maps.value = OfflineMapsState.Ready(listOf(map("a", OfflineMapStatus.Ready, newDataAt = 5)))
            runCurrent()
            assertEquals(listOf("Neu"), store.stored["a"])
            // An update fast enough never to show as updating: only its new data says it ran.
            tiles.areas["a"] = listOf("Neuer")
            maps.value = OfflineMapsState.Ready(listOf(map("a", OfflineMapStatus.Ready, newDataAt = 9)))
            runCurrent()
            assertEquals(listOf("Neuer"), store.stored["a"])
            assertEquals(listOf("a", "a", "a"), store.replaced)
        }

    @Test
    fun anAreaReadFromOlderMapDataBeforeTheAppStartedIsReadAgain() =
        runTest {
            store.replace("a", 3, sequenceOf(OfflinePlace("Alt", null, "amenity", "cafe", "Café", GeoPoint(47.0, 8.0))))
            tiles.areas["a"] = listOf("Neu")
            start()
            maps.value = OfflineMapsState.Ready(listOf(map("a", OfflineMapStatus.Ready, newDataAt = 3)))
            runCurrent()
            assertEquals(0, tiles.opened)
            maps.value = OfflineMapsState.Ready(listOf(map("a", OfflineMapStatus.Ready, newDataAt = 8)))
            runCurrent()
            assertEquals(listOf("Neu"), store.stored["a"])
            assertEquals(8L, store.readAt["a"])
        }

    @Test
    fun anUpdateThatEndsWhileTheSameAreaIsReadIsReadAfterIt() =
        runTest {
            tiles.areas["a"] = listOf("Café Rosa")
            tiles.takes = 30.seconds
            start()
            maps.value = OfflineMapsState.Ready(listOf(map("a", OfflineMapStatus.Ready)))
            runCurrent()
            // The read takes the tiles as they were at its start: those of newDataAt 0.
            maps.value = OfflineMapsState.Ready(listOf(map("a", OfflineMapStatus.Downloading(5, update = true))))
            runCurrent()
            maps.value = OfflineMapsState.Ready(listOf(map("a", OfflineMapStatus.Ready, newDataAt = 4)))
            runCurrent()
            advanceTimeBy(31.seconds)
            runCurrent()
            assertEquals(listOf("Café Rosa"), store.stored["a"])
            assertEquals(0L, store.readAt["a"])
            tiles.areas["a"] = listOf("Neu")
            advanceTimeBy(30.seconds)
            runCurrent()
            assertEquals(listOf("Neu"), store.stored["a"])
            assertEquals(4L, store.readAt["a"])
        }

    @Test
    fun brokenTilesAreTriedAgainAMinuteLaterAndAtMostFiveTimes() =
        runTest {
            tiles.areas["a"] = listOf("Café Rosa")
            tiles.broken = true
            start()
            maps.value = OfflineMapsState.Ready(listOf(map("a", OfflineMapStatus.Ready)))
            runCurrent()
            assertEquals(1, tiles.opened)
            advanceTimeBy(1.minutes)
            runCurrent()
            assertEquals(2, tiles.opened)
            advanceTimeBy(10.minutes)
            runCurrent()
            assertEquals(OfflinePlaceIndexer.MAX_TRIES, tiles.opened)
            tiles.broken = false
            maps.value = OfflineMapsState.Ready(listOf(map("a", OfflineMapStatus.Ready).copy(bytes = 1)))
            runCurrent()
            assertEquals(OfflinePlaceIndexer.MAX_TRIES, tiles.opened)
            assertEquals(emptyMap(), store.stored)
        }

    @Test
    fun busyTilesAreTriedAgainLongerAndCountForNoArea() =
        runTest {
            tiles.areas["a"] = listOf("Café Rosa")
            tiles.busy = true
            start()
            maps.value = OfflineMapsState.Ready(listOf(map("a", OfflineMapStatus.Ready)))
            runCurrent()
            advanceTimeBy(9.minutes)
            runCurrent()
            assertEquals(10, tiles.opened)
            tiles.busy = false
            advanceTimeBy(1.minutes)
            runCurrent()
            assertEquals(setOf("a"), store.stored.keys)
        }

    @Test
    fun busyTilesStopAfterHalfAnHour() =
        runTest {
            tiles.busy = true
            start()
            maps.value = OfflineMapsState.Ready(listOf(map("a", OfflineMapStatus.Ready)))
            runCurrent()
            advanceTimeBy(60.minutes)
            runCurrent()
            assertEquals(OfflinePlaceIndexer.MAX_BUSY_TRIES, tiles.opened)
        }

    @Test
    fun anUpdateThatEndsWhileAnotherAreaIsReadIsNotMissed() =
        runTest {
            tiles.areas["b"] = listOf("Beck")
            start()
            maps.value = OfflineMapsState.Ready(listOf(map("b", OfflineMapStatus.Ready)))
            runCurrent()
            // A new area is read for 30 s; b's update starts and ends meanwhile.
            tiles.areas["a"] = listOf("Café Rosa")
            tiles.takes = 30.seconds
            maps.value = OfflineMapsState.Ready(listOf(map("a", OfflineMapStatus.Ready), map("b", OfflineMapStatus.Ready)))
            runCurrent()
            tiles.areas["b"] = listOf("Beck", "Neu")
            maps.value =
                OfflineMapsState.Ready(listOf(map("a", OfflineMapStatus.Ready), map("b", OfflineMapStatus.Downloading(5, update = true))))
            runCurrent()
            maps.value = OfflineMapsState.Ready(listOf(map("a", OfflineMapStatus.Ready), map("b", OfflineMapStatus.Ready, newDataAt = 6)))
            runCurrent()
            advanceTimeBy(70.seconds)
            runCurrent()
            assertEquals(listOf("Beck", "Neu"), store.stored["b"])
        }

    @Test
    fun anAreaTheTilesDoNotHoldIsTriedAgain() =
        runTest {
            start()
            maps.value = OfflineMapsState.Ready(listOf(map("a", OfflineMapStatus.Ready)))
            runCurrent()
            assertEquals(emptyMap(), store.stored)
            tiles.areas["a"] = listOf("Café Rosa")
            advanceTimeBy(1.minutes)
            runCurrent()
            assertEquals(setOf("a"), store.stored.keys)
        }
}
