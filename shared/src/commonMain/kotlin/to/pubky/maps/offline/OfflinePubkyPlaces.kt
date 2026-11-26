package to.pubky.maps.offline

import app.cash.sqldelight.db.SqlDriver
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
import kotlinx.coroutines.withContext
import to.pubky.maps.map.MapPlacesLayer
import to.pubky.maps.offline.pubky.db.OfflinePubkyDatabase
import to.pubky.maps.place.GeoBox
import to.pubky.maps.place.MapPlace
import to.pubky.maps.place.PlaceData
import to.pubky.maps.search.SourceAnswer
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

/** The Pubky places kept for the offline areas, as the map's Pubky places layer reads them. Calls block on disk. */
interface KeptPubkyPlaces {
    /** The kept places in [box], at most [limit]; null when no kept area overlaps [box]. */
    fun placesIn(
        box: GeoBox,
        limit: Long,
    ): List<MapPlace>?
}

/** Where the Pubky places of the offline areas are kept. Calls block on disk. */
interface OfflinePubkyStore : KeptPubkyPlaces {
    /** The areas kept here, with when their places were fetched (epoch milliseconds). */
    fun areas(): Map<String, Long>

    /** Keeps [places], fetched for [box] at [fetchedAt], as those of [area], in place of those it had. */
    fun replace(
        area: String,
        box: GeoBox,
        fetchedAt: Long,
        places: List<MapPlace>,
    )

    fun delete(area: String)
}

/**
 * Keeps the Pubky places of the offline areas ([maps]) in [store], so the map shows them without a
 * connection: an area's places are fetched from Pinakes ([data]) once it is listed (downloading or
 * not: the map tiles do not need to be there), fetched again after [refresh] on a network Android does
 * not count as metered ([unmetered]), and deleted with the area. The box asked for is the area widened
 * to the layer's grid ([MapPlacesLayer.GRID_DEGREES]), so it says no more of where the user looks
 * than the layer's own requests. A fetch takes at most [MAX_PAGES] pages, one every [pageGap] (an
 * area of a city stays well within Pinakes' 120 requests a minute next to the layer and search).
 * When Pinakes cannot be reached the areas are tried again after [retry], with the places kept so far.
 */
class OfflinePubkyPlaces(
    private val maps: StateFlow<OfflineMapsState>,
    private val data: PlaceData,
    private val store: OfflinePubkyStore,
    private val scope: CoroutineScope,
    private val unmetered: () -> Boolean,
    private val clock: Clock = Clock.System,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val refresh: Duration = REFRESH,
    private val retry: Duration = RETRY,
    private val pageGap: Duration = PAGE_GAP,
) {
    /** Counts the ticks of [retry], so the same list of areas is looked at again. */
    private val ticks = MutableStateFlow(0)

    fun start() {
        scope.launch {
            val lists =
                maps
                    .filterIsInstance<OfflineMapsState.Ready>()
                    .map { ready -> ready.maps.mapNotNull { map -> map.bounds?.let { map.id to it } } }
                    .distinctUntilChanged()
            combine(lists, ticks) { list, _ -> list }
                .conflate()
                .collect { sync(it) }
        }
        scope.launch {
            while (true) {
                delay(retry)
                ticks.update { it + 1 }
            }
        }
    }

    private suspend fun sync(areas: List<Pair<String, AreaBounds>>) {
        val kept = onDisk { store.areas() } ?: return
        val listed = areas.map { it.first }.toSet()
        (kept.keys - listed).forEach { area -> onDisk { store.delete(area) } }
        for ((area, bounds) in areas) {
            val now = clock.now().toEpochMilliseconds()
            val fetchedAt = kept[area]
            val due = fetchedAt == null || ((now - fetchedAt).milliseconds >= refresh && unmetered())
            if (!due) continue
            val box = GeoBox(bounds.west, bounds.south, bounds.east, bounds.north).snappedOutward(MapPlacesLayer.GRID_DEGREES)
            // Unreachable: the other areas would fail too; all are tried again on the next tick.
            val places = fetch(box) ?: return
            // An area deleted during the fetch is in the next list, which deletes it again.
            onDisk { store.replace(area, box, now, places) }
        }
    }

    /** The places in [box], at most [MAX_PAGES] pages of them; null when Pinakes cannot be reached. */
    private suspend fun fetch(box: GeoBox): List<MapPlace>? {
        val places = mutableListOf<MapPlace>()
        var cursor: String? = null
        var pages = 0
        do {
            if (pages > 0) delay(pageGap)
            when (val answer = data.placesIn(box, cursor)) {
                is SourceAnswer.Answered -> {
                    places += answer.value.places
                    cursor = answer.value.cursor
                }

                SourceAnswer.Unreachable -> {
                    return null
                }
            }
            pages++
        } while (cursor != null && pages < MAX_PAGES)
        return places.distinctBy { it.uri }
    }

    /** Runs [block] on [io]; null when the disk failed (the next tick tries again). */
    private suspend fun <T> onDisk(block: () -> T): T? =
        withContext(io) {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
        }

    companion object {
        /** Pubky places change with what people write; a day keeps them close without much traffic. */
        val REFRESH = 1.days

        val RETRY = 15.minutes

        val PAGE_GAP = 500.milliseconds

        /** Up to 10,000 places per area (pages of 500). */
        const val MAX_PAGES = 20
    }
}

/** [OfflinePubkyStore] in SQLite. */
class SqlOfflinePubkyStore(
    driver: SqlDriver,
) : OfflinePubkyStore {
    private val queries = OfflinePubkyDatabase(driver).offlinePubkyQueries

    override fun areas(): Map<String, Long> = queries.areas().executeAsList().associate { it.id to it.fetched_at }

    override fun replace(
        area: String,
        box: GeoBox,
        fetchedAt: Long,
        places: List<MapPlace>,
    ) {
        queries.transaction {
            queries.deletePlaces(area)
            queries.putArea(area, fetchedAt, box.west, box.south, box.east, box.north)
            places.forEach { queries.putPlace(area, it.uri, it.name, it.lat, it.lon, it.type, it.category) }
        }
    }

    override fun delete(area: String) {
        queries.transaction {
            queries.deletePlaces(area)
            queries.deleteArea(area)
        }
    }

    override fun placesIn(
        box: GeoBox,
        limit: Long,
    ): List<MapPlace>? {
        if (queries.overlapping(east = box.east, west = box.west, north = box.north, south = box.south).executeAsOne() == 0L) return null
        return queries
            .placesIn(
                south = box.south,
                north = box.north,
                west = box.west,
                east = box.east,
                limit = limit,
            ) { uri, name, lat, lon, type, category ->
                MapPlace(uri, name, lat, lon, type, category)
            }.executeAsList()
    }
}
