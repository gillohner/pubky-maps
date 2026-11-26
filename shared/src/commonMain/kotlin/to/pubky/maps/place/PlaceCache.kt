package to.pubky.maps.place

import app.cash.sqldelight.db.SqlDriver
import kotlinx.serialization.SerializationException
import to.pubky.maps.links.GeoPoint
import to.pubky.maps.links.OsmId
import to.pubky.maps.place.db.PlaceCacheDatabase
import to.pubky.maps.search.SearchJson

/**
 * The places the page showed, for reopening them offline. Calls block on disk: call them off the
 * main thread.
 */
interface PlaceCache {
    /** What was saved for [uri], or null. */
    fun read(uri: String): PlaceSnapshot?

    /** Saves [snapshot] under its uri, replacing what was there. */
    fun write(snapshot: PlaceSnapshot)
}

/** [PlaceCache] in SQLite, keeping the [kept] most recently saved places. */
class SqlPlaceCache(
    driver: SqlDriver,
    private val kept: Long = KEPT,
) : PlaceCache {
    private val queries = PlaceCacheDatabase(driver).placeCacheQueries

    override fun read(uri: String): PlaceSnapshot? {
        val json = queries.get(uri).executeAsOneOrNull() ?: return null
        return try {
            SearchJson.decodeFromString(PlaceSnapshot.serializer(), json)
        } catch (e: SerializationException) {
            // Written by another app version in a shape this one cannot read: as if not saved.
            null
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    override fun write(snapshot: PlaceSnapshot) {
        queries.transaction {
            queries.put(snapshot.uri, SearchJson.encodeToString(PlaceSnapshot.serializer(), snapshot), snapshot.savedAt)
            queries.keepNewest(kept)
        }
    }

    companion object {
        /** About 2 to 10 KB each. */
        const val KEPT = 300L
    }
}

/**
 * Where a place lies by the offline areas' map labels, "Hottingen, Zürich", for a page with no
 * address. Calls block on disk.
 */
fun interface OfflineLocality {
    /**
     * The district and town of the place [osm] named [name] at [point]: the one an offline area
     * stored for the element, else the one its labels give the point; null when no offline area
     * covers it or no label reaches it.
     */
    fun localityOf(
        osm: OsmId?,
        point: GeoPoint,
        name: String?,
    ): String?
}

/** Tags and counts of places kept for the offline areas, for pages Nexus cannot answer. Calls block on disk. */
fun interface KeptPlaceSocial {
    /** What is kept for one of [uris], from the area fetched last that has one; null when no kept area does. */
    fun socialOf(uris: List<String>): KeptSocial?
}

/** A kept place's tags and counts (read with no viewer: none is the user's), fetched at [fetchedAt] (epoch ms). */
data class KeptSocial(
    val social: PlaceSocial,
    val fetchedAt: Long,
)
