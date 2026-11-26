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
import to.pubky.maps.search.SearchArea
import to.pubky.maps.search.distanceMeters
import to.pubky.maps.search.normalizeForMatch

/** Search in the places of the offline areas, for when the search servers cannot be reached. */
fun interface OfflinePlaceSearch {
    /**
     * The places matching [query] (every word starts a word of the name or the category, accents and
     * case ignored), nearest to the map centre first; null when no offline area can be searched.
     */
    suspend fun search(
        query: String,
        area: SearchArea,
    ): List<OfflinePlace>?
}

/**
 * The places of each offline area in SQLite ([OfflinePlacesDatabase]). [replace] and [delete] block
 * on disk: call them off the main thread; [search] moves to [io] itself.
 */
class SqlOfflinePlaceIndex(
    driver: SqlDriver,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : OfflinePlaceSearch,
    OfflinePlaceStore {
    private val queries = OfflinePlacesDatabase(driver).offlinePlacesQueries

    override fun areas(): Map<String, Long> = queries.areas().executeAsList().associate { it.id to it.new_data_at }

    /** Stores the places of [area] in place of those it had; a place seen twice ([OfflinePlace.sameness]) is stored once. */
    override fun replace(
        area: String,
        newDataAt: Long,
        places: Sequence<OfflinePlace>,
    ) = queries.transaction {
        removeArea(area)
        val seen = HashSet<String>()
        for (place in places) {
            if (!seen.add(place.sameness)) continue
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
            )
            val id = queries.lastPlaceId().executeAsOne()
            wordsOf(place).forEach { queries.insertWord(it, id) }
        }
        queries.putArea(area, newDataAt)
    }

    override fun delete(area: String) = queries.transaction { removeArea(area) }

    private fun removeArea(area: String) {
        queries.deleteWords(area)
        queries.deletePlaces(area)
        queries.deleteArea(area)
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
            // The rarest word (often the name, not the category every restaurant has) finds the
            // fewest places through the index; the others filter them.
            val counts = words.associateWith { queries.countWordFrom(it, it.until()).executeAsOne() }
            val rarest = words.minBy { counts.getValue(it) }
            val centre = area.centre

            fun nearest(
                from: String,
                until: String,
            ) = queries
                .withWordFrom(from, until, centre.lat, centre.lon, CANDIDATES) {
                    _,
                    _,
                    name,
                    osmType,
                    osmId,
                    key,
                    value,
                    category,
                    lat,
                    lon,
                    ->
                    OfflinePlace(name, osmOf(osmType, osmId), key, value, category, GeoPoint(lat, lon))
                }.executeAsList()
            val found =
                if (counts.getValue(rarest) <= CANDIDATES) {
                    nearest(rarest, rarest.until())
                } else {
                    // Too many start with it for the nearest few hundred to hold every one: those
                    // with the word itself ("Uster", not "Usterstrasse") are looked up too.
                    (nearest(rarest, rarest.until()) + nearest(rarest, rarest + '\u0000'))
                        .distinct()
                        .sortedBy { distanceMeters(it.point, centre) }
                }
            val matching =
                found
                    .filter { place ->
                        val placeWords = wordsOf(place)
                        words.all { w -> placeWords.any { it.startsWith(w) } }
                    }.withoutRepeatedPieces()
            // A place named exactly as typed passes the cut wherever it is: the ranking puts it first.
            val (exact, others) = matching.partition { normalizeForMatch(it.name) == typed }
            (exact + others).take(RESULTS)
        }

    private companion object {
        /** Places read for one search, nearest first, before the words are checked. */
        const val CANDIDATES = 400L

        /** Places handed to the ranking, which shows the best of them. */
        const val RESULTS = 30
    }
}

/** The first string after every string that starts with this one: the end of its index range. */
private fun String.until(): String = dropLast(1) + (last() + 1)

/** The words a place is found by: those of its name and of its category ("Café", "Street"). */
internal fun wordsOf(place: OfflinePlace): Set<String> =
    (normalizeForMatch(place.name).split(' ') + normalizeForMatch(place.category.orEmpty()).split(' '))
        .filter { it.isNotEmpty() }
        .toSet()

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
