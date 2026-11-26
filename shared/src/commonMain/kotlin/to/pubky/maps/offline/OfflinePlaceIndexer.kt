package to.pubky.maps.offline

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/** The places in the map tiles of offline areas, as [AreaTiles] opens them. */
interface TilePlaces {
    /** The places of [area]'s tiles, read as the sequence is walked; null when the tiles have no pack of [area]. */
    fun places(area: String): Sequence<OfflinePlace>?
}

/** Opens the map tiles of the offline areas for reading. */
interface AreaTiles {
    /** Runs [read] on the tiles; null when they cannot be read now (busy, no room for a copy, unknown layout). */
    suspend fun <T> open(read: (TilePlaces) -> T): T?
}

/** Where the places of the offline areas are kept. Calls block on disk. */
interface OfflinePlaceStore {
    /** The areas whose places are here, with the [OfflineMap.newDataAt] they were read at. */
    fun areas(): Map<String, Long>

    /** Stores the places of [area], read at its [newDataAt], in place of those it had. */
    fun replace(
        area: String,
        newDataAt: Long,
        places: Sequence<OfflinePlace>,
    )

    fun delete(area: String)
}

/**
 * Keeps [store] in step with the offline maps ([maps]): the places of an area are read from its
 * tiles once it is Ready, read again once it is Ready with other map data than they were read from
 * ([OfflineMap.newDataAt], kept with them, so an update that finished while the app was closed or
 * during a read is not missed), and deleted with the area. Tiles are read only while no area
 * downloads (MapLibre writes them then). A read is tried again after [retry]: up to [MAX_TRIES]
 * times per area and process when the tiles fail (no pack of the area, an unknown layout), up to
 * [MAX_BUSY_TRIES] times per process when they are busy (each try copies MapLibre's database).
 */
class OfflinePlaceIndexer(
    private val maps: StateFlow<OfflineMapsState>,
    private val store: OfflinePlaceStore,
    private val tiles: AreaTiles,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val retry: Duration = RETRY,
) {
    /** Failed reads per area in this process. */
    private val failures = mutableMapOf<String, Int>()

    /** Reads in this process that found the tiles busy. */
    private var busy = 0

    /** Counts the retries, so the same list of areas is looked at again. */
    private val tries = MutableStateFlow(0)

    private val retrying = MutableStateFlow(false)

    fun start() {
        scope.launch(io) {
            val lists =
                maps
                    .filterIsInstance<OfflineMapsState.Ready>()
                    .map { it.maps }
                    .distinctUntilChanged()
            combine(lists, tries) { list, _ -> list }
                .conflate()
                .collect { sync(it) }
        }
    }

    private suspend fun sync(maps: List<OfflineMap>) {
        val stored =
            try {
                store.areas()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return later()
            }
        val listed = maps.map { it.id }.toSet()
        (stored.keys - listed).forEach { area -> runCatching { store.delete(area) } }
        if (maps.any { it.status is OfflineMapStatus.Downloading }) return
        // Each with the map data it has now: an update that ends during the read changes it again.
        val wanted =
            maps
                .filter { it.status == OfflineMapStatus.Ready && stored[it.id] != it.newDataAt }
                .filter { (failures[it.id] ?: 0) < MAX_TRIES }
                .associate { it.id to it.newDataAt }
        if (wanted.isEmpty() || busy >= MAX_BUSY_TRIES) return
        val done =
            try {
                tiles.open { places ->
                    wanted.keys.filter { area ->
                        val found = places.places(area) ?: return@filter false
                        store.replace(area, wanted.getValue(area), found)
                        true
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emptyList()
            }
        if (done == null) {
            busy++
            return later()
        }
        val failed = wanted.keys - done.toSet()
        failed.forEach { failures[it] = (failures[it] ?: 0) + 1 }
        if (failed.isNotEmpty()) later()
    }

    private fun later() {
        if (!retrying.compareAndSet(expect = false, update = true)) return
        scope.launch {
            delay(retry)
            retrying.value = false
            tries.update { it + 1 }
        }
    }

    companion object {
        val RETRY = 1.minutes

        /** An area whose tiles fail this often waits for the next start. */
        const val MAX_TRIES = 5

        /** Busy tiles (MapLibre writing during the copy) are tried this often per start, about half an hour. */
        const val MAX_BUSY_TRIES = 30
    }
}
