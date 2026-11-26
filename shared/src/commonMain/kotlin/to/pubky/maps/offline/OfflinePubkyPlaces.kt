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
import kotlinx.serialization.SerializationException
import to.pubky.maps.map.MapPlacesLayer
import to.pubky.maps.offline.pubky.db.OfflinePubkyDatabase
import to.pubky.maps.place.GeoBox
import to.pubky.maps.place.KeptPlaceSocial
import to.pubky.maps.place.KeptSocial
import to.pubky.maps.place.MapPlace
import to.pubky.maps.place.PlaceData
import to.pubky.maps.place.PlaceSocial
import to.pubky.maps.place.ResourceView
import to.pubky.maps.place.mergeResourceViews
import to.pubky.maps.search.SearchJson
import to.pubky.maps.search.SourceAnswer
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

/**
 * The Pubky data kept for the offline areas, as the map's Pubky places layer (places) and the place
 * page (their tags and counts) read it. Calls block on disk.
 */
interface KeptPubkyPlaces : KeptPlaceSocial {
    /** The kept places in [box], at most [limit]; null when no kept area overlaps [box]. */
    fun placesIn(
        box: GeoBox,
        limit: Long,
    ): List<MapPlace>?
}

/**
 * A kept area: when its places were fetched (epoch milliseconds), and when Nexus last answered for
 * them (null when it has not yet; before [fetchedAt] when it did not answer for the places now kept).
 */
data class KeptArea(
    val fetchedAt: Long,
    val socialAt: Long?,
) {
    /** Nexus did not answer for the places now kept. */
    val socialMissing: Boolean get() = socialAt == null || socialAt < fetchedAt
}

/** Where the Pubky places of the offline areas are kept. Calls block on disk. */
interface OfflinePubkyStore : KeptPubkyPlaces {
    /** The areas kept here. */
    fun areas(): Map<String, KeptArea>

    /**
     * Keeps [places], fetched for [box] at [fetchedAt], as those of [area], in place of those it had,
     * with the [links] Nexus is asked about for them (each link to the uri of its place); the area's
     * tags stay until [replaceSocial].
     */
    fun replace(
        area: String,
        box: GeoBox,
        fetchedAt: Long,
        places: List<MapPlace>,
        links: Map<String, String>,
    )

    /** The links of [area]'s places, each to the uri of its place, in the order they were kept. */
    fun links(area: String): Map<String, String>

    /** Keeps [social], the tags and counts Nexus gave at [fetchedAt] for [area]'s places (by place uri), in place of those it had. */
    fun replaceSocial(
        area: String,
        fetchedAt: Long,
        social: Map<String, PlaceSocial>,
    )

    fun delete(area: String)
}

