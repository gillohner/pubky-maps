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
        val replaced = ArrayList<String>()

        override fun areas() = stored.keys.toSet()

        override fun replace(
            area: String,
            places: Sequence<OfflinePlace>,
        ) {
            replaced += area
            stored[area] = places.map { it.name }.toList()
        }

        override fun delete(area: String) {
            stored -= area
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
    private val store = FakeStore()
    private val tiles = FakeTiles()

    private fun map(
        id: String,
        status: OfflineMapStatus,
    ) = OfflineMap(id, "Map $id", 0, status, 0)

    private fun TestScope.start() {
        val dispatcher = StandardTestDispatcher(testScheduler)
        OfflinePlaceIndexer(maps, store, tiles, backgroundScope, io = dispatcher).start()
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
    fun anAreaIsReadAgainOnceItsUpdateFinished() =
        runTest {
            tiles.areas["a"] = listOf("Café Rosa")
            start()
            maps.value = OfflineMapsState.Ready(listOf(map("a", OfflineMapStatus.Ready)))
            runCurrent()
            tiles.areas["a"] = listOf("Café Rosa", "Neu")
            maps.value = OfflineMapsState.Ready(listOf(map("a", OfflineMapStatus.Downloading(20, update = true))))
            runCurrent()
            maps.value = OfflineMapsState.Ready(listOf(map("a", OfflineMapStatus.Ready)))
            runCurrent()
            assertEquals(listOf("Café Rosa", "Neu"), store.stored["a"])
            assertEquals(listOf("a", "a"), store.replaced)
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
            maps.value = OfflineMapsState.Ready(listOf(map("a", OfflineMapStatus.Ready), map("b", OfflineMapStatus.Ready).copy(bytes = 2)))
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
