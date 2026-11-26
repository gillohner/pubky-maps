package to.pubky.maps.offline

import app.cash.sqldelight.db.SqlDriver
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.UserAgent
import io.ktor.client.request.forms.submitForm
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import io.ktor.http.parameters
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
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import to.pubky.maps.links.OsmId
import to.pubky.maps.links.OsmType
import to.pubky.maps.offline.db.OfflinePlacesDatabase
import to.pubky.maps.place.KeptDetails
import to.pubky.maps.place.KeptPlaceDetails
import to.pubky.maps.place.OsmDetails
import to.pubky.maps.search.SearchHttpException
import to.pubky.maps.search.SearchJson
import to.pubky.maps.search.ServerFallback
import to.pubky.maps.search.SourceAnswer
import to.pubky.maps.search.searchHttpEngine
import to.pubky.maps.search.searchUserAgent
import kotlin.math.abs
import kotlin.math.roundToLong
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** The OpenStreetMap details of the element [osm]: its tags of [OsmDetails.KEYS] that have a value. */
data class ElementDetails(
    val osm: OsmId,
    val facts: Map<String, String>,
)

/** Where the details of an area's places come from. */
fun interface PlaceDetailsSource {
    /** The details of the named elements in [bounds] that have any; unreachable when no server gave them whole. */
    suspend fun detailsIn(bounds: AreaBounds): SourceAnswer<List<ElementDetails>>
}

/** Where the details of the offline areas are kept. Calls block on disk. */
interface OfflineDetailsStore : KeptPlaceDetails {
    /** The areas kept here, each with when its details were fetched (epoch ms). */
    fun areas(): Map<String, Long>

    /** Keeps [details], fetched at [fetchedAt], as those of [area], in place of those it had. */
    fun replace(
        area: String,
        fetchedAt: Long,
        details: List<ElementDetails>,
    )

    fun delete(area: String)
}

/**
 * Keeps the OpenStreetMap details (opening hours, phone, website, ...) of the places in the offline
 * areas ([maps]) in [store], so a place page shows them without a connection when it has no Pinakes
 * card. An area's details are fetched from [source] once it is listed (the map tiles do not need to
 * be there), then again after [refresh] on a network Android does not count as metered
 * ([unmetered]), and deleted with the area; an area deleted while it is asked about is neither asked
 * about further nor kept. One request goes out at a time, at least [requestGap] after the one before
 * ended (also across syncs), as public Overpass servers ask. An area that fails is tried again after
 * [retry], then after twice as long at each failure up to [MAX_BACKOFF], so an area no server can
 * answer does not load the shared servers every half hour; it goes after the others, and a sync
 * stops at the second failure (no connection fails every area alike).
 */
class OfflinePlaceDetails(
    private val maps: StateFlow<OfflineMapsState>,
    private val source: PlaceDetailsSource,
    private val store: OfflineDetailsStore,
    private val scope: CoroutineScope,
    private val unmetered: () -> Boolean,
    private val clock: Clock = Clock.System,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val refresh: Duration = REFRESH,
    private val retry: Duration = RETRY,
    private val requestGap: Duration = REQUEST_GAP,
) {
    /** Counts the ticks of [retry], so the same list of areas is looked at again. */
    private val ticks = MutableStateFlow(0)

    /**
     * Areas whose last fetch in this process failed: how many times in a row, the count of failures
     * when it last did (they go after the others, the one that failed longest ago first), and when
     * (epoch ms).
     */
    private class Failure(
        val times: Int,
        val order: Int,
        val at: Long,
    )

    private val failing = mutableMapOf<String, Failure>()
    private var failureCount = 0

    /** When the last request ended (epoch ms); the next waits [requestGap] after it. */
    private var lastRequestAt: Long? = null

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
        failing.keys.retainAll(listed)
        var failures = 0
        for ((area, bounds) in areas.sortedBy { failing[it.first]?.order ?: -1 }) {
            val fetchedAt = kept[area]
            val now = clock.now().toEpochMilliseconds()
            val due = fetchedAt == null || ((now - fetchedAt) >= refresh.inWholeMilliseconds && unmetered())
            if (!due || backingOff(area, now)) continue
            lastRequestAt?.let { last -> delay((last + requestGap.inWholeMilliseconds - clock.now().toEpochMilliseconds()).milliseconds) }
            if (!stillListed(area)) continue
            val answer = source.detailsIn(bounds)
            lastRequestAt = clock.now().toEpochMilliseconds()
            when (answer) {
                is SourceAnswer.Answered -> {
                    if (stillListed(area)) onDisk { store.replace(area, now, answer.value) }
                    failing -= area
                }

                SourceAnswer.Unreachable -> {
                    failing[area] = Failure(times = (failing[area]?.times ?: 0) + 1, order = failureCount++, at = lastRequestAt ?: now)
                    if (++failures >= 2) return
                }
            }
        }
    }

    /** [area] failed less than its backoff ago: [retry] after one failure, twice as long after each next one, at most [MAX_BACKOFF]. */
    private fun backingOff(
        area: String,
        now: Long,
    ): Boolean {
        val failure = failing[area] ?: return false
        val backoff = (retry * (1 shl (failure.times - 1).coerceIn(0, 20)).toDouble()).coerceAtMost(MAX_BACKOFF)
        // A tick may come a moment early: a minute's slack keeps the first retry at the first tick.
        return now - failure.at < (backoff - 1.minutes).inWholeMilliseconds
    }

    /** [area] is in the offline maps' list now (the user did not delete it since the sync began). */
    private fun stillListed(area: String): Boolean = (maps.value as? OfflineMapsState.Ready)?.maps?.any { it.id == area } == true

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
        /** Opening hours and phone numbers change slowly; a week keeps them close with one request an area. */
        val REFRESH = 7.days

        val RETRY = 30.minutes

        /** The longest wait before an area that keeps failing is asked about again. */
        val MAX_BACKOFF = 1.days

        /** Public Overpass servers are shared by everyone; areas are asked about one at a time, slowly. */
        val REQUEST_GAP = 10.seconds
    }
}

