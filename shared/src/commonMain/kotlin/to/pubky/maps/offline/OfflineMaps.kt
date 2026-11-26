package to.pubky.maps.offline

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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
    /** The area on the map; null when MapLibre lost the pack's metadata. */
    val bounds: AreaBounds? = null,
    /**
     * When a check last stored new map data for the area (epoch milliseconds), 0 when it has the
     * data of its first download only. It changes with every update that fetched tiles, also one
     * fast enough never to show as updating.
     */
    val newDataAt: Long = 0,
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

    /**
     * Not downloading, not complete: paused by the user, or a download the app was closed during. An
     * [update] stopped before it finished: until it does, the area may lack the map server's newest
     * tiles, and [percent] says nothing, as MapLibre counts the release before's tiles too.
     */
    data class Paused(
        val percent: Int,
        val update: Boolean = false,
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
 * areas are checked for new map data now and then ([checkForUpdates]); while [unmetered] says the
 * phone is on a network with a data cap, a check only finds out whether there is any. [downloading]
 * is true from just before a download starts until every pack this process started is complete, stopped or
 * deleted; [OfflineMapResources] throttles requests while it is.
 */
class OfflineMaps(
    private val store: PackStore,
    private val scope: CoroutineScope,
    private val clock: Clock = Clock.System,
    private val newId: () -> String = { Random.nextLong().toULong().toString(radix = 36) },
    private val unmetered: () -> Boolean = { true },
) {
    private val started = MutableStateFlow<Set<Any>>(emptySet())

    /** Packs paused by the app that MapLibre may not have paused yet. */
    private val stopping = MutableStateFlow<Set<Any>>(emptySet())

    /** The packs this process made to replace another ([replace]). */
    private val replacements = mutableListOf<Any>()

    /** Areas deleted in this process, so a style still being created for one is not downloaded. */
    private val deleted = mutableSetOf<String>()

    /** The packs each download of this process created, which the store may not list yet. */
    private val createdFor = mutableMapOf<String, MutableList<Any>>()

    /** Areas the user paused since the app started: a check does not resume their update. */
    private val held = mutableSetOf<String>()

    /** Packs the app paused (the user, a limit, a check on mobile data) since they were last resumed. */
    private val halted = mutableSetOf<Any>()

    /** The download or check that runs for an area: its packs one after the other. */
    private val runs = MutableStateFlow<Map<String, Job>>(emptyMap())

    /**
     * Packs a check resumed less than [QUIET_CHECK] ago. They were complete before, so until then a
     * report of Complete may still be the one from before the check.
     */
    private val walking = mutableSetOf<Any>()

    /** The areas being checked, with what they took on the phone before the check. */
    private val checking = MutableStateFlow<Map<String, Long>>(emptyMap())

    /** Checked areas that are fetching new map data: they show as updating. */
    private val updating = MutableStateFlow<Set<String>>(emptySet())

    /** Requests of downloads that got new data from the map server ([fetchedNewData]). */
    private val fetches = MutableStateFlow(0L)

    /** Read on MapLibre's threads for every request: a plain read of the packs downloading or being checked. */
    val downloading: Boolean get() = started.value.isNotEmpty()

    /**
     * Whether MapLibre may be writing tiles: a pack downloads ([downloading]) or a download or check
     * runs, also between its styles and during a check's walk that leaves the area Ready.
     */
    val fetchingChanges: Flow<Boolean> =
        combine(started, runs) { packs, areas -> packs.isNotEmpty() || areas.isNotEmpty() }.distinctUntilChanged()

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

    /** Every pack of the store, also those still [PackMetadata.replacing] another. */
    private val listed = MutableStateFlow<List<StoredPack>>(emptyList())

    private val packs: List<StoredPack> get() = listed.value

    /** The packs that make up the areas ([visible]). */
    private val areaPacks: List<StoredPack> get() = visible(packs)

    init {
        scope.launch {
            store.state.collect { state ->
                if (state !is PackStoreState.Ready) return@collect
                listed.value = state.packs
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

    /**
     * A request of a download or check got new data from the map server ([OfflineMapResources]);
     * called on any thread. A check's walk during which this happens stored new map data, also when
     * it ends within [QUIET_CHECK] and never shows as updating. Another area's walk at the same time
     * counts too, so an area may be marked new without new data: its places are only read again.
     */
    fun fetchedNewData() {
        fetches.update { it + 1 }
    }

    fun startPicking() {
        _picking.value = true
    }

    fun stopPicking() {
        _picking.value = false
    }

    /**
     * Downloads [bounds] in each style of [styleUrls] (the first is the one on screen) and closes the
     * picker. [bounds] must fit in one download ([fitsInOneDownload]). The area is named after
     * [place] (the town the map labels in it), else after its centre.
     */
    fun download(
        bounds: AreaBounds,
        styleUrls: List<String>,
        place: String? = null,
    ) {
        require(fitsInOneDownload(bounds)) { "The area is larger than one download" }
        _picking.value = false
        _problem.value = null
        val metadata =
            PackMetadata(
                area = newId(),
                name = place ?: areaName(bounds),
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

    /** Continues a paused offline map, or its update that stopped before it finished. */
    fun resume(id: String) {
        _problem.value = null
        held -= id
        val area = areaPacks.filter { it.areaId == id }
        val unfinished = unfinished(area)
        stopping.value -= unfinished.toSet()
        runInTurn(id, unfinished, check = area.any { it.metadata?.updating == true })
    }

    /** Pauses an offline map that is downloading or updating; [resume] continues it. */
    fun pause(id: String) {
        _problem.value = null
        held += id
        endRun(id)
        packs.filter { it.areaId == id && it.handle in started.value }.forEach { stop(it.handle) }
    }

    /**
     * Resumes every ready area once more, one style after the other. MapLibre then walks the pack
     * again from the copies of the style and its sources in its database and fetches only what is
     * missing there. When the map is online it keeps those copies current, and a map server such as
     * OpenFreeMap moves its tiles to new addresses with each release of its map data: the walk then
     * downloads the area at the new addresses (within the limits, as any download), and without it
     * the area would draw no map offline. Nothing changed: the walk ends without a request. An
     * update that stopped before it finished (the app was closed) goes on, unless the user paused
     * it since the app started. On a metered network a new release is not downloaded, as it may
     * take up to one download's 50 MB per area, unasked (Google Maps and OsmAnd update offline maps
     * on Wi-Fi): a walk that is still downloading after [QUIET_CHECK] is paused there, so the area
     * says "Update paused" instead of Ready, and the update goes on on Wi-Fi or with Resume. An
     * area marked "Update paused" without known new data ([PackMetadata.newData]: the app was
     * closed during the first seconds of a walk) is walked on mobile data too, which makes it Ready
     * again when nothing changed and stores the new data when there is some. A pack left
     * [PackMetadata.replacing] another by a process that ended is settled instead ([settle]).
     */
    internal fun checkForUpdates() {
        val leftovers =
            packs.filter { pack ->
                pack.metadata?.replacing == true && pack.areaId !in runs.value && replacements.none { it === pack.handle }
            }
        if (leftovers.isNotEmpty()) scope.launch { leftovers.forEach { settle(it) } }
        val settling = leftovers.map { it.areaId }.toSet()
        val wifi = unmetered()
        // An area being settled is checked the next time, so the two do not write its packs at once.
        areas(packs).filter { it.id !in runs.value && it.id !in held && it.id !in settling }.forEach { map ->
            val area = areaPacks.filter { it.areaId == map.id }
            val handles =
                when {
                    map.status == OfflineMapStatus.Ready -> area.map { it.handle }

                    (map.status as? OfflineMapStatus.Paused)?.update == true &&
                        (wifi || area.none { it.metadata?.newData == true }) -> unfinished(area)

                    else -> return@forEach
                }
            if (handles.any { it in started.value }) return@forEach
            if (map.status == OfflineMapStatus.Ready) checking.value += map.id to map.bytes
            runInTurn(map.id, handles, check = true, probe = !wifi)
        }
    }

    /** The packs of an area a resume walks: those not complete, and those with an update not finished. */
    private fun unfinished(area: List<StoredPack>): List<Any> =
        area.filter { it.progress.state != PackState.Complete || it.metadata?.updating == true }.map { it.handle }

    /**
     * Resumes [handles] one after the other, each once the one before is complete; a pack that stops
     * another way (paused, at the limit, deleted) ends the run. A [check] walks complete packs again
     * ([checkForUpdates]); one that is still downloading after [QUIET_CHECK] shows as updating. A
     * check marks every pack it will walk ([PackMetadata.updating]) before the first starts, and
     * unmarks each once its walk is complete, so a check that stops early, also with the app, leaves
     * the area marked as not up to date. A walk still downloading after [QUIET_CHECK] also marks
     * that the pack has new data ([PackMetadata.newData]). A [probe] pauses such a walk and ends
     * there. A check that walked every pack and stored new map data ends by replacing the packs of
     * the area ([replacePacks]).
     */
    private fun runInTurn(
        area: String,
        handles: List<Any>,
        check: Boolean = false,
        probe: Boolean = false,
    ) {
        endRun(area)
        // Started once it is in [runs], so a run that ends at once also leaves it.
        val job =
            scope.launch(start = CoroutineStart.LAZY) {
                try {
                    // An update an earlier run left open (the app was closed, the user paused it)
                    // may have stored its tiles already, so this walk fetching nothing proves no
                    // update: it ends with new map data all the same.
                    val leftOpen = handles.filter { handle -> packs.any { it.handle === handle && it.metadata?.updating == true } }
                    if (check) handles.forEach { mark(it) { m -> m.copy(updating = true) } }
                    // What each finished walk stored, as the store may list it a moment later.
                    val finished = mutableMapOf<Any, PackMetadata>()
                    var newData = false

                    suspend fun finish(
                        handle: Any,
                        fetchedBefore: Long,
                    ) {
                        val before = packs.firstOrNull { it.handle === handle }?.metadata
                        val marked =
                            mark(handle) {
                                done(it, fetches.value != fetchedBefore || handle in leftOpen, clock.now().toEpochMilliseconds())
                            }
                                ?: return
                        finished[handle] = marked
                        if (marked.newDataAt != before?.newDataAt) newData = true
                    }
                    for (handle in handles) {
                        val fetchedBefore = fetches.value
                        if (check) walking += handle
                        halted -= handle
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
                                finish(handle, fetchedBefore)
                                rest(handle)
                                continue
                            }
                            // Paused first, so the mark's write costs no download on mobile data.
                            if (probe) stop(handle)
                            mark(handle) { it.copy(newData = true) }
                            if (probe) return@launch
                            updating.value += area
                        }
                        // [watch] takes the pack out once it is complete or paused. MapLibre may
                        // report an update stopped at the limit complete, as it counts the old tiles.
                        started.first { handle !in it }
                        if (handle in halted || stateOf(handle) != PackState.Complete) return@launch
                        if (check) finish(handle, fetchedBefore)
                        rest(handle)
                    }
                    if (check && newData) replacePacks(area, finished)
                } finally {
                    walking.removeAll(handles.toSet())
                    checking.value -= area
                    updating.value -= area
                    if (runs.value[area] === coroutineContext.job) runs.value -= area
                }
            }
        runs.value += area to job
        job.start()
    }

    private fun endRun(area: String) {
        val run = runs.value[area]
        runs.value -= area
        run?.cancel()
    }

    /**
     * Sets a complete pack inactive. MapLibre keeps a pack active after it completes and ignores a
     * resume of an active pack, so without this the next check would not walk it.
     */
    private fun rest(handle: Any) {
        runCatching { store.pause(handle) }
    }

    /**
     * Stores what a check knows of [handle] ([PackMetadata.updating], [PackMetadata.newData]), if it
     * is not stored so already, and returns the pack's metadata from now on; null when it has none or
     * the store refused it.
     */
    private suspend fun mark(
        handle: Any,
        change: (PackMetadata) -> PackMetadata,
    ): PackMetadata? {
        val metadata = packs.firstOrNull { it.handle === handle }?.metadata ?: return null
        val marked = change(metadata)
        if (marked == metadata) return marked
        return try {
            store.setMetadata(handle, marked)
            marked
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Unmarked, the area shows as the store reports it, as before the mark existed.
            null
        }
    }

    /**
     * Replaces each pack of [area] by a new pack of the same style and bounds. MapLibre keeps every
     * tile a pack ever stored linked to it, so after an update the release before's tiles (up to one
     * download's 50 MB per release) would stay until the area is deleted. The new pack links the
     * tiles the update left in the database, which are all there, without asking for them again
     * (only for a resource the server had none of, which MapLibre does not store); deleting the old
     * one then leaves the old release's tiles to MapLibre's map cache, which drops the least used
     * once it is over its size limit (MapLibre counts the freed space at the next start), and MapLibre
     * compacts its database after each delete. [finished] has the metadata a check just stored, which
     * the store may not list yet. A replacement that fails keeps the old pack; the next update tries
     * again.
     */
    private suspend fun replacePacks(
        area: String,
        finished: Map<Any, PackMetadata>,
    ) {
        for (old in areaPacks.filter { it.areaId == area }) {
            val metadata = finished[old.handle] ?: old.metadata ?: continue
            if (!replace(area, old.handle, metadata.copy(replacing = false))) return
        }
    }

    /** Replaces the pack [old] of [area], whose metadata is [metadata]; false when it is kept. */
    private suspend fun replace(
        area: String,
        old: Any,
        metadata: PackMetadata,
    ): Boolean {
        var new: Any? = null
        // Once the old pack's delete has begun, it may be gone even when the call failed: the new
        // pack then stays, still marked, for the next start's [settle], which sees what is stored.
        var oldGoing = false
        try {
            // Not cut off: MapLibre would create the pack all the same, with no one to delete it.
            val handle = withContext(NonCancellable) { store.createLike(old, metadata.copy(replacing = true)) }
            new = handle
            replacements += handle
            createdFor.getOrPut(area) { mutableListOf() } += handle
            started.value += handle
            store.resume(handle)
            // A pack reports no tiles before its walk has begun.
            withTimeoutOrNull(REPLACE_TIMEOUT) {
                listed.first { list ->
                    list.any { it.handle === handle && it.progress.state == PackState.Complete && it.progress.completedTiles > 0 }
                }
            } ?: return false
            rest(handle)
            oldGoing = true
            withContext(NonCancellable) {
                store.pause(old)
                store.delete(old)
            }
            createdFor[area]?.removeAll { it === old }
            // Unmarked, the pack still takes the old one's place: [visible] and the next check's [settle].
            mark(handle) { metadata }
            return true
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return false
        } finally {
            new?.let { handle ->
                started.value -= handle
                stopping.value -= handle
                halted -= handle
                // A deleted area's packs go with it: [delete] waits for this run and deletes them.
                if (area !in deleted) {
                    createdFor[area]?.removeAll { it === handle }
                    if (!oldGoing) {
                        withContext(NonCancellable) {
                            runCatching {
                                store.pause(handle)
                                store.delete(handle)
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Settles a pack [PackMetadata.replacing] another that a process before this one left (or one
     * of this process, after the next start): when the pack it was to replace is still there, it
     * goes (that one has every tile it has); otherwise the old one was deleted already, and this one
     * takes its place.
     */
    private suspend fun settle(pack: StoredPack) {
        // One of this process stays as it is: the store's list of its old pack may be out of date.
        if (replacements.any { it === pack.handle }) return
        val metadata = pack.metadata ?: return
        val replaced = packs.any { it !== pack && it.areaId == pack.areaId && it.style == pack.style && it.metadata?.replacing != true }
        runCatching {
            if (replaced) {
                store.pause(pack.handle)
                store.delete(pack.handle)
            } else {
                store.setMetadata(pack.handle, metadata.copy(replacing = false))
            }
        }
    }

    private fun stateOf(handle: Any): PackState? = packs.firstOrNull { it.handle === handle }?.progress?.state

    fun delete(id: String) {
        val run = runs.value[id]
        endRun(id)

        fun packsOfArea(): List<Any> {
            val listed = packs.filter { it.areaId == id }.map { it.handle }
            return listed + createdFor.remove(id).orEmpty().filter { handle -> listed.none { it === handle } }
        }
        val now = if (run == null) packsOfArea() else emptyList()
        deleted += id
        _problem.value = null
        scope.launch {
            // A run (a replacement in it) may create and delete packs until it has ended.
            val area = if (run == null) now else run.join().let { packsOfArea() }
            area.forEach { handle ->
                try {
                    store.pause(handle)
                    store.delete(handle)
                } catch (e: Exception) {
                    _problem.value = "Couldn't delete the offline map: ${e.message ?: "the offline storage failed"}"
                    // The pack stays: it leaves [started] as a paused one does, once it no longer
                    // downloads, so the throttle and the place reads ([fetchingChanges]) end with it.
                    if (handle !in started.value) return@forEach
                    stopping.value += handle
                    if (stateOf(handle) == PackState.Downloading) return@forEach
                }
                // Only now: until the pack is gone or paused, its requests may still be on their way.
                started.value -= handle
                stopping.value -= handle
            }
        }
    }

    /**
     * Pauses a pack. It stays in [started] (and requests stay throttled) until the store reports it
     * is no longer downloading, as MapLibre pauses after the call returns.
     */
    private fun stop(handle: Any) {
        halted += handle
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

/**
 * The first check for new map data: soon after the map has loaded its style (and so refreshed its
 * copies), so a phone that goes offline right after the start rarely misses a new release.
 */
internal val FIRST_CHECK = 20.seconds

/** OpenFreeMap lets a map keep its sources for an hour; checking twice as often finds a change in time. */
internal val CHECK_INTERVAL = 30.minutes

/** Long enough for MapLibre to walk a complete pack in its database and report it. */
internal val QUIET_CHECK = 10.seconds

/**
 * How long a pack that replaces another may take to link its tiles in the database; a dense area's
 * 3,000 tiles take seconds.
 */
internal val REPLACE_TIMEOUT = 2.minutes

/**
 * A check's walk of a pack finished: the pack is up to date. When the walk [fetched] tiles (or
 * finished an update an earlier run left open), or an earlier walk of this update found new data,
 * the pack has new map data from now on.
 */
private fun done(
    metadata: PackMetadata,
    fetched: Boolean,
    now: Long,
): PackMetadata =
    metadata.copy(
        updating = false,
        newData = false,
        newDataAt = if (fetched || metadata.newData) maxOf(now, metadata.newDataAt + 1) else metadata.newDataAt,
    )

private val StoredPack.areaId: String get() = metadata?.area ?: "pack-${handle.hashCode()}"

/**
 * The packs that make up the areas: a pack [PackMetadata.replacing] another of its style in its
 * area is left out until that one is gone.
 */
internal fun visible(packs: List<StoredPack>): List<StoredPack> =
    packs.filter { pack ->
        pack.metadata?.replacing != true ||
            packs.none { it !== pack && it.areaId == pack.areaId && it.style == pack.style && it.metadata?.replacing != true }
    }

/**
 * The [visible] packs grouped by area, newest first. An area in [checking] shows as it was (Ready, its size
 * before the check) unless it is in [updating]. Otherwise an area with a pack whose update has not
 * finished is never Ready ([PackMetadata.updating]). The percent is each style's share: the styles
 * download one after the other, and one not started yet reports nothing to do.
 */
internal fun areas(
    packs: List<StoredPack>,
    checking: Map<String, Long> = emptyMap(),
    updating: Set<String> = emptySet(),
): List<OfflineMap> =
    visible(packs)
        .groupBy { it.areaId }
        .map { (id, area) ->
            val first = area.first().metadata
            val progress = area.map { it.progress }
            val percent =
                progress
                    .sumOf {
                        when {
                            it.state == PackState.Complete -> 100L
                            it.requiredResources > 0 -> 100 * it.completedResources / it.requiredResources
                            else -> 0L
                        }
                    }.div(progress.size)
                    .toInt()
                    .coerceIn(0, 100)
            val before = checking[id]
            val unfinishedUpdate = area.any { it.metadata?.updating == true }
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

                        progress.any { it.state == PackState.Downloading } -> {
                            OfflineMapStatus.Downloading(
                                percent,
                                progress.firstNotNullOfOrNull { it.error },
                                update = id in updating || unfinishedUpdate,
                            )
                        }

                        unfinishedUpdate -> {
                            OfflineMapStatus.Paused(percent, update = true)
                        }

                        progress.all { it.state == PackState.Complete } -> {
                            OfflineMapStatus.Ready
                        }

                        else -> {
                            OfflineMapStatus.Paused(percent)
                        }
                    },
                newDataAt = area.maxOf { it.metadata?.newDataAt ?: 0 },
                // The styles of an area share its tiles: they are stored once. A check counts again from 0.
                bytes =
                    maxOf(
                        before ?: 0,
                        progress.maxOf { it.completedTileBytes } + progress.sumOf { it.completedBytes - it.completedTileBytes },
                    ),
                bounds = first?.let { AreaBounds(it.west, it.south, it.east, it.north) },
            )
        }.sortedByDescending { it.createdAt }

/** "Map around 47.38° N, 8.54° E": the name of an offline map whose area has no town the map labels. */
internal fun areaName(bounds: AreaBounds): String {
    fun degrees(value: Double): String {
        val hundredths = (abs(value) * 100).roundToInt()
        return "${hundredths / 100}.${(hundredths % 100).toString().padStart(2, '0')}°"
    }
    val lat = bounds.centerLat
    val lon = bounds.centerLon
    return "Map around ${degrees(lat)} ${if (lat < 0) "S" else "N"}, ${degrees(lon)} ${if (lon < 0) "W" else "E"}"
}
