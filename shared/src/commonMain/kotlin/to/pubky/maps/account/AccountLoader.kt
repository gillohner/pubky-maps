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

/** At most this many saved places are read (a page holds 100). */
internal const val MAX_ITEMS = 2000

/**
 * At most this many tag files are read in one refresh. A refresh reads only the files it does not know
 * from the last one, so the next refresh goes on with the files this one left; the tags folder is listed
 * up to [MAX_PAGE_READS] pages (10 000 files), the files after that are not seen.
 */
internal const val MAX_TAG_READS = 2000

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
    /**
     * The user's data; its `refreshedAt` is when reading began, so a write made after that is known to be
     * newer. The tag files [known] (the data of the last refresh) holds are not read again. A refresh that
     * left listed tag files unread keeps [known]'s `refreshedAt`: a tag written since may be one of them,
     * so the writes since the last refresh still show over the data read.
     */
    suspend fun load(
        publicKey: String,
        known: CachedAccount? = null,
    ): CachedAccount =
        coroutineScope {
            val startedAt = now()
            val last = known?.takeIf { it.publicKey == publicKey }
            val name = async { profileName(publicKey) }
            val tags = async { tags(last) }
            val bookmarks = async { bookmarks() }
            val read = tags.await()
            CachedAccount(
                publicKey = publicKey,
                name = name.await(),
                refreshedAt = if (read.complete) startedAt else last?.refreshedAt ?: startedAt,
                tags = read.tags,
                bookmarks = bookmarks.await(),
                otherTags = read.others,
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

    /**
     * The user's tags on places, and the other files of the tags folder (see [CachedAccount.otherTags]).
     * The tags folder is shared with other apps: tags on a pubky.app post or profile are left out by the
     * core's rule ([PubkyCore.mayBeMapPlace]), as the core leaves out such bookmarks. A tag file names
     * its target only inside, so every listed file [known] does not hold is read, at most
     * [MAX_TAG_READS] of them. A known file whose target the rule now judges otherwise is read again.
     * Files that hold no tag, and tags on web pages Pinakes answered no place (left out of the cached
     * tags by [withoutPagesOf], not other files: the answer can change), are read on each refresh.
     */
    private suspend fun tags(known: CachedAccount?): TagFiles {
        // Every listed file, up to MAX_PAGE_READS pages: a known file costs no read.
        val paths = pages(Int.MAX_VALUE) { core.list(TAGS_DIR, it) }.filter { it.endsWith(".json") }
        val knownTags = known?.tags?.associateBy { it.path }.orEmpty()
        val knownOthers = known?.otherTags.orEmpty()
        val tags = ArrayList<CachedTag>()
        val others = LinkedHashMap<String, String>()
        val unread = ArrayList<String>()
        for (path in paths) {
            val tag = knownTags[path]
            when {
                tag != null && core.mayBeMapPlace(tag.place) -> tags += tag
                path in knownOthers && !core.mayBeMapPlace(knownOthers.getValue(path)) -> others[path] = knownOthers.getValue(path)
                else -> unread += path
            }
        }
        val permits = Semaphore(PARALLEL_READS)
        val read =
            coroutineScope {
                unread
                    .take(MAX_TAG_READS)
                    .map { path ->
                        async {
                            permits.withPermit {
                                val bytes =
                                    try {
                                        core.get(path)
                                    } catch (_: CoreFailure.NotFound) {
                                        // Listed but deleted since: nothing to keep.
                                        return@withPermit null
                                    }
                                path to parseTag(path, bytes)
                            }
                        }
                    }.awaitAll()
                    .filterNotNull()
            }
        for ((path, tag) in read) {
            when {
                tag == null -> {}

                core.mayBeMapPlace(tag.place) -> {
                    tags += tag
                }

                else -> {
                    others[path] = tag.place
                }
            }
        }
        return TagFiles(
            tags = tags.sortedWith(compareByDescending<CachedTag> { it.createdAt }.thenBy { it.path }),
            others = others,
            complete = unread.size <= MAX_TAG_READS,
        )
    }

    /** The place tags and the other tag files read, and whether every listed file was. */
    private class TagFiles(
        val tags: List<CachedTag>,
        val others: Map<String, String>,
        val complete: Boolean,
    )

    private suspend fun bookmarks(): List<CachedBookmark> =
        pages(MAX_ITEMS) { core.listBookmarks(it) }
            .map { CachedBookmark(path = it.path, place = it.place) }
            .sortedBy { it.place }

    /** The items of a listing, at most [max] of them. */
    private suspend fun <T> pages(
        max: Int,
        read: suspend (cursor: String?) -> Page<T>,
    ): List<T> {
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
        } while (cursor != null && items.size < max && reads < MAX_PAGE_READS)
        return items.take(max)
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