/**
 * The details of an area's places from Overpass, through its server list ([servers], read at every
 * request, so a settings change applies to the next one): one query for the named elements in the
 * area with any of [OsmDetails.KEYS], as tab-separated lines of only those tags (a few MB for the
 * largest area one download may take, where JSON of all their tags is several times more). An answer
 * that does not end with the query's end line was cut off (a timeout or memory limit on the server)
 * and moves on to the next server, as does an error status; a 429 skips the server for a while
 * ([ServerFallback]). An empty answer moves on too, as a server may hold one region only
 * (dresel.systems' default endpoint has Europe): an area with no details is taken as such only when
 * every server of the list answered it empty.
 */
class OverpassPlaceDetails(
    private val client: HttpClient,
    private val servers: () -> List<String>,
    clock: Clock = Clock.System,
) : PlaceDetailsSource {
    private val fallback = ServerFallback(clock)

    override suspend fun detailsIn(bounds: AreaBounds): SourceAnswer<List<ElementDetails>> {
        val servers = servers()
        var empty = 0
        val answer =
            fallback.first(servers) { server ->
                val response = client.submitForm(server, parameters { append("data", overpassDetailsQuery(bounds)) })
                if (!response.status.isSuccess()) {
                    val retryAfter =
                        response.headers[HttpHeaders.RetryAfter]
                            ?.trim()
                            ?.toLongOrNull()
                            ?.seconds
                    throw SearchHttpException(response.status.value, retryAfter)
                }
                val text = response.bodyAsText()
                val details = withContext(Dispatchers.Default) { parseOverpassDetails(text) } ?: throw OverpassCutOff()
                if (details.isEmpty()) {
                    empty++
                    throw OverpassEmpty()
                }
                details
            }
        // Every server answered, none with an element: the area has no details.
        if (answer == SourceAnswer.Unreachable && empty == servers.size) return SourceAnswer.Answered(emptyList(), servers.last())
        return answer
    }
}

/** An Overpass answer that ended before the query's end line. */
private class OverpassCutOff : Exception("The Overpass answer was cut off")

/** A whole Overpass answer with no element: the next server may cover the area. */
private class OverpassEmpty : Exception("The Overpass answer has no element")

/** The first cell of the line the query ends with: a complete answer has it last. */
private const val END_LINE = "end"

/** The server-side limit of one query; the client waits a little longer for the answer to arrive. */
private const val QUERY_TIMEOUT_S = 180

/**
 * The Overpass query for the details of the named elements in [bounds]: nodes, ways and relations
 * with a name and one of [OsmDetails.KEYS], as CSV of type, id and those tags, then a line [END_LINE].
 */
internal fun overpassDetailsQuery(bounds: AreaBounds): String {
    val columns = OsmDetails.KEYS.joinToString(",") { "\"$it\"" }
    val keys = OsmDetails.KEYS.joinToString("|")
    val box = listOf(bounds.south, bounds.west, bounds.north, bounds.east).joinToString(",", transform = ::degrees)
    return "[out:csv(::type,::id,$columns;false)][timeout:$QUERY_TIMEOUT_S][bbox:$box];" +
        "nwr[name][~\"^($keys)$\"~\".\"];out qt;make $END_LINE;out;"
}

/** [value] with six decimals and never in exponent notation ("8.000010", "-0.000100"). */
private fun degrees(value: Double): String {
    val micro = (abs(value) * 1_000_000).roundToLong()
    val sign = if (value < 0 && micro != 0L) "-" else ""
    return "$sign${micro / 1_000_000}.${(micro % 1_000_000).toString().padStart(6, '0')}"
}

/**
 * The elements of an answer to [overpassDetailsQuery], each with the tags it has a value for; null
 * when the answer does not end with the end line (cut off). A record with another number of cells
 * than the query's columns is left out.
 */
