package to.pubky.maps.write

import to.pubky.maps.cache.CachedAccount
import to.pubky.maps.links.GeoPoint
import to.pubky.maps.place.PlacePhoto
import to.pubky.maps.place.PlacePost
import to.pubky.maps.place.PlaceTag
import to.pubky.maps.search.GeoInput
import to.pubky.maps.search.parseGeoInput

/** One of the user's tags. [place] is spelled as the core stores places. */
data class OwnTag(
    val place: String,
    val label: String,
    /** Not on the homeserver yet. */
    val pending: Boolean = false,
)

/** A place the user saved. */
data class SavedEntry(
    val place: String,
    val kept: Kept,
    val name: String?,
    val pending: Boolean = false,
    /** Where it is, for its pin on the map: stored when it was saved, else read from a `geo:` link; null when not known. */
    val point: GeoPoint? = null,
)

/** A post or photo written on this phone, until the place page's next load from Nexus has it. */
data class OwnPost(
    val place: String,
    val text: String,
    val at: Long,
    /** The post's URI once the homeserver has it. */
    val uri: String?,
    /** The photo file on this phone, for a photo post. */
    val photoFile: String? = null,
)

/** A write that went through, kept so the user sees it until the account cache reflects it. */
data class Confirmed(
    val op: WriteOp,
    /** When it went through, epoch ms. */
    val at: Long,
    /** The post's URI, for posts and photos. */
    val uri: String? = null,
)

/** The user's own data as the app shows it: what the homeserver had, plus what this phone sent or still has to send. */
data class OwnData(
    /** The signed-in user, null when signed out. */
    val viewer: String? = null,
    /** True once the user's tags and saved places were read (from the homeserver or the cache). */
    val known: Boolean = false,
    val tags: List<OwnTag> = emptyList(),
    val saved: List<SavedEntry> = emptyList(),
    val lists: List<OwnList> = emptyList(),
    /** True once the user's lists were read from the homeserver in this run of the app. */
    val listsLoaded: Boolean = false,
    val listsLoading: Boolean = false,
    val posts: List<OwnPost> = emptyList(),
    /** Writes waiting to be sent. */
    val pending: Int = 0,
    /** Writes wait: the last attempt to send failed for lack of a connection (or [busy]). */
    val offline: Boolean = false,
    /** The homeserver was reached but throttled or failed (429, 5xx); the writes wait all the same. */
    val busy: Boolean = false,
    /** Names of places the user wrote about, by place as the core spells it. */
    val names: Map<String, String> = emptyMap(),
) {
    /** What the user has about the place whose links are [links] (each spelled as the core stores it). */
    fun forPlace(links: Set<String>): OwnPlace {
        val posts = posts.filter { it.place in links }.sortedByDescending { it.at }
        return OwnPlace(
            tags = if (known) tags.filter { it.place in links } else null,
            saved = saved.filter { it.place in links },
            lists = lists.filter { list -> list.items.any { it in links } },
            posts = posts.filter { it.photoFile == null },
            photos = posts.filter { it.photoFile != null },
            allLists = lists.map { it.uri }.toSet(),
        )
    }
}

/** The user's own data about one place. */
data class OwnPlace(
    /** The user's tags on it; null while they are not known (signed out, or never read). */
    val tags: List<OwnTag>?,
    val saved: List<SavedEntry>,
    /** The user's lists that hold it. */
    val lists: List<OwnList>,
    /** Posts written about it on this phone, newest first. */
    val posts: List<OwnPost>,
    val photos: List<OwnPost>,
    /** Every list of the user's that is known, holding the place or not. */
    val allLists: Set<String> = emptySet(),
) {
    val isSaved: Boolean get() = saved.isNotEmpty()
    val labels: Set<String>? get() = tags?.map { it.label }?.toSet()

    companion object {
        val None = OwnPlace(tags = null, saved = emptyList(), lists = emptyList(), posts = emptyList(), photos = emptyList())
    }
}

