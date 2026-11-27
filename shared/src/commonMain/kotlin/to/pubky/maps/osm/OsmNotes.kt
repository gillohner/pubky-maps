package to.pubky.maps.osm

import io.ktor.client.HttpClient
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.UserAgent
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.URLBuilder
import io.ktor.http.appendPathSegments
import io.ktor.http.isSuccess
import io.ktor.http.parameters
import kotlinx.coroutines.CancellationException
import to.pubky.maps.links.GeoPoint
import to.pubky.maps.links.OsmId
import to.pubky.maps.links.OsmType
import to.pubky.maps.search.searchHttpEngine
import to.pubky.maps.search.searchUserAgent

/** The place a note is about: where it goes on the map, and what the place is when the app knows. */
data class NoteTarget(
    val point: GeoPoint,
    val name: String? = null,
    /** The OpenStreetMap element the place is, so mappers find it at once; null for a point or another link. */
    val osm: OsmId? = null,
)

/** The element as one word for a page's route (`Node/123`); [osmIdOfRouteText] reads it back. */
val OsmId.routeText: String get() = "${type.name}/$id"

/** The element [routeText] wrote; null for any other text. */
fun osmIdOfRouteText(text: String): OsmId? {
    val parts = text.split("/")
    if (parts.size != 2) return null
    val type = OsmType.entries.firstOrNull { it.name == parts[0] } ?: return null
    return parts[1].toLongOrNull()?.let { OsmId(type, it) }
}

/** What the OpenStreetMap API servers answered for one note. */
sealed interface NoteAnswer {
    data object Sent : NoteAnswer

    /** A server answered that it does not take the note, with the reason it gave. */
    data class Refused(
        val reason: String,
    ) : NoteAnswer

    /** No server of the list answered. */
    data object Unreachable : NoteAnswer

    /**
     * The request went out but no answer came in time: the server may have taken the note. Not asked
     * of another server, so one note never becomes two.
     */
    data object NoAnswer : NoteAnswer
}

/** Leaves notes on the map for OpenStreetMap's mappers. */
fun interface OsmNotes {
    suspend fun create(
        target: NoteTarget,
        text: String,
    ): NoteAnswer
}

/** OpenStreetMap's limit for a note's text, in characters. */
const val NOTE_MAX = 2000

/** What the app adds under every note, so mappers see where notes come from (as other apps tag theirs). */
private const val NOTE_SIGNATURE = "#PubkyMaps"

/**
 * The note as it goes to OpenStreetMap: what the user wrote, then the place it is about (its name and
 * element link, when known) and the app's tag.
 */
fun noteText(
    target: NoteTarget,
    text: String,
): String {
    val about = listOfNotNull(target.name?.trim()?.ifEmpty { null }, target.osm?.url).joinToString(", ")
    return listOfNotNull(text.trim(), about.ifEmpty { null }?.let { "Place: $it" }, NOTE_SIGNATURE).joinToString("\n\n")
}

/** How many characters are left for the user's text of a note about [target] (negative when over). */
fun noteCharactersLeft(
    target: NoteTarget,
    text: String,
): Int = NOTE_MAX - noteText(target, text).codePointCount()

/** True when a note can be sent: something to say, within OpenStreetMap's limit. */
fun canSendNote(
    target: NoteTarget,
    text: String,
): Boolean = text.isNotBlank() && noteCharactersLeft(target, text) >= 0

private fun String.codePointCount(): Int {
    var count = 0
    var i = 0
    while (i < length) {
        i += if (this[i].isHighSurrogate() && i + 1 < length && this[i + 1].isLowSurrogate()) 2 else 1
        count++
    }
    return count
}

/** The notes endpoint of the OpenStreetMap API at [server]. */
fun osmNotesUrl(server: String): String = URLBuilder(server).apply { appendPathSegments("api", "0.6", "notes.json") }.buildString()

/**
 * Notes through the OpenStreetMap API servers of the list, without an account: the API takes a note
 * from anyone and shows it as anonymous. The servers are asked in order ([servers] is read at every
 * note, so a settings change applies to the next one); a server that cannot be reached or fails
 * (no connection, a connect timeout, a 5xx, a 429, or a 4xx that is not the API's: no `Error` header,
 * as from a wrong address or a proxy) hands over to the next one. A server that refuses the note
 * itself (a 4xx with the API's `Error` header: text too long, anonymous notes off, the user's address
 * blocked) is the answer: another server would take the same request no differently. A request that
 * went out and timed out waiting for its answer is [NoteAnswer.NoAnswer], since the note may be on the
 * map already. A failed note is not reported to the map's ServiceHealth line: it is the user's own
 * action, and its page says what happened.
 */
class HttpOsmNotes(
    private val client: HttpClient,
    private val servers: () -> List<String>,
) : OsmNotes {
    override suspend fun create(
        target: NoteTarget,
        text: String,
    ): NoteAnswer {
        val form =
            parameters {
                append("lat", target.point.lat.toString())
                append("lon", target.point.lon.toString())
                append("text", noteText(target, text))
            }
        for (server in servers()) {
            try {
                val response =
                    client.submitForm(osmNotesUrl(server), form) {
                        header(HttpHeaders.Accept, "application/json")
                    }
                val status = response.status
                // The API says why it refuses in its "Error" header ("No text was given", "Text is too long").
                val error = response.headers["Error"]?.trim()?.ifEmpty { null }
                when {
                    status.isSuccess() -> {
                        return NoteAnswer.Sent
                    }

                    status.value in 400..499 && status != HttpStatusCode.TooManyRequests && error != null -> {
                        return NoteAnswer.Refused(error)
                    }

                    else -> {}
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ConnectTimeoutException) {
                // Never connected: nothing reached the server.
            } catch (e: HttpRequestTimeoutException) {
                return NoteAnswer.NoAnswer
            } catch (e: SocketTimeoutException) {
                return NoteAnswer.NoAnswer
            } catch (e: Exception) {
                // No connection: the next server.
            }
        }
        return NoteAnswer.Unreachable
    }
}

/** Timeouts of one note: the API answers in well under a second; longer means it is stuck. */
private const val CONNECT_TIMEOUT_MS = 5_000L
private const val REQUEST_TIMEOUT_MS = 15_000L

/** The HTTP client of notes: the platform engine and the app's User-Agent, as the OSM API usage policy asks. */
fun osmHttpClient(appVersion: String): HttpClient =
    HttpClient(searchHttpEngine()) {
        expectSuccess = false
        install(UserAgent) { agent = searchUserAgent(appVersion) }
        install(HttpTimeout) {
            connectTimeoutMillis = CONNECT_TIMEOUT_MS
            requestTimeoutMillis = REQUEST_TIMEOUT_MS
        }
    }
