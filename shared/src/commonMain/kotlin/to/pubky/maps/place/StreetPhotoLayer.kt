package to.pubky.maps.place

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.URLBuilder
import io.ktor.http.appendPathSegments
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import to.pubky.maps.search.SearchHttpException
import to.pubky.maps.search.ServerFallback
import to.pubky.maps.search.SourceAnswer
import to.pubky.maps.status.ServiceHealth
import to.pubky.maps.status.ServicePart
import kotlin.time.Duration.Companion.seconds

/**
 * Where a Panoramax server serves the vector tiles of its photos: the `sequences` layer (the lines a
 * camera was carried along) and the `pictures` layer (each photo's point).
 */
data class StreetPhotoTiles(
    /** The tile address with `{z}`, `{x}` and `{y}` in it. */
    val tiles: String,
    /** The last zoom the server has tiles for; the map draws them larger beyond it. */
    val maxZoom: Int,
    /** The server of the list that answered, for the map's credits. */
    val server: String,
)

/** Where the photo tiles of the first Panoramax server of [servers] that answers are. */
fun interface StreetPhotoTileSource {
    suspend fun tiles(servers: List<String>): SourceAnswer<StreetPhotoTiles>
}

/**
 * Panoramax over HTTP: asks the servers of the list in order for their map style
 * (`/api/map/style.json`, which every instance and the federation serve), and takes the tiles it
 * names. A server that fails (no connection, timeout, error status, a style without photo tiles)
 * hands over to the next one; a 429 is skipped until its Retry-After. The tiles themselves are
 * loaded by MapLibre, which does not tell when they fail: a server whose style answered keeps
 * the layer for that list while the app runs.
 */
class HttpStreetPhotoTiles(
    private val client: HttpClient,
) : StreetPhotoTileSource {
    private val fallback = ServerFallback()

    override suspend fun tiles(servers: List<String>): SourceAnswer<StreetPhotoTiles> =
        fallback.first(servers) { server ->
            val response = client.get(panoramaxStyleUrl(server))
            if (!response.status.isSuccess()) {
                val retryAfter = response.headers[HttpHeaders.RetryAfter]?.trim()?.toLongOrNull()
                throw SearchHttpException(response.status.value, retryAfter?.seconds)
            }
            val text = response.bodyAsText()
            withContext(Dispatchers.Default) { parsePanoramaxStyle(text, server) }
        }
}

/** The map style of a Panoramax [server]: its photo tiles and how it draws them. */
fun panoramaxStyleUrl(server: String): String = URLBuilder(server).apply { appendPathSegments("api", "map", "style.json") }.buildString()

/**
 * The photo tiles a Panoramax map style names: the vector source its `sequences` layer draws from,
 * at its first http(s) tile address that has `{z}`, `{x}` and `{y}`. Throws when the style has none,
 * so the next server is asked.
 */
