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
import org.maplibre.compose.resource.MapResourceKind
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
 * every http(s) resource, the map's own without a limit. Otherwise it loads only the map's styles
 * (so an offline area's style can keep its cached copy, below), and MapLibre loads the rest itself. A download's request that gets new data from the map server
 * is told to [fetchedNewData].
 *
 * The style of an offline area ([keepsCopy]) whose server cannot be reached (no connection to it, a
 * timeout, a 5xx or 429 answer) keeps the copy MapLibre has cached: an expired copy MapLibre gave the
 * map already is answered as unchanged, one it held back to revalidate first (served
 * `must-revalidate`) is given as it is. MapLibre's own client answers with the error, and an error
 * fails a style until the map has changed it (its layers added), even after its cached copy drew:
 * the map then moves on to the next style of the server list, whose tiles are not on the phone, and
 * draws nothing. A style expires a day after it was fetched (OpenFreeMap's max-age), so without this
 * an offline map is blank a day after its download whenever the phone has a network but not the map
 * server. Any other style fails as before, so the map hands over to the next server, whose tiles it
 * can fetch. Tiles stay with MapLibre's own client: a tile that fails does not fail the style.
 *
 * A map request whose cached copy is still fresh is not fetched: MapLibre has given the map that
 * copy already, and its own client would ask the server only once the copy expires. MapLibre hands
 * a provider every network request at once, also these, so without this every new map (a theme
 * switch or rotation recreates it) would ask again for its cached style. It is
 * answered as unchanged with the copy's own dates, which keeps its expiry in the cache; unlike
 * MapLibre's own client, nothing asks again when the copy expires, only the next load of it.
 *
 * The client is made by [createClient] on the first request this provider fetches, which MapLibre
 * runs on a background thread: the provider is set up on the main thread at app start, before the
 * map, and a start whose cached style is fresh fetches nothing here.
 */
class OfflineMapResources(
    createClient: () -> HttpClient,
    private val downloading: () -> Boolean,
    private val clock: Clock = Clock.System,
    private val fetchedNewData: () -> Unit = {},
    private val keepsCopy: suspend (url: String) -> Boolean = { false },
) : MapResourceProvider {
    private val client by lazy(createClient)
    private val downloads = Semaphore(OfflineLimits.PARALLEL_REQUESTS)

    override fun accepts(request: MapResourceRequest): Boolean = accepts(request.url, request.kind)

    internal fun accepts(
        url: String,
        kind: MapResourceKind,
    ): Boolean = (url.startsWith("https://") || url.startsWith("http://")) && (kind == MapResourceKind.Style || downloading())

    override suspend fun load(request: MapResourceLoadRequest): MapResourceLoad =
        load(
            offline = request.usage == MapResourceLoadRequest.Usage.Offline,
            url = request.url,
            priorEtag = request.priorEtag,
            priorModified = request.priorModified,
            priorExpires = request.priorExpires,
            priorData = request.priorData,
            range = request.range,
        )

    /**
     * A download's request ([offline]) waits for one of the download's few connections. A map's
     * request for an offline area's style with a cached copy (its [priorData] when MapLibre has not
     * given it to the map yet, else only its etag or dates) keeps that copy when the server cannot
     * answer.
     */
    internal suspend fun load(
        offline: Boolean,
        url: String,
        priorEtag: String? = null,
        priorModified: Instant? = null,
        priorExpires: Instant? = null,
        priorData: ByteArray? = null,
        range: LongRange? = null,
    ): MapResourceLoad {
        val fetch = suspend { fetch(url, priorEtag, priorModified, priorData, range) }
        if (!offline) {
            if (priorExpires != null && priorExpires > clock.now()) return MapResourceLoad.NotModified(priorModified, priorExpires)
            val load = fetch()
            if (load !is MapResourceLoad.Failed || load.reason !in UNREACHABLE || !keepsCopy(url)) return load
            return when {
                // Held back because it must be revalidated first (MapLibre's isUsable).
                priorData != null -> {
                    MapResourceLoad.Bytes(priorData, priorEtag, mustRevalidate = true, priorModified, priorExpires)
                }

                priorEtag != null || priorModified != null || priorExpires != null -> {
                    MapResourceLoad.NotModified(
                        priorModified,
                        priorExpires,
                    )
                }

                else -> {
                    load
                }
            }
        }
        return downloads.withPermit { fetch() }.also { if (it is MapResourceLoad.Bytes) fetchedNewData() }
    }

    /** One request, answered as MapLibre's own client would: the body with its cache headers, or why not. */
    private suspend fun fetch(
        url: String,
        priorEtag: String?,
        priorModified: Instant?,
        priorData: ByteArray?,
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
        // As MapLibre reads Cache-Control (http_header.cpp): no-cache does not hold a copy back.
        val mustRevalidate = response.cacheControl().any { it == "must-revalidate" }
        return when (val status = response.status.value) {
            200, 206 -> {
                MapResourceLoad.Bytes(
                    bytes = response.readRawBytes(),
                    etag = response.headers[HttpHeaders.ETag],
                    mustRevalidate = mustRevalidate,
                    modified = modified,
                    expires = expires,
                )
            }

            // The map has no copy yet (it was held back to revalidate): unchanged, it is the data,
            // as MapLibre's own client answers (online_file_source.cpp).
            304 if priorData != null -> {
                MapResourceLoad.Bytes(
                    bytes = priorData,
                    etag = response.headers[HttpHeaders.ETag] ?: priorEtag,
                    mustRevalidate = mustRevalidate,
                    modified = modified ?: priorModified,
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

    private companion object {
        /** Failures that say nothing about the cached copy: the server did not answer for it. */
        val UNREACHABLE = setOf(MapResourceError.Connection, MapResourceError.Server, MapResourceError.RateLimit)
    }
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
