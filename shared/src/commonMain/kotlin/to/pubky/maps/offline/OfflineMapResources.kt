package to.pubky.maps.offline

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.UserAgent
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.readRawBytes
import io.ktor.http.HttpHeaders
import io.ktor.http.fromHttpToGmtDate
import io.ktor.http.toHttpDate
import io.ktor.util.date.GMTDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.maplibre.compose.resource.MapResourceError
import org.maplibre.compose.resource.MapResourceLoad
import org.maplibre.compose.resource.MapResourceLoadRequest
import org.maplibre.compose.resource.MapResourceProvider
import org.maplibre.compose.resource.MapResourceRequest
import to.pubky.maps.search.searchHttpEngine
import to.pubky.maps.search.searchUserAgent
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Loads the map's resources while an offline map downloads ([downloading]), so the download keeps
 * to [OfflineLimits]: at most [OfflineLimits.PARALLEL_REQUESTS] of its requests at a time, each with
 * the app's User-Agent. MapLibre's own client sends up to 20 at once and cannot be told which
 * requests are a download's before it loads them, so while a download runs this provider loads
 * every http(s) resource, the map's own without a limit. Otherwise MapLibre loads them itself.
 *
 * The client is made by [createClient] on the first request this provider loads, which MapLibre
 * runs on a background thread: the provider is set up on the main thread at app start, most starts
 * never download, and making a Ktor client takes about 0.3 s on CI's emulator, four times the rest
 * of the app's Application.onCreate.
 */
class OfflineMapResources(
    createClient: () -> HttpClient,
    private val downloading: () -> Boolean,
    private val clock: Clock = Clock.System,
) : MapResourceProvider {
    private val client by lazy(createClient)
    private val downloads = Semaphore(OfflineLimits.PARALLEL_REQUESTS)

    override fun accepts(request: MapResourceRequest): Boolean = accepts(request.url)

    internal fun accepts(url: String): Boolean = downloading() && (url.startsWith("https://") || url.startsWith("http://"))

    override suspend fun load(request: MapResourceLoadRequest): MapResourceLoad =
        load(
            offline = request.usage == MapResourceLoadRequest.Usage.Offline,
            url = request.url,
            priorEtag = request.priorEtag,
            priorModified = request.priorModified,
            range = request.range,
        )

    /** A download's request ([offline]) waits for one of the download's few connections. */
    internal suspend fun load(
        offline: Boolean,
        url: String,
        priorEtag: String? = null,
        priorModified: Instant? = null,
        range: LongRange? = null,
    ): MapResourceLoad {
        val fetch = suspend { fetch(url, priorEtag, priorModified, range) }
        return if (offline) downloads.withPermit { fetch() } else fetch()
    }

    /** One request, answered as MapLibre's own client would: the body with its cache headers, or why not. */
    private suspend fun fetch(
        url: String,
        priorEtag: String?,
        priorModified: Instant?,
        range: LongRange?,
    ): MapResourceLoad {
        val response =
            try {
                client.get(url) {
                    priorEtag?.let { header(HttpHeaders.IfNoneMatch, it) }
                    priorModified?.let { header(HttpHeaders.IfModifiedSince, GMTDate(it.toEpochMilliseconds()).toHttpDate()) }
                    range?.let { header(HttpHeaders.Range, "bytes=${it.first}-${it.last}") }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return MapResourceLoad.Failed(MapResourceError.Connection, e.message ?: "No connection")
            }
        val modified = response.date(HttpHeaders.LastModified)
        val expires = response.expires()
        return when (val status = response.status.value) {
            200, 206 -> {
                MapResourceLoad.Bytes(
                    bytes = response.readRawBytes(),
                    etag = response.headers[HttpHeaders.ETag],
                    mustRevalidate = response.cacheControl().any { it == "no-cache" || it == "must-revalidate" },
                    modified = modified,
                    expires = expires,
                )
            }

            204 -> {
                MapResourceLoad.NoContent(modified, expires)
            }

            304 -> {
                MapResourceLoad.NotModified(modified, expires)
            }

            404 -> {
                MapResourceLoad.Failed(MapResourceError.NotFound, "Not found", modified = modified, expires = expires)
            }

            429 -> {
                val retryAfter =
                    response.headers[HttpHeaders.RetryAfter]
                        ?.trim()
                        ?.toLongOrNull()
                        ?.let { clock.now() + it.seconds }
                MapResourceLoad.Failed(MapResourceError.RateLimit, "The map server is busy", retryAfter)
            }

            in 500..599 -> {
                MapResourceLoad.Failed(MapResourceError.Server, "The map server failed (HTTP $status)")
            }

            else -> {
                MapResourceLoad.Failed(MapResourceError.Other, "The map server refused (HTTP $status)")
            }
        }
    }

    private fun HttpResponse.cacheControl(): List<String> =
        headers
            .getAll(HttpHeaders.CacheControl)
            .orEmpty()
            .flatMap { it.split(',') }
            .map { it.trim().lowercase() }

    /** From max-age, else the Expires header. */
    private fun HttpResponse.expires(): Instant? {
        val maxAge = cacheControl().firstOrNull { it.startsWith("max-age=") }?.removePrefix("max-age=")?.toLongOrNull()
        return maxAge?.let { clock.now() + it.seconds } ?: date(HttpHeaders.Expires)
    }

    private fun HttpResponse.date(name: String): Instant? =
        headers[name]?.let { runCatching { Instant.fromEpochMilliseconds(it.fromHttpToGmtDate().timestamp) }.getOrNull() }
}

/** The client of offline downloads: the app's User-Agent, timeouts for a tile on a slow connection. */
fun offlineHttpClient(appVersion: String): HttpClient =
    HttpClient(searchHttpEngine()) {
        expectSuccess = false
        install(UserAgent) { agent = searchUserAgent(appVersion) }
        install(HttpTimeout) {
            connectTimeoutMillis = 10_000
            requestTimeoutMillis = 30_000
        }
    }
