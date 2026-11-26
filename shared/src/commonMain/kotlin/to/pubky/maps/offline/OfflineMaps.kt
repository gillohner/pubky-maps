package to.pubky.maps.offline

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** One offline map in the list: the packs of one downloaded area (one per map style) together. */
data class OfflineMap(
    val id: String,
    val name: String,
    val createdAt: Long,
    val status: OfflineMapStatus,
    /** About what the map takes on the phone. */
    val bytes: Long,
)

sealed interface OfflineMapStatus {
    /**
     * [retrying] names the last failure while the download tries again (no connection, a server
     * error). An [update] fetches the map data the map server published since the area was ready.
     */
    data class Downloading(
        val percent: Int,
        val retrying: String? = null,
        val update: Boolean = false,
    ) : OfflineMapStatus

    /** Not downloading, not complete: paused by the user, or a download the app was closed during. */
    data class Paused(
        val percent: Int,
    ) : OfflineMapStatus

    data object Ready : OfflineMapStatus

    /** Stopped at the limit of one download ([OfflineLimits]). */
    data object TooLarge : OfflineMapStatus
}

sealed interface OfflineMapsState {
    data object Loading : OfflineMapsState

    data class Ready(
        val maps: List<OfflineMap>,
    ) : OfflineMapsState

    data class Failed(
        val message: String,
    ) : OfflineMapsState
}

/**
 * The offline maps of the phone: download the map of an area (in every map style the app shows, so
 * light and dark both work offline), list them, resume and delete them. The styles of an area
 * download one after the other, so the second finds the tiles they share in the database instead
 * of fetching them again. A download that goes over the limits of [OfflineLimits] is stopped. Ready
 * areas are checked for new map data now and then ([checkForUpdates]). [downloading] is true from
 * just before a download starts until every pack this process started is complete, stopped or
 * deleted; [OfflineMapResources] throttles requests while it is.
 */
