package to.pubky.maps.place

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import to.pubky.maps.search.SearchJson
import to.pubky.maps.search.geoPointOrNull

// Readers of the Pinakes and Nexus answers the place page uses. Unknown fields are ignored: both
// APIs grow. Each was checked against the live development instances (fixtures in
// commonTest/fixtures/place, recorded by scripts/place/record-fixtures.py).

/** Reads a Pinakes card read (`GET /v1/resources/by-uri`). */
fun parsePinakesCard(json: String): PlaceCardInfo {
    val card = SearchJson.decodeFromString(PinakesCardRead.serializer(), json)
    return PlaceCardInfo(
        id = card.id,
        uri = card.uri,
        name = card.name?.trim()?.ifEmpty { null },
        type = card.type,
        category = card.category,
        address = card.address?.let { fields -> addressLine(fields.textValues()) },
        lat = card.point?.lat,
        lon = card.point?.lon,
        description = card.description?.trim()?.ifEmpty { null },
        image = card.image?.takeIf { it.startsWith("https://") },
        credits =
            card.credits.map {
                PlaceCredit(
                    field = it.field,
                    author = it.author,
                    license = it.license,
                    licenseUrl = it.licenseUrl,
                    sourceUrl = it.sourceUrl,
                    provider = it.provider,
                )
            },
        attribution = card.attribution,
        // A "maybe" link is a guess by name, never the same place for sure.
        alsoOn =
            card.alsoOn
                .filter { it.kind != "maybe" }
                .mapNotNull { link -> link.site?.let { AlsoOnLink(site = it, uri = link.uri, derived = link.derived) } },
        refs = card.refs,
        collections = card.collections,
        facts = card.facts?.textValues().orEmpty(),
        typePath = card.typePath,
    )
}

/** The text of each plain value (strings, numbers, booleans); nested values are left out. */
private fun Map<String, JsonElement>.textValues(): Map<String, String> =
    mapNotNull { (key, value) -> (value as? JsonPrimitive)?.contentOrNull?.let { key to it } }.toMap()

/**
 * "Heimplatz 1, 8001 Zürich" from Pinakes' address fields (`street`, `house_number`, `postcode`,
 * `city`); null when it has none of them.
 */
internal fun addressLine(address: Map<String, String>): String? {
    val street = address["street"]?.let { listOfNotNull(it, address["house_number"]).joinToString(" ") }
    val town = listOfNotNull(address["postcode"], address["city"]).joinToString(" ").ifEmpty { null }
    return listOfNotNull(street, town).joinToString(", ").ifEmpty { null }
}

/** One page of the public lists that hold a place (`GET /v1/resources/{id}/collections`). */
data class CollectionRefs(
    val items: List<CollectionRef>,
    val cursor: String?,
)

data class CollectionRef(
    val uri: String,
    val author: String,
)

fun parsePinakesCollections(json: String): CollectionRefs {
    val page = SearchJson.decodeFromString(PinakesCollectionPage.serializer(), json)
    return CollectionRefs(page.items.map { CollectionRef(it.uri, it.author) }, page.cursor)
}

/** One Nexus resource view (`POST /v1/stream/resources/by_ids`), as the page merges them. */
data class ResourceView(
    val id: String,
    val uri: String,
    val tags: List<ViewTag>,
    val replies: Int,
    val embeds: Int,
)

data class ViewTag(
    val label: String,
    val taggers: List<String>,
    val count: Int,
    /** The viewer's own tag file for the label; null when they did not use it. */
    val tagUri: String?,
)

fun parseNexusResourceViews(json: String): List<ResourceView> =
    SearchJson.decodeFromString(ListSerializer(NexusResourceView.serializer()), json).map { view ->
        ResourceView(
            id = view.details.id,
            uri = view.details.uri,
            tags = view.tags.map { ViewTag(it.label, it.taggers, it.taggersCount, it.tagUri) },
            replies = view.repliesCount,
            embeds = view.embedsCount,
        )
    }

/**
 * Merges the views of a place's links into one: each label once, its taggers listed once, its count
 * the sum of each link's count less the people listed on more than one link.
 */
fun mergeResourceViews(views: List<ResourceView>): PlaceSocial {
    val labels = LinkedHashMap<String, MutableList<ViewTag>>()
    views.forEach { view -> view.tags.forEach { labels.getOrPut(it.label) { mutableListOf() }.add(it) } }
    val tags =
        labels.map { (label, perLink) ->
            val listed = perLink.flatMap { it.taggers }
            val taggers = listed.distinct()
            PlaceTag(
                label = label,
                count = (perLink.sumOf { it.count } - (listed.size - taggers.size)).coerceAtLeast(taggers.size),
                taggers = taggers,
                mine = perLink.any { it.tagUri != null },
            )
        }
    return PlaceSocial(
        tags = tags.sortedWith(compareByDescending<PlaceTag> { it.count }.thenBy { it.label }),
        resourceIds = views.map { it.id }.distinct(),
        replyCount = views.sumOf { it.replies },
        embedCount = views.sumOf { it.embeds },
    )
}

