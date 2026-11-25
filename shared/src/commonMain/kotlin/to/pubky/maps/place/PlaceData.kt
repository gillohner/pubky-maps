package to.pubky.maps.place

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.URLBuilder
import io.ktor.http.appendPathSegments
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import to.pubky.maps.search.SearchHttpException
import to.pubky.maps.search.ServerFallback
import to.pubky.maps.search.SourceAnswer
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.time.Duration.Companion.seconds

/**
 * What the place page and the map's Pubky places layer read: Pinakes (cards, lists, places in an
 * area) and Nexus (tags, posts, people, list names). Each answer says which server gave it, or that
 * no server of the list answered.
 */
interface PlaceData {
    /** The card of the first of [uris] Pinakes knows; an answer of null when it knows none. */
    suspend fun card(uris: List<String>): SourceAnswer<PlaceCardInfo?>

    /** The public lists that hold the card [cardId], the [viewer]'s own first. */
    suspend fun collections(
        cardId: String,
        viewer: String?,
    ): SourceAnswer<CollectionRefs>

    /** Places in [bbox] (west, south, east, north), one page from [cursor]. */
    suspend fun placesIn(
        bbox: GeoBox,
        cursor: String?,
    ): SourceAnswer<MapPlacesPage>

    /** Nexus' views of [uris] (at most 100), with the [viewer]'s own tags marked. */
    suspend fun views(
        uris: List<String>,
        viewer: String?,
    ): SourceAnswer<List<ResourceView>>

    /** Posts replying to ([embeds] false) or embedding ([embeds] true) the Nexus resource [resourceId], newest first. */
    suspend fun posts(
        resourceId: String,
        embeds: Boolean,
        skip: Int,
        limit: Int,
    ): SourceAnswer<List<PlacePost>>

    /** Profiles of [ids] (at most 100), with whether the [viewer] follows each. */
    suspend fun people(
        ids: List<String>,
        viewer: String?,
    ): SourceAnswer<List<PlacePerson>>

    /** The collection posts [postUris] (at most 100) as named lists. */
    suspend fun lists(
        postUris: List<String>,
        viewer: String?,
    ): SourceAnswer<List<PlaceList>>
}

/** A map area: west, south, east, north in degrees. */
data class GeoBox(
    val west: Double,
    val south: Double,
    val east: Double,
    val north: Double,
) {
    fun contains(other: GeoBox): Boolean = other.west >= west && other.east <= east && other.south >= south && other.north <= north

    /** This box widened to the [step]-degree grid around it, kept on the globe. */
    fun snappedOutward(step: Double): GeoBox =
        GeoBox(
            west = (floor(west / step) * step).coerceAtLeast(-180.0),
            south = (floor(south / step) * step).coerceAtLeast(-90.0),
            east = (ceil(east / step) * step).coerceAtMost(180.0),
            north = (ceil(north / step) * step).coerceAtMost(90.0),
        )

    /** This box grown by [share] of its size on each side, kept on the globe. */
    fun expandedBy(share: Double): GeoBox {
        val dx = (east - west) * share
        val dy = (north - south) * share
        return GeoBox(
            west = (west - dx).coerceAtLeast(-180.0),
            south = (south - dy).coerceAtLeast(-90.0),
            east = (east + dx).coerceAtMost(180.0),
            north = (north + dy).coerceAtMost(90.0),
        )
    }
}

/**
 * [PlaceData] over HTTP: Pinakes and Nexus through their server lists (read at every request, so a
 * settings change applies to the next one), the next server when one fails (see [ServerFallback]).
 * Pinakes' 404 on a card is an answer (it does not know the link), not a failure.
 */