class OfflineMaps(
    private val store: PackStore,
    private val scope: CoroutineScope,
    private val clock: Clock = Clock.System,
    private val newId: () -> String = { Random.nextLong().toULong().toString(radix = 36) },
) {
    private val started = MutableStateFlow<Set<Any>>(emptySet())

    /** Packs paused by the app that MapLibre may not have paused yet. */
    private val stopping = MutableStateFlow<Set<Any>>(emptySet())

    /** Areas deleted in this process, so a style still being created for one is not downloaded. */
    private val deleted = mutableSetOf<String>()

    /** The packs each download of this process created, which the store may not list yet. */
    private val createdFor = mutableMapOf<String, MutableList<Any>>()

    /** The download or check that runs for an area: its packs one after the other. */
    private val runs = mutableMapOf<String, Job>()

    /**
     * Packs a check resumed less than [QUIET_CHECK] ago. They were complete before, so until then a
     * report of Complete may still be the one from before the check.
     */
    private val walking = mutableSetOf<Any>()

    /** The areas being checked, with what they took on the phone before the check. */
    private val checking = MutableStateFlow<Map<String, Long>>(emptyMap())

    /** Checked areas that are fetching new map data: they show as updating. */
    private val updating = MutableStateFlow<Set<String>>(emptySet())

    /** Read on MapLibre's threads for every request: a plain read of the current packs. */
    val downloading: Boolean get() = started.value.isNotEmpty()

    private val _picking = MutableStateFlow(false)

    /** The map shows the area picker. */
    val picking: StateFlow<Boolean> = _picking.asStateFlow()

    private val _problem = MutableStateFlow<String?>(null)

    /** The last download or delete the offline storage refused, until the next one. */
    val problem: StateFlow<String?> = _problem.asStateFlow()

    val state: StateFlow<OfflineMapsState> =
        combine(store.state, checking, updating) { state, checking, updating ->
            when (state) {
                PackStoreState.Loading -> OfflineMapsState.Loading
                is PackStoreState.Failed -> OfflineMapsState.Failed(state.message)
                is PackStoreState.Ready -> OfflineMapsState.Ready(areas(state.packs, checking, updating))
            }
        }.stateIn(scope, SharingStarted.Eagerly, OfflineMapsState.Loading)

    private var packs: List<StoredPack> = emptyList()

    init {
        scope.launch {
            store.state.collect { state ->
                if (state !is PackStoreState.Ready) return@collect
                packs = state.packs
                state.packs.forEach { watch(it) }
            }
        }
        scope.launch {
            delay(FIRST_CHECK)
            while (true) {
                checkForUpdates()
                delay(CHECK_INTERVAL)
            }
        }
    }

    fun startPicking() {
        _picking.value = true
    }

    fun stopPicking() {
        _picking.value = false
    }

    /**
     * Downloads [bounds] in each style of [styleUrls] (the first is the one on screen) and closes the
     * picker. [bounds] must fit in one download ([fitsInOneDownload]).
     */
    fun download(
        bounds: AreaBounds,
        styleUrls: List<String>,
    ) {
        require(fitsInOneDownload(bounds)) { "The area is larger than one download" }
        _picking.value = false
        _problem.value = null
        val metadata =
            PackMetadata(
                area = newId(),
                name = areaName(bounds),
                createdAt = clock.now().toEpochMilliseconds(),
                west = bounds.west,
                south = bounds.south,
                east = bounds.east,
                north = bounds.north,
            )
        scope.launch {
            val created = ArrayList<Any>()
            try {
                styleUrls.distinct().forEach { url ->
                    val handle = store.create(url, bounds, OfflineLimits.MAX_ZOOM, metadata)
                    created += handle
                    createdFor.getOrPut(metadata.area) { mutableListOf() } += handle
                    // Deleted while this style was being created: it goes too.
                    if (metadata.area in deleted) {
                        store.delete(handle)
                        return@launch
                    }
                }
            } catch (e: Exception) {
                _problem.value = "Couldn't start the download: ${e.message ?: "the offline storage failed"}"
                // Half an area would not show in both themes: the styles already created go.
                createdFor.remove(metadata.area)
                created.forEach { handle -> runCatching { store.delete(handle) } }
                return@launch
            }
            runInTurn(metadata.area, created)
        }
    }

    /** Continues a paused offline map. */
    fun resume(id: String) {
        _problem.value = null
        val unfinished = packs.filter { it.areaId == id && it.progress.state != PackState.Complete }.map { it.handle }
        stopping.value -= unfinished.toSet()
        runInTurn(id, unfinished)
    }

    /** Pauses an offline map that is downloading; [resume] continues it. */
    fun pause(id: String) {
        _problem.value = null
        runs.remove(id)?.cancel()
        packs.filter { it.areaId == id && it.handle in started.value }.forEach { stop(it.handle) }
    }

    /**
     * Resumes every ready area once more, one style after the other. MapLibre then walks the pack
     * again from the copies of the style and its sources in its database and fetches only what is
     * missing there. When the map is online it keeps those copies current, and a map server such as
     * OpenFreeMap moves its tiles to new addresses with each release of its map data: the walk then
     * downloads the area at the new addresses (within the limits, as any download), and without it
     * the area would draw no map offline. Nothing changed: the walk ends without a request.
     */
    internal fun checkForUpdates() {
        areas(packs).filter { it.status == OfflineMapStatus.Ready && it.id !in runs }.forEach { map ->
            val handles = packs.filter { it.areaId == map.id }.map { it.handle }
            if (handles.any { it in started.value }) return@forEach
            checking.value += map.id to map.bytes
            runInTurn(map.id, handles, check = true)
        }
    }

    /**
     * Resumes [handles] one after the other, each once the one before is complete; a pack that stops
     * another way (paused, at the limit, deleted) ends the run. A [check] walks complete packs again
     * ([checkForUpdates]); one that is still downloading after [QUIET_CHECK] shows as updating.
     */
    private fun runInTurn(
        area: String,
        handles: List<Any>,
        check: Boolean = false,
    ) {
        runs.remove(area)?.cancel()
        // Started once it is in [runs], so a run that ends at once also leaves it.
        val job =
            scope.launch(start = CoroutineStart.LAZY) {
                try {
                    for (handle in handles) {
                        if (check) walking += handle
                        started.value += handle
                        try {
                            store.resume(handle)
                        } catch (e: Exception) {
                            _problem.value = "Couldn't resume the download: ${e.message ?: "the offline storage failed"}"
                            started.value -= handle
                            return@launch
                        }
                        if (check) {
                            delay(QUIET_CHECK)
                            walking -= handle
                            if (stateOf(handle) == PackState.Complete) {
                                started.value -= handle
                                continue
                            }
                            updating.value += area
                        }
                        // [watch] takes the pack out once it is complete or paused.
                        started.first { handle !in it }
                        if (stateOf(handle) != PackState.Complete) return@launch
                    }
                } finally {
                    walking.removeAll(handles.toSet())
                    checking.value -= area
                    updating.value -= area
                    if (runs[area] === coroutineContext.job) runs.remove(area)
                }
            }
        runs[area] = job
        job.start()
    }

    private fun stateOf(handle: Any): PackState? = packs.firstOrNull { it.handle === handle }?.progress?.state

    fun delete(id: String) {
        runs.remove(id)?.cancel()
        val listed = packs.filter { it.areaId == id }.map { it.handle }
        val area = listed + createdFor.remove(id).orEmpty().filter { handle -> listed.none { it === handle } }
        deleted += id
        _problem.value = null
        scope.launch {
            try {
                area.forEach { handle ->
                    store.pause(handle)
                    store.delete(handle)
                    // Only now: until the pack is gone, its requests may still be on their way.
                    started.value -= handle
                    stopping.value -= handle
                }
            } catch (e: Exception) {
                _problem.value = "Couldn't delete the offline map: ${e.message ?: "the offline storage failed"}"
            }
        }
    }

    /**
     * Pauses a pack. It stays in [started] (and requests stay throttled) until the store reports it
     * is no longer downloading, as MapLibre pauses after the call returns.
     */
    private fun stop(handle: Any) {
        stopping.value += handle
        runCatching { store.pause(handle) }
            .onFailure { e -> _problem.value = "Couldn't pause the download: ${e.message ?: "the offline storage failed"}" }
    }

    /** Stops a pack near the limits of one download, and forgets a pack that finished or paused. */
    private fun watch(pack: StoredPack) {
        val progress = pack.progress
        val handle = pack.handle
        if (handle !in started.value) return
        when {
            handle in stopping.value && progress.state != PackState.Downloading -> {
                started.value -= handle
                stopping.value -= handle
            }

            progress.state == PackState.Complete && handle !in walking -> {
                started.value -= handle
            }

            handle !in stopping.value && overLimit(progress) -> {
                stop(handle)
                val metadata = pack.metadata ?: return
                scope.launch { runCatching { store.setMetadata(handle, metadata.copy(stopped = true)) } }
            }
        }
    }
}