/** Post kinds shown as posts about a place; ratings wait for social specs 1.1, the rest is not about the place. */
private val POST_KINDS = setOf("note", "article", "image", "link", "video", "file")

private val IMAGE_FILE = Regex("^pubky://([a-z0-9]{52})/pub/social/v1/files/([A-Za-z0-9]+\\.(?:jpe?g|png|webp|gif))$")

/**
 * Reads Nexus' posts about a resource (`/v1/resource/{id}/replies` or `/embeds`) from [server]:
 * deleted posts and kinds that are not posts about the place are dropped; image attachments become
 * [server]'s file URLs.
 */
fun parseNexusPosts(
    json: String,
    server: String,
): List<PlacePost> =
    SearchJson.decodeFromString(ListSerializer(NexusPostView.serializer()), json).mapNotNull { view ->
        val post = view.details
        if (post.deleted || post.kind !in POST_KINDS) return@mapNotNull null
        PlacePost(
            uri = post.uri,
            author = post.author,
            kind = post.kind,
            content = post.content.trim(),
            indexedAt = post.indexedAt,
            images = post.attachments.mapNotNull { nexusImage(it, server) },
        )
    }

/** [server]'s URLs of a `pubky://<owner>/pub/social/v1/files/<hash>.<ext>` image file. */
internal fun nexusImage(
    attachment: String,
    server: String,
): PlacePhoto? {
    val match = IMAGE_FILE.matchEntire(attachment) ?: return null
    val (owner, file) = match.destructured
    val base = "${server.trimEnd('/')}/v1/static/files/$owner/$file"
    return PlacePhoto(author = owner, url = "$base/main", thumbnailUrl = "$base/feed")
}

/** Reads Nexus' profiles (`POST /v1/stream/users/by_ids`) from [server]; unknown users are skipped. */
fun parseNexusUsers(
    json: String,
    server: String,
): List<PlacePerson> =
    SearchJson
        .parseToJsonElement(json)
        .jsonArray
        .filterIsInstance<JsonObject>()
        .map { SearchJson.decodeFromJsonElement(NexusUserView.serializer(), it) }
        .filterNot { it.details.deleted }
        .map { user ->
            PlacePerson(
                id = user.details.id,
                name =
                    user.details.name
                        .trim()
                        .ifEmpty { null },
                avatarUrl = user.details.image?.let { "${server.trimEnd('/')}/v1/static/avatar/${user.details.id}" },
                followed = user.relationship?.following == true,
            )
        }

/**
 * Reads Nexus' collection posts (`POST /v1/stream/posts/by_ids`) as lists: a collection's content is
 * `{"name", "items": [{"uri"}, ...]}`; other posts and unknown ones are skipped. A list counts only
 * its items whose URI [mayBeMapPlace] (the core's rule, the one the user's bookmarks are filtered with),
 * so another app's objects in it (a pubky.app post, a profile) are not called places.
 */
fun parseNexusCollections(
    json: String,
    viewer: String?,
    mayBeMapPlace: (String) -> Boolean,
): List<PlaceList> =
    SearchJson
        .parseToJsonElement(json)
        .jsonArray
        .filterIsInstance<JsonObject>()
        .map { SearchJson.decodeFromJsonElement(NexusPostView.serializer(), it).details }
        .filter { it.kind == "collection" && !it.deleted }
        .map { post ->
            val content = runCatching { SearchJson.parseToJsonElement(post.content).jsonObject }.getOrNull()
            PlaceList(
                uri = post.uri,
                author = post.author,
                name =
                    content
                        ?.get("name")
                        ?.jsonPrimitive
                        ?.contentOrNull
                        ?.trim()
                        ?.ifEmpty { null },
                itemCount = (content?.get("items") as? JsonArray)?.let { placeCount(it, mayBeMapPlace) },
                mine = post.author == viewer,
            )
        }

/** How many of a collection's [items] point at something that may be a place; an item without a URI is not one. */
private fun placeCount(
    items: JsonArray,
    mayBeMapPlace: (String) -> Boolean,
): Int =
    items.count { item ->
        val uri = ((item as? JsonObject)?.get("uri") as? JsonPrimitive)?.takeIf { it.isString }?.content
        uri != null && mayBeMapPlace(uri)
    }

/** `author:postId` of a `pubky://<author>/pub/social/v1/posts/<id>` post, as Nexus' by_ids takes it. */
internal fun globalPostId(uri: String): String? {
    val rest = uri.removePrefix("pubky://").takeIf { it != uri } ?: return null
    val author = rest.substringBefore('/')
    val id = rest.substringAfter("/pub/social/v1/posts/", "").takeIf { it.isNotEmpty() && '/' !in it } ?: return null
    return "$author:$id"
}