/**
 * Keeps the Pubky places of the offline areas ([maps]) and their tags and counts in [store], so the
 * map shows the places and their pages the tags without a connection. An area's places are fetched
 * from Pinakes ([data]) once it is listed (downloading or not: the map tiles do not need to be there),
 * then Nexus' views of them (each place's link and the links people referenced for it, 100 a request,
 * at most [MAX_SOCIAL_REQUESTS] requests, asked with no viewer, so nothing in them is the user's);
 * both are fetched again after [refresh] on a network Android does not count as metered
 * ([unmetered]), and deleted with the area. When Nexus does not answer, the places are kept and the
 * tags the area had stay with their own date; only Nexus is asked again at the next try, on any
 * network, for the kept places. The box asked for is the area widened to the layer's grid
 * ([MapPlacesLayer.GRID_DEGREES]); unlike the layer's requests, which go out only while the user looks
 * at a place, these tell Pinakes which areas are downloaded (and Nexus which places are in them),
 * once at the download and then once a day, whether or not the user looks there. A fetch takes at
 * most [MAX_PAGES] pages; one request goes out every [requestGap] at most, to Pinakes or Nexus, also
 * between areas, so many areas stay far below Pinakes' 120 requests a minute next to the layer and
 * search (a 429 puts the server on hold for them too). An area that fails is tried again after
 * [retry], after the others on later tries, the one that failed longest ago first; a sync stops at
 * Pinakes' second failure and asks Nexus no more after its second, but still fetches the places of
 * the other areas (no connection fails every area alike).
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
    private val requestGap: Duration = REQUEST_GAP,
) {
    /** Counts the ticks of [retry], so the same list of areas is looked at again. */
    private val ticks = MutableStateFlow(0)

    /** Areas whose last fetch in this process failed, with the count of failures when they did: they go after the others. */
    private val failing = mutableMapOf<String, Int>()
    private var failureCount = 0

    /** Requests of the running sync; each after the first waits [requestGap]. */
    private var requests = 0

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
        requests = 0
        var placeFailures = 0
        var socialFailures = 0
        for ((area, bounds) in areas.sortedBy { failing[it.first] ?: -1 }) {
            val now = clock.now().toEpochMilliseconds()
            val keptArea = kept[area]
            val placesDue = keptArea == null || ((now - keptArea.fetchedAt).milliseconds >= refresh && unmetered())
            // After Nexus' second failure in this sync only the places are fetched: the tags wait for the next try.
            val socialDue = socialFailures < 2
            if (!placesDue && !(keptArea.socialMissing && socialDue)) continue
            val links =
                if (placesDue) {
                    val box = GeoBox(bounds.west, bounds.south, bounds.east, bounds.north).snappedOutward(MapPlacesLayer.GRID_DEGREES)
                    fetch(box)?.let { fetched ->
                        val links = socialLinks(fetched.places, fetched.refs)
                        // An area deleted during the fetch is in the next list, which deletes it again.
                        onDisk { store.replace(area, box, now, fetched.places, links) }
                        links
                    }
                } else {
                    onDisk { store.links(area) }
                }
            if (links == null) {
                failing[area] = failureCount++
                if (++placeFailures >= 2) return
                continue
            }
            if (!socialDue) continue
            val social = socialOf(links)
            if (social == null) {
                failing[area] = failureCount++
                socialFailures++
                continue
            }
            onDisk { store.replaceSocial(area, clock.now().toEpochMilliseconds(), social) }
            failing -= area
        }
    }

    private class Fetched(
        val places: List<MapPlace>,
        val refs: Map<String, List<String>>,
    )

    /** The places in [box], at most [MAX_PAGES] pages of them; null when Pinakes did not answer. */
    private suspend fun fetch(box: GeoBox): Fetched? {
        val places = mutableListOf<MapPlace>()
        val refs = mutableMapOf<String, List<String>>()
        var cursor: String? = null
        var pages = 0
        do {
            if (requests++ > 0) delay(requestGap)
            when (val answer = data.placesIn(box, cursor)) {
                is SourceAnswer.Answered -> {
                    places += answer.value.places
                    refs += answer.value.refs
                    cursor = answer.value.cursor
                }

                SourceAnswer.Unreachable -> {
                    return null
                }
            }
            pages++
        } while (cursor != null && pages < MAX_PAGES)
        return Fetched(places.distinctBy { it.uri }, refs)
    }

    /**
     * Nexus' tags and counts of the places of [links] it knows, each merged over the place's links as
     * the place page merges them; null when Nexus did not answer. A request asks for whole places
     * only (a place's links in one request), at most [MAX_SOCIAL_REQUESTS] of them. A view is matched
     * to its link as Nexus spells it, else as [linkKey] does.
     */
    private suspend fun socialOf(links: Map<String, String>): Map<String, PlaceSocial>? {
        val views = mutableMapOf<String, MutableList<ResourceView>>()
        for (batch in socialBatches(links).take(MAX_SOCIAL_REQUESTS)) {
            if (requests++ > 0) delay(requestGap)
            when (val answer = data.views(batch.keys.toList(), viewer = null)) {
                is SourceAnswer.Answered -> {
                    val byKey = batch.entries.associate { (link, place) -> linkKey(link) to place }
                    answer.value.forEach { view ->
                        val place = batch[view.uri] ?: byKey[linkKey(view.uri)] ?: return@forEach
                        views.getOrPut(place) { mutableListOf() } += view
                    }
                }

                SourceAnswer.Unreachable -> {
                    return null
                }
            }
        }
        return views.mapValues { (_, list) -> mergeResourceViews(list.distinctBy { it.id }) }
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

        /** 40 requests a minute at most, a third of Pinakes' limit. */
        val REQUEST_GAP = 1500.milliseconds

        /** Up to 10,000 places per area (pages of 500). */
        const val MAX_PAGES = 20

        /**
         * Nexus requests per area: 2,000 links, about 500 places with the links people referenced
         * for them; places past that have their pins offline but no tags.
         */
        const val MAX_SOCIAL_REQUESTS = 20

        /** Nexus' by_ids takes at most 100 links. */
        const val SOCIAL_BATCH = 100

        /**
         * The links Nexus is asked about for [places], each to the uri of its place: a place's own
         * uri, then the links people referenced for it in [refs], at most [SOCIAL_BATCH] a place. A
         * place's own uri is always its own; a ref that is another place's uri, or a ref of an
         * earlier place, is not asked again.
         */
        internal fun socialLinks(
            places: List<MapPlace>,
            refs: Map<String, List<String>>,
        ): Map<String, String> {
            val own = places.mapTo(HashSet()) { it.uri }
            val links = LinkedHashMap<String, String>()
            for (place in places) {
                val mine = (listOf(place.uri) + refs[place.uri].orEmpty().filter { it !in own }).filter { it !in links }.distinct()
                mine.take(SOCIAL_BATCH).forEach { links[it] = place.uri }
            }
            return links
        }

        /**
         * [links] as Nexus requests of at most [SOCIAL_BATCH] links, a place's links in one request,
         * places in the order of their first link.
         */
        internal fun socialBatches(links: Map<String, String>): List<Map<String, String>> {
            val batches = mutableListOf<Map<String, String>>()
            var batch = LinkedHashMap<String, String>()
            for ((place, placeLinks) in links.entries.groupBy({ it.value }, { it.key })) {
                if (batch.isNotEmpty() && batch.size + placeLinks.size > SOCIAL_BATCH) {
                    batches += batch
                    batch = LinkedHashMap()
                }
                placeLinks.take(SOCIAL_BATCH).forEach { batch[it] = place }
            }
            if (batch.isNotEmpty()) batches += batch
            return batches
        }

        /** [link] without what spellings of one link differ in: http or https, `www.`, a trailing slash, the host's case. */
        internal fun linkKey(link: String): String {
            val rest = link.removePrefix("https://").removePrefix("http://")
            if (rest == link) return link
            val host = rest.substringBefore('/').lowercase().removePrefix("www.")
            val path = rest.substringAfter('/', "").trimEnd('/')
            return if (path.isEmpty()) host else "$host/$path"
        }
    }
}

