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

    /** The areas whose places are here. */
    override fun areas(): Set<String> = queries.areas().executeAsList().toSet()

    /** Stores the places of [area] in place of those it had; a place seen twice ([OfflinePlace.sameness]) is stored once. */
    override fun replace(
        area: String,
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
        queries.putArea(area)
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
            val words = normalizeForMatch(query).split(' ').filter { it.isNotEmpty() }.distinct()
            // The longest word finds the fewest places through the index; the others filter them.
            val longest = words.maxByOrNull { it.length } ?: return@withContext emptyList()
            val until = longest.dropLast(1) + (longest.last() + 1)
            val centre = area.centre
            val found =
                queries
                    .withWordFrom(longest, until, centre.lat, centre.lon, CANDIDATES) {
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
            found
                .filter { place ->
                    val placeWords = wordsOf(place)
                    words.all { w -> placeWords.any { it.startsWith(w) } }
                }.withoutRepeatedPieces()
                .take(RESULTS)
        }

    private companion object {
        /** Places read for one search, nearest first, before the words are checked. */
        const val CANDIDATES = 400L

        /** Places handed to the ranking, which shows the best of them. */
        const val RESULTS = 30
    }
}

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
