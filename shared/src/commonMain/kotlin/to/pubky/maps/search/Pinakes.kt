package to.pubky.maps.search

import io.ktor.http.URLBuilder
import io.ktor.http.appendPathSegments
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import to.pubky.maps.links.GeoPoint
import to.pubky.maps.links.OsmId
import to.pubky.maps.links.OsmType
import to.pubky.maps.place.formatCoordinate

/**
 * A place card Pinakes found: a place someone referenced on Pubky. [rank] is its position in Pinakes'
 * answer, 0 first; Pinakes ranks by how well the name matches, then by how much people use the place
 * (and, with a viewer, by what the people they follow referenced).
 */
data class PinakesPlace(
    val uri: String,
    val name: String?,
    val point: GeoPoint,
    /** The schema.org type, e.g. `Museum`, `CafeOrCoffeeShop`. */
    val type: String?,
    /** The OpenStreetMap element this card is, from its link, its other spellings or an exact "also on" link. */
    val osm: OsmId?,
    /** How many public Pubky collections hold the place. */
    val collections: Int,
    val rank: Int,
    /** The type and its ancestors, root first (`Thing`, `Place`, `CivicStructure`, `Museum`). */
    val typePath: List<String> = emptyList(),
)

/** Shared by the search parsers: unknown fields are expected, both APIs grow. */
internal val SearchJson = Json { ignoreUnknownKeys = true }

/**
 * Pinakes' search URL on [server]: places matching [query] within the area's radius. With a
 * [viewer] (the signed-in user's pubky id), places the people they follow referenced rank higher
 * (`boost=following`, which leaves nothing out).
 */
fun pinakesSearchUrl(
    server: String,
    query: String,
    area: SearchArea,
    viewer: String?,
    limit: Int = RESULTS_PER_SOURCE,
): String =
    URLBuilder(server)
        .apply {
            appendPathSegments("v1", "search")
            parameters.append("q", query)
            parameters.append("type", "Place")
            parameters.append("near", "${formatCoordinate(area.centre.lat)},${formatCoordinate(area.centre.lon)}")
            parameters.append("radius", area.pinakesRadiusMeters.toString())
            parameters.append("limit", limit.toString())
            if (viewer != null) {
                parameters.append("boost", "following")
                parameters.append("viewer", viewer)
            }
        }.buildString()

/** Reads Pinakes' search answer. Cards without a point cannot be shown on the map and are dropped. */
fun parsePinakes(json: String): List<PinakesPlace> =
    SearchJson
        .decodeFromString(PinakesResponse.serializer(), json)
        .items
        .mapIndexedNotNull { index, card ->
            val point = card.point?.let { geoPointOrNull(it.lat, it.lon) } ?: return@mapIndexedNotNull null
            PinakesPlace(
                uri = card.uri,
                name = card.name?.trim()?.ifEmpty { null },
                point = point,
                type = card.type,
                osm = card.osmId(),
                collections = card.collections,
                rank = index,
                typePath = card.typePath,
            )
        }

private val OSM_ELEMENT_URL = Regex("^https?://(?:www\\.)?openstreetmap\\.org/(node|way|relation)/([0-9]{1,18})/?$")

/** The OpenStreetMap element an `https://www.openstreetmap.org/{type}/{id}` link names (any spelling). */
fun osmIdFromUrl(url: String): OsmId? {
    val match = OSM_ELEMENT_URL.matchEntire(url.trim()) ?: return null
    val id = match.groupValues[2].toLongOrNull()?.takeIf { it > 0 } ?: return null
    val type =
        when (match.groupValues[1]) {
            "node" -> OsmType.Node
            "way" -> OsmType.Way
            else -> OsmType.Relation
        }
    return OsmId(type, id)
}

/**
 * The OSM element of a card: its own link, then the links people used for it, then an exact "also on"
 * link (a Wikidata card whose item names the element). A "maybe" link is a hint, never an identity.
 */
private fun PinakesCard.osmId(): OsmId? =
    osmIdFromUrl(uri)
        ?: refs.firstNotNullOfOrNull { osmIdFromUrl(it) }
        ?: alsoOn.filter { it.kind != "maybe" }.firstNotNullOfOrNull { osmIdFromUrl(it.uri) }

@Serializable
private data class PinakesResponse(
    val items: List<PinakesCard> = emptyList(),
)

@Serializable
private data class PinakesCard(
    val uri: String,
    val type: String? = null,
    @SerialName("type_path") val typePath: List<String> = emptyList(),
    val name: String? = null,
    val point: PinakesPoint? = null,
    val refs: List<String> = emptyList(),
    @SerialName("also_on") val alsoOn: List<PinakesAlsoOn> = emptyList(),
    val collections: Int = 0,
)

@Serializable
private data class PinakesPoint(
    val lat: Double,
    val lon: Double,
)

@Serializable
private data class PinakesAlsoOn(
    val uri: String,
    val kind: String? = null,
)