internal fun parseOverpassDetails(text: String): List<ElementDetails>? {
    val columns = 2 + OsmDetails.KEYS.size
    val details = ArrayList<ElementDetails>()
    var complete = false
    for (cells in overpassRecords(text)) {
        if (cells.size == 1 && cells[0].isBlank()) continue
        complete = cells.size == columns && cells[0] == END_LINE
        if (complete || cells.size != columns) continue
        val type =
            when (cells[0]) {
                "node" -> OsmType.Node
                "way" -> OsmType.Way
                "relation" -> OsmType.Relation
                else -> continue
            }
        val id = cells[1].toLongOrNull() ?: continue
        val facts =
            OsmDetails.KEYS
                .zip(cells.subList(2, columns))
                .mapNotNull { (key, cell) -> cell.trim().takeIf { it.isNotEmpty() }?.let { key to it } }
                .toMap()
        if (facts.isNotEmpty()) details += ElementDetails(OsmId(type, id), facts)
    }
    return details.takeIf { complete }
}

/**
 * The records of Overpass' CSV output with tabs between cells. Overpass puts a value with a comma,
 * a quote or a line break in it in quotes, doubling the quotes inside, so a quoted cell can go on
 * over lines.
 */
internal fun overpassRecords(text: String): Sequence<List<String>> =
    sequence {
        var cells = ArrayList<String>()
        val cell = StringBuilder()
        var quoted = false
        // Nothing of the cell read yet: a quote here opens a quoted cell.
        var fresh = true
        var i = 0
        while (i < text.length) {
            val c = text[i++]
            if (quoted) {
                when {
                    c != '"' -> {
                        cell.append(c)
                    }

                    i < text.length && text[i] == '"' -> {
                        cell.append('"')
                        i++
                    }

                    else -> {
                        quoted = false
                    }
                }
                continue
            }
            when {
                c == '"' && fresh -> {
                    quoted = true
                    fresh = false
                }

                c == '\t' -> {
                    cells += cell.toString()
                    cell.clear()
                    fresh = true
                }

                c == '\n' -> {
                    cells += cell.toString()
                    cell.clear()
                    fresh = true
                    yield(cells)
                    cells = ArrayList()
                }

                c == '\r' -> {}

                else -> {
                    cell.append(c)
                    fresh = false
                }
            }
        }
        if (cells.isNotEmpty() || cell.isNotEmpty()) {
            cells += cell.toString()
            yield(cells)
        }
    }

/**
 * The HTTP client for Overpass: the platform engine and the app's User-Agent (`PubkyMaps/<version>`,
 * as the public Overpass servers ask apps to identify themselves), with room for a query of up to
 * [QUERY_TIMEOUT_S] seconds and its answer of a few MB.
 */
fun overpassHttpClient(appVersion: String): HttpClient =
    HttpClient(searchHttpEngine()) {
        expectSuccess = false
        install(UserAgent) { agent = searchUserAgent(appVersion) }
        install(HttpTimeout) {
            connectTimeoutMillis = 15_000
            // Overpass sends nothing while the query runs; the Android engine's own 100 s read timeout would cut it.
            socketTimeoutMillis = (QUERY_TIMEOUT_S + 60) * 1_000L
            requestTimeoutMillis = (QUERY_TIMEOUT_S + 60) * 1_000L
        }
    }

private val factsSerializer = MapSerializer(String.serializer(), String.serializer())

/** [OfflineDetailsStore] in the offline places' SQLite ([OfflinePlacesDatabase]). */
class SqlOfflineDetails(
    driver: SqlDriver,
) : OfflineDetailsStore {
    private val queries = OfflinePlacesDatabase(driver).offlinePlacesQueries

    override fun areas(): Map<String, Long> = queries.detailsAreas().executeAsList().associate { it.id to it.fetched_at }

    override fun replace(
        area: String,
        fetchedAt: Long,
        details: List<ElementDetails>,
    ) = queries.transaction {
        queries.deleteDetails(area)
        details.forEach { queries.insertDetails(area, it.osm.type.name, it.osm.id, SearchJson.encodeToString(factsSerializer, it.facts)) }
        queries.putDetailsArea(area, fetchedAt)
    }

    override fun delete(area: String) =
        queries.transaction {
            queries.deleteDetails(area)
            queries.deleteDetailsArea(area)
        }

    override fun detailsOf(osm: OsmId): KeptDetails? {
        val row = queries.detailsOf(osm.id, osm.type.name).executeAsOneOrNull() ?: return null
        val facts =
            try {
                SearchJson.decodeFromString(factsSerializer, row.facts)
            } catch (e: SerializationException) {
                // Written by another app version in a shape this one cannot read: as if not kept.
                return null
            } catch (e: IllegalArgumentException) {
                return null
            }
        return KeptDetails(facts, fetchedAt = row.fetched_at)
    }
}
