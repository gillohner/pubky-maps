package to.pubky.maps.write

import to.pubky.maps.cache.CachedBookmark
import to.pubky.maps.cache.CachedTag

/**
 * The Rust core's writes about places, as the app uses them. Every object is built and validated by
 * the core's spec builders (ids, paths, validation come from pubky-social-specs); nothing here builds
 * a path or an id by hand. On Android it wraps the UniFFI bindings; tests use a fake. Every `suspend`
 * function talks to the user's homeserver and throws [to.pubky.maps.account.CoreFailure].
 */
interface PubkyWrites {
    /** Tags [place] with [label] (`/pub/social/v1/tags/…`); an identical stored tag is not written again. */
    suspend fun tag(
        place: String,
        label: String,
    ): CachedTag

    /** Deletes the user's tag [label] on [place]; a tag that is not there is no error. */
    suspend fun untag(
        place: String,
        label: String,
    )

    /** Posts [text] about [place]: a `note` whose `parent` is the place. Returns the post's URI. */
    suspend fun post(
        place: String,
        text: String,
    ): String

    /**
     * Uploads [image] (JPEG, PNG or WebP, its metadata already stripped) and posts it as an `image`
     * post with `embed` = [place]. Returns the post's URI.
     */
    suspend fun postPhoto(
        place: String,
        image: ByteArray,
        mimeType: String,
        alt: String?,
        caption: String?,
    ): String

    /** Creates a list (public under `/pub`, [private] under `/priv`) holding [places]. */
    suspend fun createList(
        name: String,
        places: List<String>,
        private: Boolean,
    ): OwnList

    /**
     * Adds [place] to, or with [remove] removes it from, the newest version of the list [listUri] (the
     * core reads that version from the homeserver, never from an indexer).
     */
    suspend fun editList(
        listUri: String,
        place: String,
        remove: Boolean,
    ): OwnList

    /** The user's lists in `/pub` (or [private]: `/priv`), reading no post in [skip]. */
    suspend fun lists(
        private: Boolean,
        skip: List<String>,
    ): ListScan

    /** Saves [place] privately (`/priv/social/v1/bookmarks/`). */
    suspend fun save(place: String): CachedBookmark

    /** Removes the saved [place]; one that is not saved is no error. */
    suspend fun unsave(place: String)

    /** [place] spelled as the core stores it (OSM URLs canonical, `geo:` rounded); [place] itself when it is not a place URI. */
    fun normalize(place: String): String
}

/** The lists found in one root, and the posts read that are not lists (pass them as `skip` next time). */
data class ListScan(
    val lists: List<OwnList>,
    val otherPostIds: List<String>,
)