class HttpPlaceData(
    private val client: HttpClient,
    private val pinakesServers: () -> List<String>,
    private val nexusServers: () -> List<String>,
) : PlaceData {
    private val pinakes = ServerFallback()
    private val nexus = ServerFallback()

    override suspend fun card(uris: List<String>): SourceAnswer<PlaceCardInfo?> =
        pinakes.first(pinakesServers()) { server ->
            uris.distinct().firstNotNullOfOrNull { uri ->
                val url = url(server, "v1", "resources", "by-uri") { append("uri", uri) }
                getOrNull(url)?.let { json -> withContext(Dispatchers.Default) { parsePinakesCard(json) } }
            }
        }

    override suspend fun collections(
        cardId: String,
        viewer: String?,
    ): SourceAnswer<CollectionRefs> =
        pinakes.first(pinakesServers()) { server ->
            val url =
                url(server, "v1", "resources", cardId, "collections") {
                    append("limit", LISTS_PAGE.toString())
                    if (viewer != null) append("viewer", viewer)
                }
            // A card that was removed meanwhile is held by no list.
            getOrNull(url)?.let(::parsePinakesCollections) ?: CollectionRefs(emptyList(), null)
        }

    override suspend fun placesIn(
        bbox: GeoBox,
        cursor: String?,
    ): SourceAnswer<MapPlacesPage> =
        pinakes.first(pinakesServers()) { server ->
            val url =
                url(server, "v1", "search") {
                    append("bbox", listOf(bbox.west, bbox.south, bbox.east, bbox.north).joinToString(",") { formatCoordinate(it) })
                    append("type", "Place")
                    append("limit", MAP_PAGE.toString())
                    if (cursor != null) append("cursor", cursor)
                }
            val json = getOrNull(url) ?: throw SearchHttpException(HttpStatusCode.NotFound.value, null)
            withContext(Dispatchers.Default) { parseMapPlaces(json) }
        }

    override suspend fun views(
        uris: List<String>,
        viewer: String?,
    ): SourceAnswer<List<ResourceView>> =
        nexus.first(nexusServers()) { server ->
            val url =
                url(server, "v1", "stream", "resources", "by_ids") {
                    append("limit_tags", TAGS_PER_PLACE.toString())
                    append("limit_taggers", TAGGERS_PER_TAG.toString())
                }
            val body =
                JsonObject(
                    buildMap {
                        put("uris", JsonArray(uris.take(BATCH).map(::JsonPrimitive)))
                        if (viewer != null) put("viewer_id", JsonPrimitive(viewer))
                    },
                )
            parseNexusResourceViews(post(url, body.toString()))
        }

    override suspend fun posts(
        resourceId: String,
        embeds: Boolean,
        skip: Int,
        limit: Int,
    ): SourceAnswer<List<PlacePost>> =
        nexus.first(nexusServers()) { server ->
            val url =
                url(server, "v1", "resource", resourceId, if (embeds) "embeds" else "replies") {
                    append("skip", skip.toString())
                    append("limit", limit.toString())
                }
            // A resource Nexus no longer has has no posts.
            getOrNull(url)?.let { parseNexusPosts(it, server) }.orEmpty()
        }

    override suspend fun people(
        ids: List<String>,
        viewer: String?,
    ): SourceAnswer<List<PlacePerson>> =
        nexus.first(nexusServers()) { server ->
            val body =
                JsonObject(
                    buildMap {
                        put("user_ids", JsonArray(ids.take(BATCH).map(::JsonPrimitive)))
                        if (viewer != null) put("viewer_id", JsonPrimitive(viewer))
                    },
                )
            parseNexusUsers(post(url(server, "v1", "stream", "users", "by_ids"), body.toString()), server)
        }

    override suspend fun lists(
        postUris: List<String>,
        viewer: String?,
    ): SourceAnswer<List<PlaceList>> =
        nexus.first(nexusServers()) { server ->
            val ids = postUris.mapNotNull(::globalPostId).take(BATCH)
            val body = JsonObject(mapOf("post_ids" to JsonArray(ids.map(::JsonPrimitive))))
            parseNexusCollections(post(url(server, "v1", "stream", "posts", "by_ids"), body.toString()), viewer)
        }

    /** The body of a 2xx answer, null on 404; any other status is a failure of the server. */
    private suspend fun getOrNull(url: String): String? {
        val response = client.get(url) { header(HttpHeaders.Accept, "application/json") }
        if (response.status == HttpStatusCode.NotFound) return null
        return response.bodyOrThrow()
    }

    private suspend fun post(
        url: String,
        json: String,
    ): String =
        client
            .post(url) {
                header(HttpHeaders.Accept, "application/json")
                contentType(ContentType.Application.Json)
                setBody(json)
            }.bodyOrThrow()

    private suspend fun HttpResponse.bodyOrThrow(): String {
        if (!status.isSuccess()) {
            val retryAfter =
                headers[HttpHeaders.RetryAfter]
                    ?.trim()
                    ?.toLongOrNull()
                    ?.seconds
            throw SearchHttpException(status.value, retryAfter)
        }
        return bodyAsText()
    }

    private fun url(
        server: String,
        vararg path: String,
        query: io.ktor.http.ParametersBuilder.() -> Unit = {},
    ): String =
        URLBuilder(server)
            .apply {
                appendPathSegments(*path)
                parameters.query()
            }.buildString()

    private companion object {
        /** Nexus' and Pinakes' batch routes take at most 100 entries. */
        const val BATCH = 100
        const val TAGS_PER_PLACE = 20
        const val TAGGERS_PER_TAG = 5
        const val LISTS_PAGE = 20

        /** Pinakes' largest page. */
        const val MAP_PAGE = 500
    }
}
