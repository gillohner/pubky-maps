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
import kotlinx.coroutines.launch
import kotlin.time.Clock
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
    fun areas(): Set<String>

    fun replace(
        area: String,
        places: Sequence<OfflinePlace>,
        indexedAt: Long,
    ): Int

    fun delete(area: String)
}

/**
 * Keeps [store] in step with the offline maps ([maps]): the places of an area are read from its
 * tiles once it is Ready, read again once an update of its map data has finished, and deleted with
 * the area. Tiles are read only while no area downloads (MapLibre writes them then); tiles that
 * cannot be read now are tried again after [retry], up to [MAX_TRIES] times per area and process.
 */
class OfflinePlaceIndexer(
    private val maps: StateFlow<OfflineMapsState>,
    private val store: OfflinePlaceStore,
    private val tiles: AreaTiles,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val clock: Clock = Clock.System,
    private val retry: Duration = RETRY,
) {
    /** Areas seen updating in this process: their places are read again once they are Ready. */
    private val updated = mutableSetOf<String>()

    /** Failed reads per area in this process. */
    private val failures = mutableMapOf<String, Int>()

    /** Counts the retries, so the same list of areas is looked at again. */
    private val tries = MutableStateFlow(0)

    private var retrying = false

    fun start() {
        scope.launch(io) {
            combine(maps.filterIsInstance<OfflineMapsState.Ready>(), tries) { state, _ -> state.maps }
                .distinctUntilChanged()
                .conflate()
                .collect { sync(it) }
        }
    }

    private suspend fun sync(maps: List<OfflineMap>) {
        maps.filter { (it.status as? OfflineMapStatus.Downloading)?.update == true }.forEach { updated += it.id }
        val stored =
            try {
                store.areas()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return later()
            }
        val listed = maps.map { it.id }.toSet()
        (stored - listed).forEach { area -> runCatching { store.delete(area) } }
        if (maps.any { it.status is OfflineMapStatus.Downloading }) return
        val wanted =
            maps
                .filter { it.status == OfflineMapStatus.Ready && (it.id !in stored || it.id in updated) }
                .map { it.id }
                .filter { (failures[it] ?: 0) < MAX_TRIES }
        if (wanted.isEmpty()) return
        val done =
            try {
                tiles.open { places ->
                    wanted.filter { area ->
                        val found = places.places(area) ?: return@filter false
                        store.replace(area, found, clock.now().toEpochMilliseconds())
                        true
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
        done?.forEach { updated -= it }
        val failed = wanted - done.orEmpty().toSet()
        failed.forEach { failures[it] = (failures[it] ?: 0) + 1 }
        if (failed.isNotEmpty()) later()
    }

    private fun later() {
        if (retrying) return
        retrying = true
        scope.launch {
            delay(retry)
            retrying = false
            tries.value++
        }
    }

    companion object {
        val RETRY = 1.minutes

        /** Each try copies MapLibre's database: an area that fails this often waits for the next start. */
        const val MAX_TRIES = 5
    }
}