fun parsePanoramaxStyle(
    json: String,
    server: String,
): StreetPhotoTiles {
    val root = StyleJson.parseToJsonElement(json).jsonObject
    val sourceName =
        (root["layers"] as? JsonArray)
            ?.mapNotNull { it as? JsonObject }
            ?.firstOrNull { it.string("source-layer") == SEQUENCES_LAYER }
            ?.string("source")
            ?: throw IllegalArgumentException("No photo sequences in this style")
    val source = (root["sources"] as? JsonObject)?.get(sourceName) as? JsonObject ?: throw IllegalArgumentException("No source $sourceName")
    require(source.string("type") == "vector") { "Not a vector source" }
    val tiles =
        (source["tiles"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            ?.firstOrNull { url ->
                (url.startsWith("https://") || url.startsWith("http://")) && "{z}" in url && "{x}" in url && "{y}" in url
            } ?: throw IllegalArgumentException("No tile address")
    val maxZoom = (source["maxzoom"] as? JsonPrimitive)?.intOrNull?.coerceIn(0, MAX_TILE_ZOOM) ?: DEFAULT_MAX_ZOOM
    return StreetPhotoTiles(tiles, maxZoom, server)
}

/** The street-level photo layer of the map, as the user switched it and as the servers answered. */
sealed interface StreetPhotoLayerState {
    /** Switched off: no Panoramax server is asked. */
    data object Off : StreetPhotoLayerState

    /** Switched on, the servers are being asked where the tiles are. */
    data object Finding : StreetPhotoLayerState

    /** The map draws the photos of [tiles]. */
    data class Shown(
        val tiles: StreetPhotoTiles,
    ) : StreetPhotoLayerState

    /** Switched on, but no server of the list answered: nothing is drawn. */
    data object Unreachable : StreetPhotoLayerState
}

/**
 * Runs the map's street-level photo layer: [show] asks [source] for the tiles once per server list
 * (the answer is kept while the list stays the same, also while the layer is off), [hide] stops
 * drawing. When no server answers, the layer names the outage in [health] (the map's one line) and is
 * asked again at the next [show]. Call it on the main thread.
 */
class StreetPhotoLayer(
    private val source: StreetPhotoTileSource,
    private val scope: CoroutineScope,
    private val health: ServiceHealth? = null,
) {
    private val mutableState = MutableStateFlow<StreetPhotoLayerState>(StreetPhotoLayerState.Off)
    val state: StateFlow<StreetPhotoLayerState> = mutableState.asStateFlow()

    private var job: Job? = null

    /** The server list the running question asks. */
    private var askedFor: List<String>? = null

    /** The tiles found for [foundFor]; kept while the server list stays the same. */
    private var found: StreetPhotoTiles? = null
    private var foundFor: List<String>? = null

    /** Whether this layer named the outage in [health], so only it takes the line back. */
    private var reportedOutage = false

    /** Draws the layer from the first server of [servers] that answers. */
    fun show(servers: List<String>) {
        val kept = found?.takeIf { foundFor == servers }
        if (kept != null) {
            job?.cancel()
            mutableState.value = StreetPhotoLayerState.Shown(kept)
            recoverOutage()
            return
        }
        if (mutableState.value == StreetPhotoLayerState.Finding && job?.isActive == true && askedFor == servers) return
        job?.cancel()
        askedFor = servers
        mutableState.value = StreetPhotoLayerState.Finding
        job =
            scope.launch {
                val answer =
                    try {
                        source.tiles(servers)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // Not a server's failure (the fallback takes those): nothing drawn, asked again next time.
                        SourceAnswer.Unreachable
                    }
                when (answer) {
                    is SourceAnswer.Answered -> {
                        found = answer.value
                        foundFor = servers
                        mutableState.value = StreetPhotoLayerState.Shown(answer.value)
                        recoverOutage()
                    }

                    SourceAnswer.Unreachable -> {
                        mutableState.value = StreetPhotoLayerState.Unreachable
                        reportedOutage = true
                        health?.failed(ServicePart.StreetPhotos)
                    }
                }
            }
    }

    /**
     * Stops drawing the layer. An outage this layer named leaves the map's line: with the layer off,
     * nothing the user sees is missing (a place page that cannot reach Panoramax names it again).
     */
    fun hide() {
        job?.cancel()
        job = null
        mutableState.value = StreetPhotoLayerState.Off
        recoverOutage()
    }

    /** Takes back the map's line if this layer named the outage. */
    private fun recoverOutage() {
        if (!reportedOutage) return
        reportedOutage = false
        health?.recovered(ServicePart.StreetPhotos)
    }
}

/** The tile layer Panoramax' sequences (the lines photos were taken along) are in. */
const val SEQUENCES_LAYER = "sequences"

/** The tile layer Panoramax' single photos are in. */
const val PICTURES_LAYER = "pictures"

/** Panoramax instances serve tiles up to zoom 15. */
private const val DEFAULT_MAX_ZOOM = 15

private const val MAX_TILE_ZOOM = 22

private fun JsonObject.string(key: String): String? = (get(key) as? JsonPrimitive)?.contentOrNull

private val StyleJson = Json { ignoreUnknownKeys = true }
