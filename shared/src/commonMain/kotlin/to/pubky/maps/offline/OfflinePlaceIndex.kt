package to.pubky.maps.offline

import app.cash.sqldelight.db.SqlDriver
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import to.pubky.maps.links.GeoPoint
import to.pubky.maps.links.OsmId
import to.pubky.maps.links.OsmType
import to.pubky.maps.offline.db.OfflinePlacesDatabase
import to.pubky.maps.place.OfflineLocality
import to.pubky.maps.search.SearchArea
import to.pubky.maps.search.distanceMeters
import to.pubky.maps.search.normalizeForMatch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sqrt

/** Search in the places of the offline areas, for when the search servers cannot be reached. */
fun interface OfflinePlaceSearch {
    /**
     * The places matching [query] (every word starts a word of the name, the category or the
     * locality, accents and case ignored), nearest to the map centre first; null when no offline
     * area can be searched.
     */
    suspend fun search(
        query: String,
        area: SearchArea,
    ): List<OfflinePlace>?
}

/**
 * The places of each offline area in SQLite ([OfflinePlacesDatabase]). [replace], [delete] and
 * [localityOf] block on disk: call them off the main thread; [search] moves to [io] itself.
 */
class SqlOfflinePlaceIndex internal constructor(
    driver: SqlDriver,
    private val io: CoroutineDispatcher,
    /** A word with this many places or fewer is read through its index alone ([nearest]); tests set it to read every word so. */
    private val boxFrom: Long,
) : OfflinePlaceSearch,
    OfflinePlaceStore,
    OfflineLocality {
    constructor(driver: SqlDriver, io: CoroutineDispatcher = Dispatchers.IO) : this(driver, io, BOX_FROM)

    private val queries = OfflinePlacesDatabase(driver).offlinePlacesQueries

    override fun areas(): Map<String, Long> = queries.areas().executeAsList().associate { it.id to it.new_data_at }

    /**
     * Stores the places of [area] in place of those it had, each with its locality ([Localities])
     * from the area's place labels; a place seen twice ([OfflinePlace.sameness]) is stored once.
     */
    override fun replace(
        area: String,
        newDataAt: Long,
        places: Sequence<OfflinePlace>,
    ) = queries.transaction {
        removeArea(area)
        val seen = HashSet<String>()
        // The labels are known only once every tile is read: the places are stored first, keeping
        // their row and point (a few MB for the largest area one download may take), then named.
        val labels = ArrayList<Pair<Long, OfflinePlace>>()
        val others = PlacePoints()
        for (place in places) {
            if (!seen.add(place.sameness)) continue
            val id = insert(area, place)
            if (place.key == "place") labels += id to place else others.add(id, place.point.lat, place.point.lon)
        }
        val localities = Localities(labels.map { it.second })
        for ((id, label) in labels) localities.of(label)?.let { queries.setLocality(it, id) }
        others.forEach { id, lat, lon -> localities.at(lat, lon)?.let { queries.setLocality(it, id) } }
        queries.putArea(area, newDataAt)
    }

    private fun insert(
        area: String,
        place: OfflinePlace,
    ): Long {
        queries.insertPlace(
            area = area,
            name = place.name,
            osm_type = place.osm?.type?.name,
            osm_id = place.osm?.id,
            tag_key = place.key,
            tag_value = place.value,
            category = place.category,
            lat = place.point.lat,
            lon = place.point.lon,
            locality = place.locality,
        )
        val id = queries.lastPlaceId().executeAsOne()
        wordsOf(place).forEach { queries.insertWord(it, id) }
        return id
    }

    override fun delete(area: String) = queries.transaction { removeArea(area) }

    private fun removeArea(area: String) {
        queries.deleteWords(area)
        queries.deletePlaces(area)
        queries.deleteArea(area)
    }

    /**
     * The locality stored with the element [osm], as offline search shows it. For a place no area
     * has a locality for (a Pubky pin, a dropped pin, a place the tiles name differently, one at an
     * area's edge that a neighbouring area's label reaches), the one the labels around [point] give
     * ([Localities.named], so a town's page is not named after itself), if the areas have a place in
     * the square of [NEAR_METERS] each way around it: a point farther from every place lies outside
     * their tiles, where a nearer label may be missing.
     */
    override fun localityOf(
        osm: OsmId?,
        point: GeoPoint,
        name: String?,
    ): String? {
        if (osm != null) {
            queries
                .localitiesOf(osm.id, osm.type.name)
                .executeAsList()
                .firstNotNullOfOrNull { it.locality }
                ?.let { return it }
        }
        val near = Box.around(point, NEAR_METERS)
        if (queries.anyIn(near.south, near.north, near.west, near.east).executeAsOneOrNull() == null) return null
        val reach = Box.around(point, Localities.MAX_REACH_METERS)
        val labels = queries.labelsIn(reach.south, reach.north, reach.west, reach.east, placeOfRow).executeAsList()
        return Localities(labels).named(name, point.lat, point.lon)
    }

    override suspend fun search(
        query: String,
        area: SearchArea,
    ): List<OfflinePlace>? =
        withContext(io) {
            if (queries.areas().executeAsList().isEmpty()) return@withContext null
            val typed = normalizeForMatch(query)
            val words = typed.split(' ').filter { it.isNotEmpty() }.distinct()
            if (words.isEmpty()) return@withContext emptyList()
            val counts = words.associateWith { queries.countWordFrom(it, it.until()).executeAsOne() }
            val centre = area.centre

            // Every word looks up the places it names, the rarest first (often the name, not the
            // category every restaurant has); a word too common to look up cheaply only filters.
            // A place's district and town are not in the index, so its own word finds it and the
            // others may match where it lies: "bahnhofstrasse winterthur", and a town's name finds
            // the town and its streets, not every shop in it.
            val asked = words.sortedBy { counts.getValue(it) }.filterIndexed { i, w -> i == 0 || counts.getValue(w) <= COMMON }
            val nearCentre = asked.flatMap { named(it, centre, counts.getValue(it)) }
            // "cafe uster": the other words, common ones too (but not one or two letters still being
            // typed), are looked up around the town or district as well, which can be farther than
            // the nearest few hundred cafés.
            val town =
                nearCentre
                    .filter { it.key == "place" && Localities.names(it.value) }
                    .filter { wordsOf(it.copy(category = null)).let { own -> own.size < words.size && words.containsAll(own) } }
                    .minByOrNull { distanceMeters(it.point, centre) }
            val aroundTown = words.filter { it in asked || it.length >= MIN_COMMON_LETTERS }
            val nearTown =
                town
                    ?.let { t ->
                        (aroundTown - wordsOf(t.copy(category = null))).flatMap { named(it, t.point, counts.getValue(it)) }
                    }.orEmpty()
            val found = (nearCentre + nearTown).distinct().sortedBy { distanceMeters(it.point, centre) }
            val matching =
                found
                    .filter { place ->
                        val placeWords = wordsOf(place) + localityWords(place.locality)
                        words.all { w -> placeWords.any { it.startsWith(w) } }
                    }.withoutRepeatedPieces()
            // A place named exactly as typed passes the cut also when farther than the others: the ranking puts it first.
            val (exact, others) = matching.partition { normalizeForMatch(it.name) == typed }
            (exact + others).take(RESULTS)
        }

    /**
     * [search]'s candidates for [word]: the places nearest [at] with a word of their name or
     * category that starts with it ([count] such words in the index), and when there are more than
     * the nearest few hundred, also those with the word itself, town and district labels first.
     */
    internal fun named(
        word: String,
        at: GeoPoint,
        count: Long = queries.countWordFrom(word, word.until()).executeAsOne(),
    ): List<OfflinePlace> {
        val until = word.until()
        val starting =
            nearest(at, count) {
                if (it == null) {
                    queries.withWordFrom(word, until, at.lat, at.lon, CANDIDATES, placeOfRow).executeAsList()
                } else {
                    queries
                        .withWordFromIn(it.south, it.north, it.west, it.east, word, until, at.lat, at.lon, CANDIDATES, placeOfRow)
                        .executeAsList()
                }
            }
        if (count <= CANDIDATES) return starting
        // Too many start with it for the nearest few hundred to hold every one: those with
        // the word itself ("Uster", not "Usterstrasse") are looked up too, town and
        // district labels first.
        val exact = queries.countWord(word).executeAsOne()
        if (exact <= boxFrom) return starting + queries.withWord(word, at.lat, at.lon, CANDIDATES, placeOfRow).executeAsList()
        val labels = queries.labelsWithWord(word, at.lat, at.lon, CANDIDATES, placeOfRow).executeAsList()
        if (labels.size >= CANDIDATES) return starting + labels
        val others =
            nearest(at, exact) {
                if (it == null) {
                    queries.withWord(word, at.lat, at.lon, CANDIDATES, placeOfRow).executeAsList().filter { p -> p.key != "place" }
                } else {
                    queries.withWordIn(it.south, it.north, it.west, it.east, word, at.lat, at.lon, CANDIDATES, placeOfRow).executeAsList()
                }
            }
        return starting + labels + others.take(CANDIDATES.toInt() - labels.size)
    }

    /**
     * The [CANDIDATES] places nearest [at] that [find] gives, nearest first, for a word with [count]
     * rows in the word index. [find] reads them within a box (half its side in degrees, as the
     * queries measure distances), or everywhere for null, through the word's index, which reads
     * all [count]. A common word's nearest places mostly lie close by, and a box around [at] reads
     * only the places in it: the box grows until its nearest [CANDIDATES] lie within its circle, so
     * that no place outside is nearer. It reads everywhere instead when the box would hold as many
     * places as [count], when the first circle has none of the word's places, or after [BOX_STEPS].
     */
    private fun nearest(
        at: GeoPoint,
        count: Long,
        find: (Box?) -> List<OfflinePlace>,
    ): List<OfflinePlace> {
        if (count <= boxFrom) return find(null)
        var half = FIRST_BOX_DEGREES
        repeat(BOX_STEPS) {
            val box = Box(at.lat - half, at.lat + half, at.lon - half, at.lon + half)
            if (queries.countIn(box.south, box.north, box.west, box.east).executeAsOne() >= count) return find(null)
            val found = find(box)
            val inCircle = found.count { squared(it.point.lat - at.lat) + squared(it.point.lon - at.lon) <= half * half }
            if (inCircle >= CANDIDATES) return found
            // None near: nothing says they are closer than elsewhere ("1" in house numbers of another town).
            if (inCircle == 0) return find(null)
            // A box with the places of the word as dense as this circle has them holds the nearest.
            half *= (sqrt(CANDIDATES.toDouble() / inCircle) * SPARE).coerceIn(MIN_GROWTH, MAX_GROWTH)
        }
        return find(null)
    }

    private companion object {
        /** How near a place of the areas (north, south, east or west) a point must be for their labels to name it. */
        const val NEAR_METERS = 500.0

        /** Places read for one search, nearest first, before the words are checked. */
        const val CANDIDATES = 400L

        /** Places handed to the ranking, which shows the best of them. */
        const val RESULTS = 30

        /** A word in more places than this ("s" while typing "strasse") is not looked up unless it is the rarest. */
        const val COMMON = 5_000L

        /** A common word shorter than this is not looked up around a town either. */
        const val MIN_COMMON_LETTERS = 3

        /**
         * Reading this many places through a word's index takes a few milliseconds; below it a box
         * that misses would cost more than it can save.
         */
        const val BOX_FROM = 5_000L

        /** Half the side of the first box [nearest] reads, in degrees (about 1 km). */
        const val FIRST_BOX_DEGREES = 0.01

        /** How much the box's side grows at a step, at least and at most. */
        const val MIN_GROWTH = 1.5
        const val MAX_GROWTH = 8.0

        /** How much larger the next box is than the density in the last circle asks. */
        const val SPARE = 1.2

        /** How many boxes [nearest] tries before it reads everywhere. */
        const val BOX_STEPS = 6
    }
}