/** [OfflinePubkyStore] in SQLite. */
class SqlOfflinePubkyStore(
    driver: SqlDriver,
) : OfflinePubkyStore {
    private val queries = OfflinePubkyDatabase(driver).offlinePubkyQueries

    override fun areas(): Map<String, KeptArea> =
        queries.areas().executeAsList().associate { it.id to KeptArea(fetchedAt = it.fetched_at, socialAt = it.social_at) }

    override fun replace(
        area: String,
        box: GeoBox,
        fetchedAt: Long,
        places: List<MapPlace>,
        links: Map<String, String>,
    ) {
        queries.transaction {
            val socialAt = queries.socialAt(area).executeAsOneOrNull()?.social_at
            queries.deletePlaces(area)
            queries.deleteLinks(area)
            queries.putArea(area, fetchedAt, box.west, box.south, box.east, box.north, socialAt)
            places.forEach { queries.putPlace(area, it.uri, it.name, it.lat, it.lon, it.type, it.category) }
            links.entries.forEachIndexed { i, (link, place) -> queries.putLink(area, link, place, i.toLong()) }
        }
    }

    override fun links(area: String): Map<String, String> =
        queries.links(area).executeAsList().associateTo(LinkedHashMap()) { it.link to it.place }

    override fun replaceSocial(
        area: String,
        fetchedAt: Long,
        social: Map<String, PlaceSocial>,
    ) {
        queries.transaction {
            queries.deleteSocial(area)
            social.forEach { (place, it) -> queries.putSocial(area, place, SearchJson.encodeToString(PlaceSocial.serializer(), it)) }
            queries.setSocialAt(fetchedAt, area)
        }
    }

    override fun delete(area: String) {
        queries.transaction {
            queries.deletePlaces(area)
            queries.deleteLinks(area)
            queries.deleteSocial(area)
            queries.deleteArea(area)
        }
    }

    override fun socialOf(uris: List<String>): KeptSocial? {
        if (uris.isEmpty()) return null
        val row = queries.socialOf(uris.distinct()).executeAsOneOrNull() ?: return null
        val social =
            try {
                SearchJson.decodeFromString(PlaceSocial.serializer(), row.social)
            } catch (e: SerializationException) {
                // Written by another app version in a shape this one cannot read: as if not kept.
                return null
            } catch (e: IllegalArgumentException) {
                return null
            }
        return KeptSocial(social, fetchedAt = row.social_at)
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
            ) { uri, name, lat, lon, type, category, _ ->
                MapPlace(uri, name, lat, lon, type, category)
            }.executeAsList()
    }
}
