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
import to.pubky.maps.core.PubkyMapsCore
import to.pubky.maps.core.normalizePlaceUri

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

    override suspend fun post(
        place: String,
        text: String,
    ): String = mapped { core.postReview(place, text).uri }

    override suspend fun postPhoto(
        place: String,
        image: ByteArray,
        mimeType: String,
        alt: String?,
        caption: String?,
    ): String = mapped { core.postPhoto(place, image, mimeType, alt, caption).post.uri }

    override suspend fun createList(
        name: String,
        places: List<String>,
        private: Boolean,
    ): OwnList =
        mapped {
            core.createCollection(name, null, places.map { CollectionItem(it, null) }, private).toList(private)
        }

    override suspend fun editList(
        listUri: String,
        place: String,
        remove: Boolean,
    ): OwnList {
        val (private, id) = listAddress(listUri) ?: throw CoreFailure.Other("not a list: $listUri")
        val edit = if (remove) CollectionEdit.Remove(place) else CollectionEdit.Add(place, null)
        return mapped { core.editCollection(id, edit, private).toList(private) }
    }

    override suspend fun lists(
        private: Boolean,
        skip: List<String>,
    ): ListScan =
        mapped {
            val scan = core.listCollections(private, skip)
            ListScan(
                lists =
                    scan.collections.map { list ->
                        OwnList(
                            uri = list.uri,
                            kept = if (list.private) Kept.Private else Kept.Public,
                            name = list.name,
                            items = list.items.map { it.uri },
                        )
                    },
                otherPostIds = scan.otherPostIds,
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

private fun BuiltObject.toList(private: Boolean): OwnList {
    val (name, items) = listOfPost(json) ?: throw CoreFailure.Other("the core built an unreadable list")
    return OwnList(uri = uri, kept = if (private) Kept.Private else Kept.Public, name = name, items = items)
}
