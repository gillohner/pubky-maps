package to.pubky.maps.write

import app.cash.sqldelight.db.SqlDriver
import kotlinx.serialization.json.Json
import to.pubky.maps.cache.db.CacheDatabase
import to.pubky.maps.links.GeoPoint
import to.pubky.maps.search.geoPointOrNull
import to.pubky.maps.write.db.PhoneDataDatabase

private val storeJson =
    Json {
        ignoreUnknownKeys = true
        classDiscriminator = "type"
    }

/**
 * The signed-in user's writes on this phone: the outbox, their lists as last read, the posts known
 * not to be lists, and the names and points of places they wrote about. It lives in the account cache's
 * database, so sign-out deletes it with the rest of the account. Calls block on disk.
 */
interface WriteStore {
    fun queue(): List<QueuedOp>

    fun enqueue(op: WriteOp): QueuedOp

    fun remove(seq: Long)

    fun lists(): List<OwnList>

    /** Replaces every list kept under `/priv` ([private]) or `/pub` with [lists]. */
    fun replaceLists(
        private: Boolean,
        lists: List<OwnList>,
    )

    fun putList(list: OwnList)

    fun otherPosts(private: Boolean): List<String>

    fun addOtherPosts(
        private: Boolean,
        ids: List<String>,
    )

    fun labels(): Map<String, String>

    fun putLabel(
        place: String,
        name: String,
    )

    /** Where the places the user wrote about are, by place as the core spells it. */
    fun points(): Map<String, GeoPoint>

    fun putPoint(
        place: String,
        point: GeoPoint,
    )
}

/** [WriteStore] in the account cache's SQLite database ([to.pubky.maps.cache.SqlAccountCache] clears it). */
class SqlWriteStore(
    driver: SqlDriver,
) : WriteStore {
    private val queries = CacheDatabase(driver).cacheQueries

    override fun queue(): List<QueuedOp> =
        queries.outbox().executeAsList().mapNotNull { row ->
            // A write this version cannot read (from a newer version, downgraded) is skipped, not sent.
            runCatching { QueuedOp(row.seq, storeJson.decodeFromString(WriteOp.serializer(), row.op)) }.getOrNull()
        }

    override fun enqueue(op: WriteOp): QueuedOp =
        queries.transactionWithResult {
            queries.putOutbox(storeJson.encodeToString(WriteOp.serializer(), op))
            QueuedOp(queries.lastSeq().executeAsOne(), op)
        }

    override fun remove(seq: Long) {
        queries.deleteOutboxItem(seq)
    }

    override fun lists(): List<OwnList> =
        queries.ownLists().executeAsList().mapNotNull { runCatching { storeJson.decodeFromString(OwnList.serializer(), it) }.getOrNull() }

    override fun replaceLists(
        private: Boolean,
        lists: List<OwnList>,
    ) = queries.transaction {
        queries.deleteOwnListsIn(if (private) 1L else 0L)
        lists.forEach { putList(it) }
    }

    override fun putList(list: OwnList) {
        queries.putOwnList(list.uri, if (list.kept == Kept.Private) 1L else 0L, storeJson.encodeToString(OwnList.serializer(), list))
    }

    override fun otherPosts(private: Boolean): List<String> = queries.otherPosts(if (private) 1L else 0L).executeAsList()

    override fun addOtherPosts(
        private: Boolean,
        ids: List<String>,
    ) = queries.transaction { ids.forEach { queries.putOtherPost(if (private) 1L else 0L, it) } }

    override fun labels(): Map<String, String> = queries.placeLabels().executeAsList().associate { it.place to it.name }

    override fun putLabel(
        place: String,
        name: String,
    ) {
        queries.putPlaceLabel(place, name)
    }

    override fun points(): Map<String, GeoPoint> =
        queries
            .placePoints()
            .executeAsList()
            .mapNotNull { row -> geoPointOrNull(row.lat, row.lon)?.let { row.place to it } }
            .toMap()

    override fun putPoint(
        place: String,
        point: GeoPoint,
    ) {
        queries.putPlacePoint(place, point.lat, point.lon)
    }
}

/** What the user keeps on this phone only. Calls block on disk. */
interface PhoneStore {
    fun read(): PhoneData

    fun save(saved: PhoneSaved)

    fun unsave(place: String)

    fun putList(list: OwnList)

    /** Marks [place] (a `geo:` link) as saved from the page of a point of its own ([PhoneData.pointPlaces]). */
    fun putPointPlace(place: String)

    /** [place] was saved from another page: it is read as its link is again. */
    fun deletePointPlace(place: String)

    /** Whether [name] was set on this phone. */
    fun flag(name: String): Boolean

    fun setFlag(name: String)
}

/** [PhoneStore] in its own SQLite database, kept when the user signs out. */
class SqlPhoneStore(
    driver: SqlDriver,
) : PhoneStore {
    private val queries = PhoneDataDatabase(driver).phoneDataQueries

    override fun read(): PhoneData =
        PhoneData(
            saved =
                queries
                    .savedPlaces { place, name, savedAt, lat, lon ->
                        PhoneSaved(place, name, savedAt, if (lat != null && lon != null) geoPointOrNull(lat, lon) else null)
                    }.executeAsList(),
            lists =
                queries.lists().executeAsList().mapNotNull {
                    runCatching { storeJson.decodeFromString(OwnList.serializer(), it) }.getOrNull()
                },
            pointPlaces = queries.pointPlaces().executeAsList().toSet(),
        )

    override fun save(saved: PhoneSaved) {
        queries.putSavedPlace(saved.place, saved.name, saved.savedAt, saved.point?.lat, saved.point?.lon)
    }

    override fun unsave(place: String) {
        queries.deleteSavedPlace(place)
    }

    override fun putList(list: OwnList) {
        queries.putList(list.uri, storeJson.encodeToString(OwnList.serializer(), list))
    }

    override fun putPointPlace(place: String) {
        queries.putPointPlace(place)
    }

    override fun deletePointPlace(place: String) {
        queries.deletePointPlace(place)
    }

    override fun flag(name: String): Boolean = queries.flag(name).executeAsOneOrNull() != null

    override fun setFlag(name: String) {
        queries.putFlag(name)
    }
}
