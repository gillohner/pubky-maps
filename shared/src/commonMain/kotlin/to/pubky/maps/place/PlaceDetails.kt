package to.pubky.maps.place

import kotlinx.serialization.Serializable
import to.pubky.maps.links.GeoPoint
import to.pubky.maps.search.geoPointOrNull

// What the place page shows about a place, each part from its own source. Plain data, so the page
// can be cached for offline reopening and screenshot without the network.

/** Where a card's image or description came from, and under which licence it is shown. */
@Serializable
data class PlaceCredit(
    /** `image` or `description`. */
    val field: String,
    val author: String?,
    val license: String?,
    val licenseUrl: String?,
    val sourceUrl: String,
    /** Who provided it (`wikimedia_commons`, `wikipedia`, `wikidata`, ...). */
    val provider: String,
)

/** The same place on another service ("Also on"). */
@Serializable
data class AlsoOnLink(
    val site: String,
    val uri: String,
    /** Found by a source (Wikidata names the official website), not referenced by anyone on Pubky. */
    val derived: Boolean,
)

/** The Pinakes card of a place: the link people referenced, read into a typed card. */
@Serializable
data class PlaceCardInfo(
    /** Pinakes' own id, for its routes (`/v1/resources/{id}/collections`). Never joined with Nexus. */
    val id: String,
    val uri: String,
    val name: String?,
    /** The schema.org type, e.g. `Museum`. */
    val type: String?,
    /** OpenStreetMap's `key/value` category, e.g. `tourism/museum`. */
    val category: String?,
    /** One line ("Heimplatz 1, 8001 Zürich"). */
    val address: String?,
    val lat: Double?,
    val lon: Double?,
    val description: String?,
    val image: String?,
    val credits: List<PlaceCredit>,
    /** The data licence notice to show with the card (OpenStreetMap's ODbL credit). */
    val attribution: String?,
    val alsoOn: List<AlsoOnLink>,
    /** The links people referenced that resolve to this card (ask Nexus by these). */
    val refs: List<String>,
    /** How many public collections hold the place. */
    val collections: Int,
    /** OpenStreetMap details, by OSM tag name (`opening_hours`, `phone`, ...). */
    val facts: Map<String, String>,
) {
    val point: GeoPoint? get() = if (lat != null && lon != null) geoPointOrNull(lat, lon) else null

    /** The credit for [field] (`image`, `description`), when Pinakes took it from another source. */
    fun credit(field: String): PlaceCredit? = credits.firstOrNull { it.field == field }
}

/** One label people put on the place, with who did. */
@Serializable
data class PlaceTag(
    val label: String,
    /** How many people used it (over every link of the place). */
    val count: Int,
    /** Some of them (Nexus lists a few per label), friends first once profiles are known. */
    val taggers: List<String>,
    /** The signed-in user used it. */
    val mine: Boolean,
)

/** The Pubky profile of someone who tagged or wrote about the place. */
@Serializable
data class PlacePerson(
    val id: String,
    val name: String?,
    /** Their profile picture as Nexus serves it; null when they have none. */
    val avatarUrl: String?,
    /** The signed-in user follows them. */
    val followed: Boolean,
)

/** A post about the place: a reply to it, or a post that embeds it. */
@Serializable
data class PlacePost(
    val uri: String,
    val author: String,
    val kind: String,
    val content: String,
    /** When Nexus indexed it, epoch milliseconds. */
    val indexedAt: Long,
    /** The image files it carries, as Nexus serves them. */
    val images: List<PlacePhoto>,
)

/** A photo of the place, as Nexus serves the author's file. */
@Serializable
data class PlacePhoto(
    val author: String,
    /** The full-size file. */
    val url: String,
    /** A small variant for thumbnails. */
    val thumbnailUrl: String,
)

/** A public list (collection post) that holds the place. */
@Serializable
data class PlaceList(
    val uri: String,
    val author: String,
    val name: String?,
    val itemCount: Int?,
    /** The signed-in user's own list. */
    val mine: Boolean,
)

/** Pubky's social data about the place, from Nexus. */
@Serializable
data class PlaceSocial(
    val tags: List<PlaceTag>,
    /** Nexus' resource ids of the place's links, for its replies and embeds. */
    val resourceIds: List<String>,
    val replyCount: Int,
    val embedCount: Int,
)

/** Everything the page showed, as cached for offline reopening. */
@Serializable
data class PlaceSnapshot(
    val uri: String,
    val card: PlaceCardInfo? = null,
    val social: PlaceSocial? = null,
    val people: List<PlacePerson> = emptyList(),
    val posts: List<PlacePost> = emptyList(),
    val photos: List<PlacePhoto> = emptyList(),
    val lists: List<PlaceList> = emptyList(),
    /** When it was saved, epoch milliseconds. */
    val savedAt: Long = 0,
    /**
     * Whose page it was (the signed-in user's pubky id, null signed out): their own tags and lists and
     * whom they follow are true only for them.
     */
    val viewer: String? = null,
) {
    /**
     * This snapshot as [viewer] may see it: for someone else (another account, or signed out), nothing
     * is theirs and they follow nobody; lists they wrote are theirs again.
     */
    fun forViewer(viewer: String?): PlaceSnapshot {
        if (viewer == this.viewer) return this
        return copy(
            social = social?.copy(tags = social.tags.map { it.copy(mine = false) }),
            people = people.map { it.copy(followed = false) },
            lists = lists.map { it.copy(mine = viewer != null && it.author == viewer) },
            viewer = viewer,
        )
    }
}
