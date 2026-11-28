package to.pubky.maps.cache

import app.cash.sqldelight.db.SqlDriver
import to.pubky.maps.cache.db.CacheDatabase

/** A tag the user put on a place: `/pub/social/v1/tags/…`. */
data class CachedTag(
    val path: String,
    val place: String,
    val label: String,
    val createdAt: Long,
)

/** A place the user saved: `/priv/social/v1/bookmarks/…`. */
data class CachedBookmark(
    val path: String,
    val place: String,
)

/**
 * The user's own data as last read from their homeserver. [otherTags] are the tags of the tags folder
 * that are on no place (another app's tag on its own post or profile), by path, with their target: the
 * next refresh does not read them again (a tag file's name is made from its target and label, so its
 * target never changes).
 */
data class CachedAccount(
    val publicKey: String,
    val name: String?,
    val refreshedAt: Long?,
    val tags: List<CachedTag>,
    val bookmarks: List<CachedBookmark>,
    val otherTags: Map<String, String> = emptyMap(),
)

/**
 * The on-device copy of the signed-in user's own data (profile name, tags, saved places), readable
 * without the network. Calls block on disk: call them off the main thread.
 */
interface AccountCache {
    /** What is stored, or null when nothing is. */
    fun read(): CachedAccount?

    /** Starts a cache for [publicKey]: anything stored for another account is deleted, its outbox too. */
    fun begin(publicKey: String)

    /** Replaces the profile, tags and saved places with [account]; the outbox and lists stay. */
    fun replace(account: CachedAccount)

    /** Deletes everything, the account's outbox and lists ([to.pubky.maps.write.WriteStore]) too. */
    fun clear()
}

/** [AccountCache] in SQLite. */
class SqlAccountCache(
    driver: SqlDriver,
) : AccountCache {
    private val queries = CacheDatabase(driver).cacheQueries

    /**
     * The other tags [replace] wrote, null when unknown: they are written again only when they changed,
     * as a tag or bookmark write replaces the account but keeps them (up to 10 000 rows).
     */
    private var writtenOtherTags: Map<String, String>? = null

    override fun read(): CachedAccount? {
        val account = queries.account().executeAsOneOrNull() ?: return null
        return CachedAccount(
            publicKey = account.public_key,
            name = account.name,
            refreshedAt = account.refreshed_at,
            tags = queries.tags { path, place, label, createdAt -> CachedTag(path, place, label, createdAt) }.executeAsList(),
            bookmarks = queries.bookmarks { path, place -> CachedBookmark(path, place) }.executeAsList(),
            otherTags = queries.otherTags().executeAsList().associate { it.path to it.place },
        )
    }

    override fun begin(publicKey: String) {
        queries.transaction {
            if (queries.account().executeAsOneOrNull()?.public_key != publicKey) {
                deleteAll()
                deleteWrites()
                queries.putAccount(publicKey, null, null)
            }
        }
        writtenOtherTags = null
    }

    override fun replace(account: CachedAccount) {
        val otherTagsChanged = account.otherTags != writtenOtherTags
        writtenOtherTags = null
        queries.transaction {
            queries.deleteAccounts()
            queries.deleteTags()
            queries.deleteBookmarks()
            queries.putAccount(account.publicKey, account.name, account.refreshedAt)
            account.tags.forEach { queries.putTag(it.path, it.place, it.label, it.createdAt) }
            account.bookmarks.forEach { queries.putBookmark(it.path, it.place) }
            if (otherTagsChanged) {
                queries.deleteOtherTags()
                account.otherTags.forEach { (path, place) -> queries.putOtherTag(path, place) }
            }
        }
        writtenOtherTags = account.otherTags
    }

    override fun clear() {
        queries.transaction {
            deleteAll()
            deleteWrites()
        }
        writtenOtherTags = null
    }

    private fun deleteAll() {
        queries.deleteAccounts()
        queries.deleteTags()
        queries.deleteOtherTags()
        queries.deleteBookmarks()
    }

    /** What [to.pubky.maps.write.SqlWriteStore] keeps for the account. */
    private fun deleteWrites() {
        queries.deleteOutbox()
        queries.deleteOwnLists()
        queries.deleteOtherPosts()
        queries.deletePlaceLabels()
        queries.deletePlacePoints()
        queries.deletePlaceSummaries()
    }
}