private fun squared(x: Double) = x * x

/** The first string after every string that starts with this one: the end of its index range. */
private fun String.until(): String = dropLast(1) + (last() + 1)

/** The words a place is found by: those of its name and of its category ("Café", "Street"). */
internal fun wordsOf(place: OfflinePlace): Set<String> =
    (normalizeForMatch(place.name).split(' ') + normalizeForMatch(place.category.orEmpty()).split(' '))
        .filter { it.isNotEmpty() }
        .toSet()

/** The words of a place's locality ("Hottingen, Zürich"), which find it too: "bahnhofstrasse winterthur". */
internal fun localityWords(locality: String?): Set<String> =
    normalizeForMatch(locality.orEmpty()).split(' ').filter { it.isNotEmpty() }.toSet()

/** Rows and points of places, in arrays: an area can have a hundred thousand. */
private class PlacePoints {
    private var ids = LongArray(INITIAL)
    private var points = DoubleArray(2 * INITIAL)
    private var size = 0

    fun add(
        id: Long,
        lat: Double,
        lon: Double,
    ) {
        if (size == ids.size) {
            ids = ids.copyOf(2 * size)
            points = points.copyOf(4 * size)
        }
        ids[size] = id
        points[2 * size] = lat
        points[2 * size + 1] = lon
        size++
    }