/** What the user saved on this phone only. */
data class PhoneData(
    val saved: List<PhoneSaved> = emptyList(),
    val lists: List<OwnList> = emptyList(),
)

data class PhoneSaved(
    val place: String,
    val name: String?,
    val savedAt: Long,
    val point: GeoPoint? = null,
)

/**
 * The user's own data: [cached] (the homeserver's copy on this phone) with [confirmed] writes the
 * cache does not reflect yet (made after it was read) and the [queue] still to be sent applied on
 * top, in order, so a write shows at once and disappears again when it is dropped. Places are
 * compared as [normalize] spells them. [labels] are the names stored for the places the user wrote
 * about (by place in any spelling: a write keeps the link as the user had it, the homeserver's copy
 * comes back as the core spells it), [points] where they are (by place as the core spells it).
 */
internal fun ownData(
    viewer: String?,
    cached: CachedAccount?,
    lists: List<OwnList>,
    phone: PhoneData,
    queue: List<QueuedOp>,
    confirmed: List<Confirmed>,
    labels: Map<String, String>,
    points: Map<String, GeoPoint> = emptyMap(),
    normalize: (String) -> String,
): OwnData {
    val signedIn = viewer != null && cached?.publicKey == viewer
    val tags = LinkedHashMap<Pair<String, String>, OwnTag>()
    val saved = LinkedHashMap<String, SavedEntry>()
    val ownLists = LinkedHashMap<String, OwnList>()
    val posts = mutableListOf<OwnPost>()
    val named = labels.mapKeys { normalize(it.key) }
    phone.saved.forEach {
        val place = normalize(it.place)
        saved["phone|$place"] = SavedEntry(place, Kept.Phone, it.name, point = it.point ?: geoLinkPoint(it.place))
    }
    phone.lists.forEach { ownLists[it.uri] = it.copy(items = it.items.map(normalize)) }
    if (signedIn) {
        cached.tags.forEach { tag ->
            val place = normalize(tag.place)
            tags[place to tag.label] = OwnTag(place, tag.label)
        }
        cached.bookmarks.forEach { bookmark ->
            val place = normalize(bookmark.place)
            saved["home|$place"] =
                SavedEntry(place, Kept.Private, named[place], point = pointOf(place, points))
        }
        lists.forEach { ownLists[it.uri] = it.copy(items = it.items.map(normalize)) }
        val readAt = cached.refreshedAt ?: 0
        confirmed.forEach { done ->
            // A refresh read after the write already has it; posts are never in the cache.
            val reflected = done.at <= readAt
            val op = done.op
            when {
                op is WriteOp.Post -> {
                    posts += OwnPost(normalize(op.place), op.text, op.at, done.uri)
                }

                op is WriteOp.Photo -> {
                    posts += OwnPost(normalize(op.place), op.caption.orEmpty(), op.at, done.uri, photoFile = op.file)
                }

                // Lists sent are stored as the homeserver returned them (in [lists]).
                op is WriteOp.CreateList || op is WriteOp.EditList -> {}

                !reflected -> {
                    apply(op, pending = false, tags, saved, ownLists, named, points, normalize)
                }
            }
        }
        queue.forEach { (_, op) ->
            when (op) {
                is WriteOp.Post -> posts += OwnPost(normalize(op.place), op.text, op.at, uri = null)
                is WriteOp.Photo -> posts += OwnPost(normalize(op.place), op.caption.orEmpty(), op.at, uri = null, photoFile = op.file)
                else -> apply(op, pending = true, tags, saved, ownLists, named, points, normalize)
            }
        }
    }
    return OwnData(
        viewer = viewer.takeIf { signedIn },
        known = signedIn && cached.refreshedAt != null,
        tags = tags.values.toList(),
        saved = saved.values.sortedBy { (it.name ?: it.place).lowercase() },
        lists = ownLists.values.toList(),
        posts = posts.sortedByDescending { it.at },
        pending = if (signedIn) queue.size else 0,
        names =
            phone.saved.mapNotNull { saved -> saved.name?.let { normalize(saved.place) to it } }.toMap() +
                (if (signedIn) named else emptyMap()),
    )
}

