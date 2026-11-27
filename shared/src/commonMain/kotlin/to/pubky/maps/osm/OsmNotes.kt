package to.pubky.maps.osm

import io.ktor.client.HttpClient
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.UserAgent
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.URLBuilder
import io.ktor.http.appendPathSegments
import io.ktor.http.isSuccess
import io.ktor.http.parameters
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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

    /**
     * No server of the list could be reached: the request reached none of them (no connection, no
     * name, refused), so the note is on no server and can be sent again without becoming two.
     */
    data object Unreachable : NoteAnswer

    /**
     * Not taken, and a server may have seen the request: one failed (a 5xx, a 429, an answer not from
     * the API) or the connection broke after it went out. Sent again only by the user.
     */
    data object Failed : NoteAnswer

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
 * note, so a settings change applies to the next one, and may wait, as for the user's settings to be
 * read from the phone); a server that cannot be reached or fails (no
 * connection, a connection that timed out, a 5xx, a 429, or an answer that is not the API's, as from a
 * wrong address, a web site answering every path or a proxy: a 4xx without the API's `Error` header, a
 * 2xx whose body is not the GeoJSON `Feature` of a new note) hands over to the next one. A server that
 * refuses the note itself (a 4xx with the API's `Error` header: text too long, anonymous notes off, the
 * user's address blocked) is the answer: another server would take the same request no differently. A
 * request that went out and timed out waiting for its answer is [NoteAnswer.NoAnswer], since the note
 * may be on the map already. Which failures came before the request went out is the platform's to say
 * ([failedBeforeSending]; Android's engine throws a plain socket timeout when connecting a note). When
 * every server failed, the answer is [NoteAnswer.Unreachable] only if the request reached none of them,
 * else [NoteAnswer.Failed]. A failed note is not reported to the map's ServiceHealth line: it is the
 * user's own action, and its page says what happened.
 */
class HttpOsmNotes(
    private val client: HttpClient,
    private val servers: suspend () -> List<String>,
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
        var reachedOne = false
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
                    // A 2xx that is not a new note (a web page, an empty answer) made none: the next server.
                    status.isSuccess() && isNewNote(response) -> {
                        return NoteAnswer.Sent
                    }

                    status.value in 400..499 && status != HttpStatusCode.TooManyRequests && error != null -> {
                        return NoteAnswer.Refused(error)
                    }

                    else -> {
                        reachedOne = true
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: HttpRequestTimeoutException) {
                return NoteAnswer.NoAnswer
            } catch (e: Exception) {
                when {
                    // Never connected: nothing reached the server.
                    e is ConnectTimeoutException || failedBeforeSending(e) -> {}

                    e is SocketTimeoutException -> {
                        return NoteAnswer.NoAnswer
                    }

                    // The next server; this one may have the request when it broke after connecting.
                    else -> {
                        reachedOne = true
                    }
                }
            }
        }
        return if (reachedOne) NoteAnswer.Failed else NoteAnswer.Unreachable
    }
}

/**
 * Whether a 2xx is the note the API made: its body is a GeoJSON `Feature` (the API answers a new note
 * with it). Anything else, such as a web page or an empty answer, made no note. The body is already
 * read when the request returns (Ktor keeps a non-streamed body), so reading it here cannot time out.
 */
private suspend fun isNewNote(response: HttpResponse): Boolean {
    val body = response.bodyAsText()
    val feature = runCatching { Json.parseToJsonElement(body) as? JsonObject }.getOrNull()
    return (feature?.get("type") as? JsonPrimitive)?.content == "Feature"
}

/** Whether [e] failed a request before any of it went out, so no server can have the note. */
internal expect fun failedBeforeSending(e: Throwable): Boolean

/** Timeouts of one note: the API answers in well under a second; longer means it is stuck. */
private const val CONNECT_TIMEOUT_MS = 5_000L
private const val REQUEST_TIMEOUT_MS = 15_000L

/** The HTTP client of notes: the platform engine and the app's User-Agent, as the OSM API usage policy asks. */
fun osmHttpClient(appVersion: String): HttpClient = osmHttpClient(appVersion, CONNECT_TIMEOUT_MS, REQUEST_TIMEOUT_MS)

/**
 * [osmHttpClient] with its timeouts. A read waits four times the request's whole time, so a request
 * waiting for its answer always ends as a request timeout ([NoteAnswer.NoAnswer]: Android's engine then
 * closes the connection, and the stalled read fails as closed), never as a socket timeout. On Android a
 * socket timeout is then only ever one of connecting or of the TLS handshake, before the note went out
 * ([failedBeforeSending]).
 */
internal fun osmHttpClient(
    appVersion: String,
    connectTimeoutMs: Long,
    requestTimeoutMs: Long,
): HttpClient =
    HttpClient(searchHttpEngine()) {
        expectSuccess = false
        install(UserAgent) { agent = searchUserAgent(appVersion) }
        install(HttpTimeout) {
            connectTimeoutMillis = connectTimeoutMs
            requestTimeoutMillis = requestTimeoutMs
            socketTimeoutMillis = 4 * requestTimeoutMs
        }
    }