/**
 * Whether a download must stop: the check runs as resources complete, while up to
 * [OfflineLimits.PARALLEL_REQUESTS] more are on their way, so it stops that many tiles (and their
 * bytes) short of the limits.
 */
internal fun overLimit(progress: PackProgress): Boolean =
    progress.completedTiles >= OfflineLimits.MAX_TILES - OfflineLimits.PARALLEL_REQUESTS ||
        progress.completedTileBytes >= OfflineLimits.MAX_TILE_BYTES - OfflineLimits.PARALLEL_REQUESTS * LARGEST_TILE_BYTES

/** About the largest vector tile of a dense city at zoom 14. */
private const val LARGEST_TILE_BYTES = 500_000L

/** The first check for new map data, after the map has loaded its style (and so refreshed its copies). */
internal val FIRST_CHECK = 1.minutes

/** OpenFreeMap lets a map keep its sources for an hour; checking twice as often finds a change in time. */
internal val CHECK_INTERVAL = 30.minutes

/** Long enough for MapLibre to walk a complete pack in its database and report it. */
internal val QUIET_CHECK = 10.seconds

private val StoredPack.areaId: String get() = metadata?.area ?: "pack-${handle.hashCode()}"

/**
 * The packs grouped by area, newest first. An area in [checking] shows as it was (Ready, its size
 * before the check) unless it is in [updating]. The percent is each style's share: the styles
 * download one after the other, and one not started yet reports nothing to do.
 */
internal fun areas(
    packs: List<StoredPack>,
    checking: Map<String, Long> = emptyMap(),
    updating: Set<String> = emptySet(),
): List<OfflineMap> =
    packs
        .groupBy { it.areaId }
        .map { (id, area) ->
            val first = area.first().metadata
            val progress = area.map { it.progress }
            val done =
                progress.sumOf {
                    when {
                        it.state == PackState.Complete -> 1.0
                        it.requiredResources > 0 -> it.completedResources.toDouble() / it.requiredResources
                        else -> 0.0
                    }
                }
            val percent = (100 * done / progress.size).toInt().coerceIn(0, 100)
            val before = checking[id]
            OfflineMap(
                id = id,
                name = first?.name ?: "Offline map",
                createdAt = first?.createdAt ?: 0,
                status =
                    when {
                        area.any { it.metadata?.stopped == true } -> {
                            OfflineMapStatus.TooLarge
                        }

                        before != null && id !in updating -> {
                            OfflineMapStatus.Ready
                        }

                        progress.all { it.state == PackState.Complete } -> {
                            OfflineMapStatus.Ready
                        }

                        progress.any { it.state == PackState.Downloading } -> {
                            OfflineMapStatus.Downloading(percent, progress.firstNotNullOfOrNull { it.error }, update = id in updating)
                        }

                        else -> {
                            OfflineMapStatus.Paused(percent)
                        }
                    },
                // The styles of an area share its tiles: they are stored once. A check counts again from 0.
                bytes =
                    maxOf(
                        before ?: 0,
                        progress.maxOf { it.completedTileBytes } + progress.sumOf { it.completedBytes - it.completedTileBytes },
                    ),
            )
        }.sortedByDescending { it.createdAt }

/** "Map around 47.38° N, 8.54° E": the offline map is named after the centre of its area. */
internal fun areaName(bounds: AreaBounds): String {
    fun degrees(value: Double): String {
        val hundredths = (abs(value) * 100).roundToInt()
        return "${hundredths / 100}.${(hundredths % 100).toString().padStart(2, '0')}°"
    }
    val lat = bounds.centerLat
    val lon = bounds.centerLon
    return "Map around ${degrees(lat)} ${if (lat < 0) "S" else "N"}, ${degrees(lon)} ${if (lon < 0) "W" else "E"}"
}
