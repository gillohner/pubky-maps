package to.pubky.maps.write

import to.pubky.maps.account.CoreFailure
import to.pubky.maps.account.mapped
import to.pubky.maps.account.parseTag
import to.pubky.maps.cache.CachedBookmark
import to.pubky.maps.cache.CachedTag
import to.pubky.maps.core.BuiltObject
import to.pubky.maps.core.CollectionEdit
import to.pubky.maps.core.CollectionItem
import to.pubky.maps.core.CoreException
import to.pubky.maps.core.OwnCollection
import to.pubky.maps.core.PubkyMapsCore
import to.pubky.maps.core.buildPhotoFile
import to.pubky.maps.core.buildPhotoPost
import to.pubky.maps.core.buildReview
import to.pubky.maps.core.normalizePlaceUri
import to.pubky.maps.core.buildList as coreBuildList

/** [PubkyWrites] on the Rust core's UniFFI bindings: every object comes from the core's spec builders. */
class UniffiPubkyWrites(
    private val core: PubkyMapsCore,
) : PubkyWrites {
    override suspend fun tag(
        place: String,
        label: String,
    ): CachedTag =
        mapped {
            val built = core.tagPlace(place, label)
            parseTag(built.path, built.json.encodeToByteArray()) ?: throw CoreFailure.Other("the core built an unreadable tag")
        }

    override suspend fun untag(
        place: String,
        label: String,
    ) = mapped { core.untagPlace(place, label) }

    override fun buildPost(
        author: String,
        place: String,
        text: String,
    ): BuiltWrite = mapped { buildReview(author, place, text).toWrite() }

    override fun buildPhoto(
        author: String,
        place: String,
        image: ByteArray,
        mimeType: String,
        alt: String?,
        caption: String?,
    ): BuiltPhoto =
        mapped {
            val file = buildPhotoFile(author, image, mimeType)
            BuiltPhoto(filePath = file.path, post = buildPhotoPost(author, place, file.uri, alt, caption).toWrite())
        }

    override fun buildList(
        author: String,
        name: String,
        description: String?,
        places: List<String>,
        private: Boolean,
    ): BuiltWrite = mapped { coreBuildList(author, name, description, places.map { CollectionItem(it, null) }, private).toWrite() }

    override suspend fun put(
        path: String,
        json: String,
    ) = mapped { core.put(path, json.encodeToByteArray()) }

    override suspend fun putFile(
        path: String,
        bytes: ByteArray,
    ) = mapped { if (!core.exists(path)) core.put(path, bytes) }

    override suspend fun editList(
        listUri: String,
        place: String,
        remove: Boolean,
    ): OwnList {
        val (private, id) = listAddress(listUri) ?: throw CoreFailure.Other("not a collection: $listUri")
        val edit = if (remove) CollectionEdit.Remove(place) else CollectionEdit.Add(place, null)
        return mapped { core.editCollection(id, edit, private).toOwnList() }
    }

    override suspend fun lists(
        private: Boolean,
        skip: List<String>,
    ): ListScan =
        mapped {
            val scan = core.listCollections(private, skip)
            ListScan(
                lists = scan.collections.map { it.toOwnList() },
                otherPostIds = scan.otherPostIds,
                unreadPostIds = scan.unreadPostIds,
            )
        }

    override suspend fun save(place: String): CachedBookmark =
        mapped {
            val built = core.bookmarkPlace(place)
            CachedBookmark(path = built.path, place = normalize(place))
        }

    override suspend fun unsave(place: String) = mapped { core.removeBookmark(place) }

    override fun normalize(place: String): String =
        try {
            normalizePlaceUri(place)
        } catch (_: CoreException) {
            place
        }
}

/**
 * A list as the core read it from the homeserver, its description (written here or on pubky.app) kept.
 * The core gives only a list's places in its items; the other items are counted, never shown or dropped.
 * An edit that took the last place out of a list that also holds other links leaves it shown with no
 * places until the next scan leaves it out, so the user can still tick it again in Add to collection; a
 * list with no items at all stays.
 */
internal fun OwnCollection.toOwnList() =
    OwnList(
        uri = uri,
        kept = if (private) Kept.Private else Kept.Public,
        name = name,
        items = items.map { it.uri },
        description = listDescription(description),
        otherItems = otherItems.toInt(),
    )

private fun BuiltObject.toWrite() = BuiltWrite(path = path, uri = uri, json = json)
