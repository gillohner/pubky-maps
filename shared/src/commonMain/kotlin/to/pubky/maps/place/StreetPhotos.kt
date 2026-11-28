package to.pubky.maps.place

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.URLBuilder
import io.ktor.http.Url
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
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import to.pubky.maps.links.GeoPoint
import to.pubky.maps.search.SearchHttpException
import to.pubky.maps.search.ServerFallback
import to.pubky.maps.search.SourceAnswer
import to.pubky.maps.status.ServiceHealth
import to.pubky.maps.status.ServicePart
import kotlin.time.Duration.Companion.seconds

/** A street-level photo that shows a place, from a Panoramax instance. */
data class StreetPhoto(
    val id: String,
    /** A small copy (500 px wide) for the place page. */
    val thumbnailUrl: String,
    /** Where the photo opens: the Panoramax viewer of the instance that holds it. */
    val viewerUrl: String,
    /** Who took it, as Panoramax names them; null when it names nobody. */
    val author: String?,
    /** When it was taken, as "Apr 2024"; null when the answer has no date. */
    val taken: String?,
    /** Its licence as Panoramax names it ("CC-BY-SA-4.0", "etalab-2.0"); null when none is given. */
    val licence: String?,
    /** The licence's text. */
    val licenceUrl: String?,
)

/** What the Panoramax servers answered for one place. */
sealed interface StreetPhotosAnswer {
    /** A server answered; [photos] is empty when it holds none that show the place. */
    data class Found(
        val photos: List<StreetPhoto>,
    ) : StreetPhotosAnswer

    /** No server of the list answered. */
    data object Unreachable : StreetPhotosAnswer
}

/** Street-level photos that show a place. */
fun interface StreetPhotos {
    suspend fun near(point: GeoPoint): StreetPhotosAnswer
}

/**
 * Panoramax over HTTP: asks the servers of the list in order ([servers] is read at every question, so
 * a settings change applies to the next place) for the photos that look at [GeoPoint] or are 360°
 * around it, and moves to the next server when one fails (no connection, timeout, error status, an
 * answer it cannot read; a 429 is skipped until its Retry-After). An answer with no photos is an
 * answer: the next server is not asked.
 */
class HttpPanoramax(
    private val client: HttpClient,
    private val servers: () -> List<String>,
) : StreetPhotos {
    private val fallback = ServerFallback()

    override suspend fun near(point: GeoPoint): StreetPhotosAnswer =
        when (
            val answer =
                fallback.first(servers()) { server ->
                    val response = client.get(panoramaxSearchUrl(server, point)) { header(HttpHeaders.Accept, "application/geo+json") }
                    if (!response.status.isSuccess()) {
                        val retryAfter = response.headers[HttpHeaders.RetryAfter]?.trim()?.toLongOrNull()
                        throw SearchHttpException(response.status.value, retryAfter?.seconds)
                    }
                    val text = response.bodyAsText()
                    withContext(Dispatchers.Default) { parsePanoramaxSearch(text, server) }
                }
        ) {
            is SourceAnswer.Answered -> StreetPhotosAnswer.Found(answer.value)
            SourceAnswer.Unreachable -> StreetPhotosAnswer.Unreachable
        }
}

/**
 * Panoramax' search for photos of the place at [point] on [server]: from 3 to [PHOTO_DISTANCE_M] metres
 * away (a shop front seen from across the street; closer, the camera stood on the place), with the
 * place within 60° of where the camera looked ("recognisable in a human field of view", the API's
 * documentation says; its default 30° leaves out most photos taken along a street), nearest first.
 */
fun panoramaxSearchUrl(
    server: String,
    point: GeoPoint,
): String =
    URLBuilder(server)
        .apply {
            appendPathSegments("api", "search")
            parameters.append("place_position", "${point.lon},${point.lat}")
            parameters.append("place_distance", "3-$PHOTO_DISTANCE_M")
            parameters.append("place_fov_tolerance", "60")
            parameters.append("limit", "$PHOTOS_ASKED")
        }.buildString()

