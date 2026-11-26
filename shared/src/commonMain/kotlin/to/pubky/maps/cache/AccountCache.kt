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

/** The user's own data as last read from their homeserver. */
data class CachedAccount(
    val publicKey: String,
    val name: String?,
    val refreshedAt: Long?,
    val tags: List<CachedTag>,
    val bookmarks: List<CachedBookmark>,
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

    override fun read(): CachedAccount? {
        val account = queries.account().executeAsOneOrNull() ?: return null
        return CachedAccount(
            publicKey = account.public_key,
            name = account.name,
            refreshedAt = account.refreshed_at,
            tags = queries.tags { path, place, label, createdAt -> CachedTag(path, place, label, createdAt) }.executeAsList(),
            bookmarks = queries.bookmarks { path, place -> CachedBookmark(path, place) }.executeAsList(),
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
    }

    override fun replace(account: CachedAccount) {
        queries.transaction {
            deleteAll()
            queries.putAccount(account.publicKey, account.name, account.refreshedAt)
            account.tags.forEach { queries.putTag(it.path, it.place, it.label, it.createdAt) }
            account.bookmarks.forEach { queries.putBookmark(it.path, it.place) }
        }
    }

    override fun clear() {
        queries.transaction {
            deleteAll()
            deleteWrites()
        }
    }

    private fun deleteAll() {
        queries.deleteAccounts()
        queries.deleteTags()
        queries.deleteBookmarks()
    }

    /** What [to.pubky.maps.write.SqlWriteStore] keeps for the account. */
    private fun deleteWrites() {
        queries.deleteOutbox()
        queries.deleteOwnLists()
        queries.deleteOtherPosts()
        queries.deletePlaceLabels()
        queries.deletePlacePoints()
    }
}