private fun apply(
    op: WriteOp,
    pending: Boolean,
    tags: MutableMap<Pair<String, String>, OwnTag>,
    saved: MutableMap<String, SavedEntry>,
    lists: MutableMap<String, OwnList>,
    /** Names by place as the core spells it. */
    named: Map<String, String>,
    points: Map<String, GeoPoint>,
    normalize: (String) -> String,
) {
    val place = normalize(op.place)
    when (op) {
        is WriteOp.Tag -> {
            tags[place to op.label] = OwnTag(place, op.label, pending)
        }

        is WriteOp.Untag -> {
            tags.remove(place to op.label)
        }

        is WriteOp.Save -> {
            saved["home|$place"] =
                SavedEntry(place, Kept.Private, named[place], pending, pointOf(place, points))
        }

        is WriteOp.Unsave -> {
            saved.remove("home|$place")
        }

        is WriteOp.CreateList -> {
            lists[op.uri] = OwnList(op.uri, if (op.private) Kept.Private else Kept.Public, op.name, listOf(place), pending = pending)
        }

        is WriteOp.EditList -> {
            val list = lists[op.list] ?: return
            val items = if (op.remove) list.items.filterNot { normalize(it) == place } else (list.items + place).distinct()
            lists[op.list] = list.copy(items = items, pending = list.pending || pending)
        }

        is WriteOp.Post, is WriteOp.Photo -> {}
    }
}

/** Where the place [place] (as the core spells it) is: the point stored for it, else its `geo:` link's. */
private fun pointOf(
    place: String,
    points: Map<String, GeoPoint>,
): GeoPoint? = points[place] ?: geoLinkPoint(place)

/** The point a `geo:` link names ("geo:47.37,8.54"); null for any other link, or a `geo:` search without one. */
private fun geoLinkPoint(place: String): GeoPoint? =
    if (place.startsWith("geo:", ignoreCase = true)) (parseGeoInput(place) as? GeoInput.Point)?.point else null

/**
 * The place's tags from Nexus with the user's own [mine] put in: Nexus lags behind the homeserver,
 * so a tag the user just added counts them and one they removed no longer does. [mine] null: the
 * user's tags are not known, Nexus' own word stands.
 */
fun mergeOwnTags(
    tags: List<PlaceTag>,
    mine: Set<String>?,
    viewer: String?,
): List<PlaceTag> {
    if (mine == null || viewer == null) return tags
    val merged =
        tags.mapNotNull { tag ->
            when {
                tag.label in mine && !tag.mine -> {
                    tag.copy(mine = true, count = tag.count + 1, taggers = listOf(viewer) + tag.taggers)
                }

                tag.label !in mine && tag.mine -> {
                    val count = tag.count - 1
                    if (count <= 0) null else tag.copy(mine = false, count = count, taggers = tag.taggers - viewer)
                }

                else -> {
                    tag
                }
            }
        }
    val added = mine.filter { label -> tags.none { it.label == label } }.map { PlaceTag(it, 1, listOf(viewer), mine = true) }
    return (merged + added).sortedWith(compareByDescending<PlaceTag> { it.mine }.thenByDescending { it.count })
}

/** The user's own posts and photos about the place as page rows, newest first, minus those Nexus already shows. */
fun ownPostRows(
    own: List<OwnPost>,
    shown: List<PlacePost>,
    viewer: String,
): List<PlacePost> {
    val shownUris = shown.map { it.uri }.toSet()
    return own
        .filter { it.uri == null || it.uri !in shownUris }
        .map { post ->
            PlacePost(
                uri = post.uri ?: "pending:${post.at}",
                author = viewer,
                kind = if (post.photoFile != null) "image" else "note",
                content = post.text,
                indexedAt = post.at,
                images = listOfNotNull(post.photoFile?.let { PlacePhoto(viewer, url = "file://$it", thumbnailUrl = "file://$it") }),
                pending = post.uri == null,
            )
        }
}
