package to.pubky.maps.account

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import to.pubky.maps.cache.CachedAccount
import to.pubky.maps.cache.CachedBookmark
import to.pubky.maps.cache.CachedTag

/** The profile, where Pubky social specs v1 keeps it, then where older apps did. */
internal val PROFILE_PATHS = listOf("/pub/social/v1/profile.json", "/pub/pubky.app/profile.json")

internal const val TAGS_DIR = "/pub/social/v1/tags/"

/** At most this many tags and saved places are read (a page holds 100). */
internal const val MAX_ITEMS = 2000

/**
 * At most this many pages are asked for in one listing. A page of saved places can be empty and not be
 * the last (the core skips other apps' bookmarks), so the listing ends on a missing cursor, and this
 * bounds it against a cursor that never ends.
 */
internal const val MAX_PAGE_READS = 100

/** Tag files are read this many at a time. */
private const val PARALLEL_READS = 6

private val json = Json { ignoreUnknownKeys = true }

/** Reads the signed-in user's own data from their homeserver. Throws [CoreFailure]. */
internal class AccountLoader(
    private val core: PubkyCore,
    private val now: () -> Long,
) {
    /** The user's data; its `refreshedAt` is when reading began, so a write made after that is known to be newer. */
    suspend fun load(publicKey: String): CachedAccount =
        coroutineScope {
            val startedAt = now()
            val name = async { profileName(publicKey) }
            val tags = async { tags() }
            val bookmarks = async { bookmarks() }
            CachedAccount(
                publicKey = publicKey,
                name = name.await(),
                refreshedAt = startedAt,
                tags = tags.await(),
                bookmarks = bookmarks.await(),
            )
        }

    /** The profile's name, or null when there is no profile (or it has no name). */
    private suspend fun profileName(publicKey: String): String? {
        for (path in PROFILE_PATHS) {
            val bytes =
                try {
                    core.publicGet("pubky://$publicKey$path")
                } catch (_: CoreFailure.NotFound) {
                    continue
                }
            return parseObject(bytes)
                ?.get("name")
                ?.jsonPrimitive
                ?.contentOrNull
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
        }
        return null
    }

    private suspend fun tags(): List<CachedTag> {
        val paths = pages { core.list(TAGS_DIR, it) }.filter { it.endsWith(".json") }
        val permits = Semaphore(PARALLEL_READS)
        return coroutineScope {
            paths
                .map { path ->
                    async {
                        permits.withPermit {
                            val bytes =
                                try {
                                    core.get(path)
                                } catch (_: CoreFailure.NotFound) {
                                    return@withPermit null
                                }
                            parseTag(path, bytes)
                        }
                    }
                }.awaitAll()
                .filterNotNull()
                .sortedWith(compareByDescending<CachedTag> { it.createdAt }.thenBy { it.path })
        }
    }

    private suspend fun bookmarks(): List<CachedBookmark> =
        pages { core.listBookmarks(it) }
            .map { CachedBookmark(path = it.path, place = it.place) }
            .sortedBy { it.place }

    private suspend fun <T> pages(read: suspend (cursor: String?) -> Page<T>): List<T> {
        val items = mutableListOf<T>()
        var cursor: String? = null
        var reads = 0
        do {
            val page =
                try {
                    read(cursor)
                } catch (_: CoreFailure.NotFound) {
                    // A folder nothing was ever written to does not exist on the homeserver (404).
                    break
                }
            items += page.items
            cursor = page.nextCursor
            reads++
        } while (cursor != null && items.size < MAX_ITEMS && reads < MAX_PAGE_READS)
        return items.take(MAX_ITEMS)
    }
}

/** A tag file (`{"uri", "label", "created_at"}`), or null when it is not one. */
internal fun parseTag(
    path: String,
    bytes: ByteArray,
): CachedTag? {
    val obj = parseObject(bytes) ?: return null
    val place = obj["uri"]?.jsonPrimitive?.contentOrNull ?: return null
    val label = obj["label"]?.jsonPrimitive?.contentOrNull ?: return null
    // created_at is in microseconds (specs Timestamp).
    val createdAt = obj["created_at"]?.jsonPrimitive?.longOrNull ?: 0L
    return CachedTag(path = path, place = place, label = label, createdAt = createdAt)
}

private fun parseObject(bytes: ByteArray): JsonObject? =
    runCatching {
        json.parseToJsonElement(bytes.decodeToString()).jsonObject
    }.getOrNull()
