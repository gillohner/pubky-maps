package to.pubky.maps.offline

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import to.pubky.maps.links.GeoPoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.minutes

@OptIn(ExperimentalCoroutinesApi::class)
class OfflinePlaceIndexerTest {
    private class FakeStore : OfflinePlaceStore {
        val stored = mutableMapOf<String, List<String>>()
        val replaced = ArrayList<String>()

        override fun areas() = stored.keys.toSet()

        override fun replace(
            area: String,
            places: Sequence<OfflinePlace>,
            indexedAt: Long,
        ): Int {
            replaced += area
            stored[area] = places.map { it.name }.toList()
            return stored.getValue(area).size
        }

        override fun delete(area: String) {
            stored -= area
        }
    }

    /** The tiles of each area; [busy] makes every open fail as when MapLibre is writing. */
    private class FakeTiles : AreaTiles {
        val areas = mutableMapOf<String, List<String>>()
        var busy = false
        var opened = 0

        override suspend fun <T> open(read: (TilePlaces) -> T): T? {
            opened++
            if (busy) return null
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
    fun busyTilesAreTriedAgainAMinuteLaterAndAtMostFiveTimes() =
        runTest {
            tiles.areas["a"] = listOf("Café Rosa")
            tiles.busy = true
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
            tiles.busy = false
            maps.value = OfflineMapsState.Ready(listOf(map("a", OfflineMapStatus.Ready).copy(bytes = 1)))
            runCurrent()
            assertEquals(OfflinePlaceIndexer.MAX_TRIES, tiles.opened)
            assertEquals(emptyMap(), store.stored)
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
