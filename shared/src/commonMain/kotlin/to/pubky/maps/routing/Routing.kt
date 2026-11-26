package to.pubky.maps.routing

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.UserAgent
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.URLBuilder
import io.ktor.http.appendPathSegments
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import to.pubky.maps.links.GeoPoint
import to.pubky.maps.search.SearchHttpException
import to.pubky.maps.search.ServerFallback
import to.pubky.maps.search.SourceAnswer
import to.pubky.maps.search.searchHttpEngine
import to.pubky.maps.search.searchUserAgent
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/** What the routing servers answered for one route. */
sealed interface RouteAnswer {
    data class Found(
        val route: Route,
        val server: String,
    ) : RouteAnswer

    /** A server answered that it has no route between the two points. */
    data class NoRoute(
        val reason: NoRouteReason,
    ) : RouteAnswer

    /** No server of the list answered. */
    data object Unreachable : RouteAnswer
}

/** Routes between two points, through the routing servers of the user's list. */
fun interface Routing {
    suspend fun route(
        from: GeoPoint,
        to: GeoPoint,
        mode: TravelMode,
    ): RouteAnswer
}

/**
 * Valhalla over HTTP: asks the servers of the list in order ([servers] is read at every route, so a
 * settings change applies to the next one) and moves to the next one when a server fails (no
 * connection, timeout, error status, an answer it cannot read; a 429 is skipped until its Retry-After
 * as in search). A server that refuses the route itself (HTTP 400 with a Valhalla error code: too far,
 * no road near, not connected) is the answer: the next server holds the same OpenStreetMap roads.
 * Requests to one server are at least [spacing] apart (FOSSGIS: one request a second).
 */
class HttpRouting(
    private val client: HttpClient,
    private val servers: () -> List<String>,
    clock: Clock = Clock.System,
    private val timeSource: TimeSource = TimeSource.Monotonic,
    private val spacing: Duration = 1.seconds,
) : Routing {
    private val fallback = ServerFallback(clock)
    private val mutex = Mutex()
    private val lastRequest = HashMap<String, TimeMark>()

    override suspend fun route(
        from: GeoPoint,
        to: GeoPoint,
        mode: TravelMode,
    ): RouteAnswer {
        val body = valhallaRouteBody(from, to, mode)
        val answer =
            fallback.first(servers()) { server ->
                waitTurn(server)
                val response =
                    client.post(valhallaRouteUrl(server)) {
                        contentType(ContentType.Application.Json)
                        header(HttpHeaders.Accept, "application/json")
                        setBody(body)
                    }
                val text = response.bodyAsText()
                when {
                    response.status.isSuccess() -> {
                        val route = withContext(Dispatchers.Default) { parseValhallaRoute(text) }
                        RouteAnswer.Found(route, server)
                    }

                    response.status == HttpStatusCode.BadRequest -> {
                        val code = valhallaErrorCode(text) ?: throw SearchHttpException(response.status.value, null)
                        RouteAnswer.NoRoute(noRouteReason(code))
                    }

                    else -> {
                        val retryAfter =
                            response.headers[HttpHeaders.RetryAfter]
                                ?.trim()
                                ?.toLongOrNull()
                                ?.seconds
                        throw SearchHttpException(response.status.value, retryAfter)
                    }
                }
            }
        return when (answer) {
            is SourceAnswer.Answered -> answer.value
            SourceAnswer.Unreachable -> RouteAnswer.Unreachable
        }
    }

    /** Waits until [spacing] has passed since the last request to [server], and takes the turn. */
    private suspend fun waitTurn(server: String) {
        mutex.withLock {
            val last = lastRequest[server]
            if (last != null) {
                val wait = spacing - last.elapsedNow()
                if (wait.isPositive()) delay(wait)
            }
            lastRequest[server] = timeSource.markNow()
        }
    }
}

/** Valhalla's route endpoint on [server]. */
fun valhallaRouteUrl(server: String): String = URLBuilder(server).apply { appendPathSegments("route") }.buildString()

/** Timeouts of one route: a long route takes Valhalla a few seconds, longer means the server is stuck. */
private const val CONNECT_TIMEOUT_MS = 5_000L
private const val REQUEST_TIMEOUT_MS = 15_000L

/** The HTTP client of routing: the platform engine and the app's User-Agent, as FOSSGIS asks. */
fun routingHttpClient(appVersion: String): HttpClient =
    HttpClient(searchHttpEngine()) {
        expectSuccess = false
        install(UserAgent) { agent = searchUserAgent(appVersion) }
        install(HttpTimeout) {
            connectTimeoutMillis = CONNECT_TIMEOUT_MS
            requestTimeoutMillis = REQUEST_TIMEOUT_MS
        }
    }