    fun forEach(action: (id: Long, lat: Double, lon: Double) -> Unit) {
        for (i in 0 until size) action(ids[i], points[2 * i], points[2 * i + 1])
    }

    private companion object {
        const val INITIAL = 1024
    }
}

/** A place as the index stores it ([OfflinePlacesDatabase]'s `place` row, without its id and area). */
private val placeOfRow = {
    _: Long,
    _: String,
    name: String,
    osmType: String?,
    osmId: Long?,
    key: String,
    value: String,
    category: String?,
    lat: Double,
    lon: Double,
    locality: String?,
    ->
    OfflinePlace(name, osmOf(osmType, osmId), key, value, category, GeoPoint(lat, lon), locality)
}

/** The box [around] a point, in degrees. */
private class Box(
    val south: Double,
    val north: Double,
    val west: Double,
    val east: Double,
) {
    companion object {
        fun around(
            point: GeoPoint,
            meters: Double,
        ): Box {
            val lat = meters / METERS_PER_DEGREE
            val lon = lat / cos(point.lat * PI / 180).coerceAtLeast(0.01)
            return Box(point.lat - lat, point.lat + lat, point.lon - lon, point.lon + lon)
        }

        const val METERS_PER_DEGREE = 111_320.0
    }
}

private fun osmOf(
    type: String?,
    id: Long?,
): OsmId? {
    val osmType = OsmType.entries.firstOrNull { it.name == type } ?: return null
    return OsmId(osmType, id ?: return null)
}

/** Pieces of one street (or other place without an OSM element) closer than this are one result. */
private const val SAME_PIECE_METERS = 1_500.0

/**
 * Keeps the first of the places without an OSM element that share a name and a category within
 * [SAME_PIECE_METERS]: the tiles cut a street into a piece per tile.
 */
private fun List<OfflinePlace>.withoutRepeatedPieces(): List<OfflinePlace> {
    val kept = ArrayList<OfflinePlace>(size)
    for (place in this) {
        val repeat =
            place.osm == null &&
                kept.any { k ->
                    k.osm == null &&
                        k.category == place.category &&
                        normalizeForMatch(k.name) == normalizeForMatch(place.name) &&
                        distanceMeters(k.point, place.point) <= SAME_PIECE_METERS
                }
        if (!repeat) kept += place
    }
    return kept
}
