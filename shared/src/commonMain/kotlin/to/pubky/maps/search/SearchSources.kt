package to.pubky.maps.search

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.UserAgent
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** What one source answered for one search. */
sealed interface SourceAnswer<out T> {
    /** [server] answered with [value]. */
    data class Answered<T>(
        val value: T,
        val server: String,
    ) : SourceAnswer<T>

    /** No server of the list answered usably. */
    data object Unreachable : SourceAnswer<Nothing>
}

/** The two online sources of search, each asked through its server list. */
interface PlaceSearch {
    suspend fun photon(
        query: String,
        area: SearchArea,
    ): SourceAnswer<List<PhotonPlace>>

    suspend fun pinakes(
        query: String,
        area: SearchArea,
        viewer: String?,
    ): SourceAnswer<List<PinakesPlace>>
}

/** A server answered, but not with a usable answer. */
class SearchHttpException(
    val status: Int,
    val retryAfter: Duration?,
) : Exception("HTTP $status")

/**
 * Photon and Pinakes over HTTP. Each asks the servers of its list in order ([photonServers] and
 * [pinakesServers] are read at every search, so a settings change applies to the next one) and
 * moves to the next server when one fails: no connection, a timeout, an error status or an answer it
 * cannot read. A server that answered 429 (too many requests) is skipped until its Retry-After has
 * passed (60 s when it gave none, at most 10 min).
 */
class HttpPlaceSearch(
    private val client: HttpClient,
    private val photonServers: () -> List<String>,
    private val pinakesServers: () -> List<String>,
    /** The device language (ISO 639-1), for Photon's result language. */
    private val language: () -> String,
    private val clock: Clock = Clock.System,
) : PlaceSearch {
    private val photonFallback = ServerFallback(clock)
    private val pinakesFallback = ServerFallback(clock)

    override suspend fun photon(
        query: String,
        area: SearchArea,
    ): SourceAnswer<List<PhotonPlace>> =
        photonFallback.first(photonServers()) { server ->
            val json = getText(photonSearchUrl(server, query, area, photonLanguage(language())))
            withContext(Dispatchers.Default) { parsePhoton(json) }
        }

    override suspend fun pinakes(
        query: String,
        area: SearchArea,
        viewer: String?,
    ): SourceAnswer<List<PinakesPlace>> =
        pinakesFallback.first(pinakesServers()) { server ->
            val json = getText(pinakesSearchUrl(server, query, area, viewer))
            withContext(Dispatchers.Default) { parsePinakes(json) }
        }

    private suspend fun getText(url: String): String {
        val response = client.get(url) { header(HttpHeaders.Accept, "application/json") }
        if (!response.status.isSuccess()) {
            val retryAfter =
                response.headers[HttpHeaders.RetryAfter]
                    ?.trim()
                    ?.toLongOrNull()
                    ?.seconds
            throw SearchHttpException(response.status.value, retryAfter)
        }
        return response.bodyAsText()
    }
}

/**
 * Tries a list of servers in order. Remembers which servers asked to be left alone (HTTP 429) and
 * until when; shared by the searches of one source.
 */
class ServerFallback(
    private val clock: Clock = Clock.System,
) {
    private val mutex = Mutex()
    private val coolingUntil = HashMap<String, Instant>()

    suspend fun <T> first(
        servers: List<String>,
        fetch: suspend (server: String) -> T,
    ): SourceAnswer<T> {
        for (server in servers) {
            if (isCooling(server)) continue
            try {
                return SourceAnswer.Answered(fetch(server), server)
            } catch (e: CancellationException) {
                throw e
            } catch (e: SearchHttpException) {
                if (e.status == HttpStatusCode.TooManyRequests.value) coolDown(server, e.retryAfter ?: DEFAULT_COOL_DOWN)
            } catch (e: Exception) {
                // No connection, timeout, unreadable answer: the next server.
            }
        }
        return SourceAnswer.Unreachable
    }

    private suspend fun isCooling(server: String): Boolean =
        mutex.withLock {
            val until = coolingUntil[server] ?: return@withLock false
            if (clock.now() < until) return@withLock true
            coolingUntil.remove(server)
            false
        }

    private suspend fun coolDown(
        server: String,
        duration: Duration,
    ) = mutex.withLock { coolingUntil[server] = clock.now() + duration.coerceIn(Duration.ZERO, MAX_COOL_DOWN) }

    private companion object {
        val DEFAULT_COOL_DOWN = 60.seconds
        val MAX_COOL_DOWN = 600.seconds
    }
}

/** Timeouts of one search request: a search that takes longer is no use while typing. */
private const val CONNECT_TIMEOUT_MS = 5_000L
private const val REQUEST_TIMEOUT_MS = 8_000L

/**
 * The HTTP client of search: the platform engine, the app's User-Agent (`PubkyMaps/<version>`, as
 * Photon's and Pinakes' operators ask clients to identify themselves), short timeouts.
 */
fun searchHttpClient(appVersion: String): HttpClient =
    HttpClient(searchHttpEngine()) {
        expectSuccess = false
        install(UserAgent) { agent = searchUserAgent(appVersion) }
        install(HttpTimeout) {
            connectTimeoutMillis = CONNECT_TIMEOUT_MS
            requestTimeoutMillis = REQUEST_TIMEOUT_MS
        }
    }

fun searchUserAgent(appVersion: String): String = "PubkyMaps/$appVersion"