@Serializable
private data class PinakesCardRead(
    val id: String,
    val uri: String,
    val type: String? = null,
    @SerialName("type_path") val typePath: List<String> = emptyList(),
    val name: String? = null,
    val description: String? = null,
    val image: String? = null,
    val point: PinakesPoint? = null,
    @SerialName("also_on") val alsoOn: List<PinakesAlsoOn> = emptyList(),
    val refs: List<String> = emptyList(),
    val collections: Int = 0,
    val attribution: String? = null,
    val credits: List<PinakesCredit> = emptyList(),
    val category: String? = null,
    val address: Map<String, JsonElement>? = null,
    val facts: Map<String, JsonElement>? = null,
)

@Serializable
private data class PinakesPoint(
    val lat: Double,
    val lon: Double,
)

@Serializable
private data class PinakesAlsoOn(
    val uri: String,
    val site: String? = null,
    val kind: String? = null,
    val derived: Boolean = false,
)

@Serializable
private data class PinakesCredit(
    val field: String,
    val provider: String,
    @SerialName("source_url") val sourceUrl: String,
    val author: String? = null,
    val license: String? = null,
    @SerialName("license_url") val licenseUrl: String? = null,
)

@Serializable
private data class PinakesCollectionPage(
    val items: List<PinakesCollection> = emptyList(),
    val cursor: String? = null,
)

@Serializable
private data class PinakesCollection(
    val uri: String,
    val author: String,
)

@Serializable
private data class NexusResourceView(
    val details: NexusResourceDetails,
    val tags: List<NexusTag> = emptyList(),
    @SerialName("replies_count") val repliesCount: Int = 0,
    @SerialName("embeds_count") val embedsCount: Int = 0,
)

@Serializable
private data class NexusResourceDetails(
    val id: String,
    val uri: String,
)

@Serializable
private data class NexusTag(
    val label: String,
    val taggers: List<String> = emptyList(),
    @SerialName("taggers_count") val taggersCount: Int = 0,
    @SerialName("tag_uri") val tagUri: String? = null,
)

@Serializable
private data class NexusPostView(
    val details: NexusPostDetails,
)

@Serializable
private data class NexusPostDetails(
    val uri: String,
    val author: String,
    val kind: String = "unknown",
    val content: String = "",
    @SerialName("indexed_at") val indexedAt: Long = 0,
    val attachments: List<String> = emptyList(),
    val deleted: Boolean = false,
)

@Serializable
private data class NexusUserView(
    val details: NexusUserDetails,
    val relationship: NexusRelationship? = null,
)

@Serializable
private data class NexusUserDetails(
    val id: String,
    val name: String = "",
    val image: String? = null,
    val deleted: Boolean = false,
)

@Serializable
private data class NexusRelationship(
    val following: Boolean = false,
)

/** A place Pinakes holds, as the map's Pubky places layer shows it. */
data class MapPlace(
    val uri: String,
    val name: String?,
    val lat: Double,
    val lon: Double,
    /** The schema.org type, e.g. `Museum`. */
    val type: String?,
    /** OpenStreetMap's `key/value` category, e.g. `amenity/cafe`. */
    val category: String?,
    /** [type] and its schema.org ancestors, e.g. `[Thing, Place, LocalBusiness, FoodEstablishment, CafeOrCoffeeShop]`. */
    val typePath: List<String> = emptyList(),
)

/**
 * One page of places in an area; [cursor] is the next page, null on the last. [refs] holds, by a
 * place's uri, the other links people referenced for the same place (as on its card).
 */
data class MapPlacesPage(
    val places: List<MapPlace>,
    val cursor: String?,
    val refs: Map<String, List<String>> = emptyMap(),
)

/** Reads a Pinakes search answer (`GET /v1/search?bbox=…&type=Place&referenced=true`); cards without a point are dropped. */
fun parseMapPlaces(json: String): MapPlacesPage {
    val page = SearchJson.decodeFromString(PinakesSearchPage.serializer(), json)
    val places =
        page.items.mapNotNull { card ->
            val point = card.point ?: return@mapNotNull null
            if (geoPointOrNull(point.lat, point.lon) == null) return@mapNotNull null
            MapPlace(card.uri, card.name?.trim()?.ifEmpty { null }, point.lat, point.lon, card.type, card.category, card.typePath)
        }
    val kept = places.mapTo(HashSet()) { it.uri }
    val refs = page.items.filter { it.uri in kept && it.refs.isNotEmpty() }.associate { it.uri to it.refs }
    return MapPlacesPage(places, page.cursor, refs)
}

@Serializable
private data class PinakesSearchPage(
    val items: List<PinakesSearchCard> = emptyList(),
    val cursor: String? = null,
)

@Serializable
private data class PinakesSearchCard(
    val uri: String,
    val name: String? = null,
    val type: String? = null,
    val category: String? = null,
    val point: PinakesPoint? = null,
    val refs: List<String> = emptyList(),
    @SerialName("type_path") val typePath: List<String> = emptyList(),
)
