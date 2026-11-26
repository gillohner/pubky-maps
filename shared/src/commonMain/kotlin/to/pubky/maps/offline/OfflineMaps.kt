package to.pubky.maps.offline

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.random.Random
import kotlin.time.Clock

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
    /** [retrying] names the last failure while the download tries again (no connection, a server error). */
    data class Downloading(
        val percent: Int,
        val retrying: String? = null,
    ) : OfflineMapStatus

    /** Not downloading, not complete: a download the app was closed during. */
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
 * light and dark both work offline), list them, resume and delete them. A download that goes over the
 * limits of [OfflineLimits] is stopped. [downloading] is true from just before a download starts
 * until every pack this process started is complete, stopped or deleted; [OfflineMapResources]
 * throttles requests while it is.
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

    /** Read on MapLibre's threads for every request: a plain read of the current packs. */
    val downloading: Boolean get() = started.value.isNotEmpty()

    private val _picking = MutableStateFlow(false)

    /** The map shows the area picker. */
    val picking: StateFlow<Boolean> = _picking.asStateFlow()

    private val _problem = MutableStateFlow<String?>(null)

    /** The last download or delete the offline storage refused, until the next one. */
    val problem: StateFlow<String?> = _problem.asStateFlow()

    val state: StateFlow<OfflineMapsState> =
        store.state
            .map { state ->
                when (state) {
                    PackStoreState.Loading -> OfflineMapsState.Loading
                    is PackStoreState.Failed -> OfflineMapsState.Failed(state.message)
                    is PackStoreState.Ready -> OfflineMapsState.Ready(areas(state.packs))
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
                    // Deleted while this style was being created: it goes too.
                    if (metadata.area in deleted) {
                        store.delete(handle)
                        return@launch
                    }
                    started.value += handle
                    store.resume(handle)
                }
            } catch (e: Exception) {
                _problem.value = "Couldn't start the download: ${e.message ?: "the offline storage failed"}"
                // Half an area would not show in both themes: the styles already created go.
                created.forEach { handle ->
                    runCatching {
                        store.pause(handle)
                        store.delete(handle)
                    }
                    started.value -= handle
                }
            }
        }
    }

    /** Continues a paused offline map. */
    fun resume(id: String) {
        _problem.value = null
        packs.filter { it.areaId == id && it.progress.state != PackState.Complete }.forEach {
            stopping.value -= it.handle
            started.value += it.handle
            runCatching { store.resume(it.handle) }
                .onFailure { e -> _problem.value = "Couldn't resume the download: ${e.message ?: "the offline storage failed"}" }
        }
    }

    /** Pauses an offline map that is downloading; [resume] continues it. */
    fun pause(id: String) {
        _problem.value = null
        packs.filter { it.areaId == id && it.handle in started.value }.forEach { stop(it.handle) }
    }

    fun delete(id: String) {
        val area = packs.filter { it.areaId == id }
        deleted += id
        _problem.value = null
        scope.launch {
            try {
                area.forEach {
                    store.pause(it.handle)
                    store.delete(it.handle)
                    // Only now: until the pack is gone, its requests may still be on their way.
                    started.value -= it.handle
                    stopping.value -= it.handle
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
            progress.state == PackState.Complete || (handle in stopping.value && progress.state != PackState.Downloading) -> {
                started.value -= handle
                stopping.value -= handle
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

private val StoredPack.areaId: String get() = metadata?.area ?: "pack-${handle.hashCode()}"

/** The packs grouped by area, newest first. */
internal fun areas(packs: List<StoredPack>): List<OfflineMap> =
    packs
        .groupBy { it.areaId }
        .map { (id, area) ->
            val first = area.first().metadata
            val progress = area.map { it.progress }
            val required = progress.sumOf { it.requiredResources }
            val percent = if (required > 0) (100.0 * progress.sumOf { it.completedResources } / required).toInt().coerceIn(0, 100) else 0
            OfflineMap(
                id = id,
                name = first?.name ?: "Offline map",
                createdAt = first?.createdAt ?: 0,
                status =
                    when {
                        area.any { it.metadata?.stopped == true } -> {
                            OfflineMapStatus.TooLarge
                        }

                        progress.all { it.state == PackState.Complete } -> {
                            OfflineMapStatus.Ready
                        }

                        progress.any { it.state == PackState.Downloading } -> {
                            OfflineMapStatus.Downloading(percent, progress.firstNotNullOfOrNull { it.error })
                        }

                        else -> {
                            OfflineMapStatus.Paused(percent)
                        }
                    },
                // The styles of an area share its tiles: they are stored once.
                bytes = progress.maxOf { it.completedTileBytes } + progress.sumOf { it.completedBytes - it.completedTileBytes },
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
