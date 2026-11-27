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

    /**
     * A post about [place] by [author]: a `note` whose `parent` is the place. Built (its id minted)
     * now, so it can wait in the outbox and is sent with [put] to this same path, however often.
     */
    fun buildPost(
        author: String,
        place: String,
        text: String,
    ): BuiltWrite

    /**
     * A photo of [place]: the file's address (from the bytes' hash) and an `image` post with `embed`
     * = the place and the file attached. [image] is JPEG, PNG or WebP with its metadata stripped.
     */
    fun buildPhoto(
        author: String,
        place: String,
        image: ByteArray,
        mimeType: String,
        alt: String?,
        caption: String?,
    ): BuiltPhoto

    /** A list holding [places], with [description] when not null: public under `/pub`, [private] under `/priv`. */
    fun buildList(
        author: String,
        name: String,
        description: String?,
        places: List<String>,
        private: Boolean,
    ): BuiltWrite

    /** Writes a built object to the user's homeserver. */
    suspend fun put(
        path: String,
        json: String,
    )

    /** Uploads a file unless it is already there (files are named by their content). */
    suspend fun putFile(
        path: String,
        bytes: ByteArray,
    )

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

/** An object built by the core's spec builders, ready to PUT. */
data class BuiltWrite(
    val path: String,
    val uri: String,
    val json: String,
)

/** A photo post and the path of the file it attaches. */
data class BuiltPhoto(
    val filePath: String,
    val post: BuiltWrite,
)

/** The lists found in one root, and the posts read that are not lists (pass them as `skip` next time). */
data class ListScan(
    val lists: List<OwnList>,
    val otherPostIds: List<String>,
    /** Posts whose folder could not be read now: what was known of them stays, the next scan tries again. */
    val unreadPostIds: List<String> = emptyList(),
)