/**
 * Reads Panoramax' search answer from [server]: at most [MAX_STREET_PHOTOS] photos in the server's
 * order, one per sequence (the frames of one walk or drive past the place look nearly the same),
 * leaving out a photo without an id or a web address for its thumbnail. A photo opens in the viewer of
 * the instance that holds it (the federation's answer names it as `via`; an instance holds its own
 * photos).
 */
fun parsePanoramaxSearch(
    json: String,
    server: String,
): List<StreetPhoto> {
    val root = PanoramaxJson.parseToJsonElement(json).jsonObject
    val features = root["features"]?.jsonArray ?: throw IllegalArgumentException("Not a Panoramax search answer")
    val origin = originOf(server)
    val sequences = HashSet<String>()
    val photos = ArrayList<StreetPhoto>()
    for (element in features) {
        if (photos.size == MAX_STREET_PHOTOS) break
        val feature = element as? JsonObject ?: continue
        // Ids are UUIDs; anything else would not be safe in the viewer's address.
        val id = feature.string("id")?.takeIf { PhotoId.matches(it) } ?: continue
        val properties = feature["properties"] as? JsonObject ?: JsonObject(emptyMap())
        val assets = feature["assets"] as? JsonObject
        val thumbnail =
            ((assets?.get("thumb") as? JsonObject)?.string("href") ?: properties.string("geovisio:thumbnail"))
                ?.takeIf(::isWebAddress)
                ?: continue
        val sequence = feature.string("collection")
        if (sequence != null && !sequences.add(sequence)) continue
        val links = (feature["links"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        val via = links.firstOrNull { it.string("rel") == "via" }?.string("href")?.let(::originOf)
        val viewer = via ?: origin ?: continue
        val producer =
            (feature["providers"] as? JsonArray)
                .orEmpty()
                .mapNotNull { it as? JsonObject }
                .firstOrNull { provider ->
                    (provider["roles"] as? JsonArray).orEmpty().any {
                        (it as? JsonPrimitive)?.contentOrNull ==
                            "producer"
                    }
                }?.string("name")
        photos +=
            StreetPhoto(
                id = id,
                thumbnailUrl = thumbnail,
                viewerUrl = "$viewer/#focus=pic&pic=$id",
                author = (producer ?: properties.string("geovisio:producer"))?.trim()?.takeIf { it.isNotEmpty() },
                taken = properties.string("datetime")?.let(::monthOf),
                licence = properties.string("license"),
                licenceUrl = links.firstOrNull { it.string("rel") == "license" }?.string("href")?.takeIf(::isWebAddress),
            )
    }
    return photos
}

/**
 * A licence as its authors write it: "CC-BY-SA-4.0" as "CC BY-SA 4.0", the French state's
 * "etalab-2.0" (Licence Ouverte) as "Etalab 2.0"; other names as given.
 */
fun licenceName(licence: String): String {
    Regex("""etalab-(\d+(?:\.\d+)?)""", RegexOption.IGNORE_CASE).matchEntire(licence)?.let { return "Etalab ${it.groupValues[1]}" }
    val cc = Regex("""CC-([A-Z-]+)-(\d+(?:\.\d+)?)""").matchEntire(licence) ?: return licence
    return "CC ${cc.groupValues[1]} ${cc.groupValues[2]}"
}

/** The place whose street-level photos are shown, and what Panoramax answered for it. */
data class StreetPhotosState(
    val point: GeoPoint,
    val photos: List<StreetPhoto> = emptyList(),
    /** No server has answered yet. */
    val loading: Boolean = true,
    /** No server of the list answered. */
    val failed: Boolean = false,
)

/**
 * The street-level photos of the open place, for the life of the process (a rotation keeps them):
 * [open] asks [source] for a place's point once, and keeps the answers of the last
 * [KEPT_ANSWERS] places, so going back to one does not ask again; an outage is not kept, the next
 * opening asks again. Every server failing marks [ServicePart.StreetPhotos] in [health], an answer
 * clears it.
 */
class StreetPhotosController(
    private val source: StreetPhotos,
    private val scope: CoroutineScope,
    private val health: ServiceHealth? = null,
) {
    private val mutableState = MutableStateFlow<StreetPhotosState?>(null)
    val state: StateFlow<StreetPhotosState?> = mutableState.asStateFlow()

    private val kept = LinkedHashMap<GeoPoint, List<StreetPhoto>>()
    private var job: Job? = null

    /** Shows the photos of the place at [point]; null closes them. */
    fun open(point: GeoPoint?) {
        if (point == null) return close()
        val current = mutableState.value
        if (current?.point == point && !current.failed) return
        job?.cancel()
        kept.remove(point)?.let { photos ->
            // Put back last: the answers kept are those of the places opened last.
            kept[point] = photos
            mutableState.value = StreetPhotosState(point, photos, loading = false)
            return
        }
        mutableState.value = StreetPhotosState(point)
        job =
            scope.launch {
                val answer =
                    try {
                        source.near(point)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // Not a server's failure (the fallback takes those, e.g. the list could not be
                        // read): no photos, asked again at the next opening, the app goes on.
                        mutableState.value = StreetPhotosState(point, loading = false, failed = true)
                        return@launch
                    }
                when (answer) {
                    is StreetPhotosAnswer.Found -> {
                        kept.remove(point)
                        kept[point] = answer.photos
                        if (kept.size > KEPT_ANSWERS) kept.remove(kept.keys.first())
                        mutableState.value = StreetPhotosState(point, answer.photos, loading = false)
                        health?.recovered(ServicePart.StreetPhotos)
                    }

                    StreetPhotosAnswer.Unreachable -> {
                        mutableState.value = StreetPhotosState(point, loading = false, failed = true)
                        health?.failed(ServicePart.StreetPhotos)
                    }
                }
            }
    }

    fun close() {
        job?.cancel()
        job = null
        mutableState.value = null
    }
}

/** At most this many photos of one place: a row of them on the page. */
const val MAX_STREET_PHOTOS = 6

/** Asked for more than shown, since frames of one sequence are left out but the first. */
private const val PHOTOS_ASKED = 30

/** How far from the place a photo may be taken, in metres. */
private const val PHOTO_DISTANCE_M = 40

private const val KEPT_ANSWERS = 32

private val MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

/** "2024-04-29T16:54:30+00:00" as "Apr 2024"; null when it is no such date. */
internal fun monthOf(datetime: String): String? {
    val match = Regex("""(\d{4})-(\d{2})-.*""").matchEntire(datetime) ?: return null
    val month = MONTHS.getOrNull(match.groupValues[2].toInt() - 1) ?: return null
    return "$month ${match.groupValues[1]}"
}

/** An http or https address: what the page may load or open, never a file or an app's own scheme. */
private fun isWebAddress(url: String): Boolean = url.startsWith("https://") || url.startsWith("http://")

/** The scheme, host and port of a web address; null for anything else. */
private fun originOf(url: String): String? =
    runCatching { Url(url) }
        .getOrNull()
        ?.takeIf { isWebAddress(url) && it.host.isNotEmpty() }
        ?.let { URLBuilder(protocol = it.protocol, host = it.host, port = it.specifiedPort).buildString().trimEnd('/') }

private fun JsonObject.string(key: String): String? = (get(key) as? JsonPrimitive)?.contentOrNull

private fun JsonArray?.orEmpty(): List<JsonElement> = this ?: emptyList()

private val PhotoId = Regex("[A-Za-z0-9-]{1,64}")

private val PanoramaxJson = Json { ignoreUnknownKeys = true }
