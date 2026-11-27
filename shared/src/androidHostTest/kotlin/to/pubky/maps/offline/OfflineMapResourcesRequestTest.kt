package to.pubky.maps.offline

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import org.maplibre.compose.resource.MapResourceKind
import org.maplibre.compose.resource.MapResourceLoad
import org.maplibre.compose.resource.MapResourceLoadRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/**
 * [OfflineMapResources] as MapLibre calls it: through [org.maplibre.compose.resource.MapResourceProvider.load]
 * with a [MapResourceLoadRequest]. `OfflineMapResourcesTest` (common) tests what the provider does with
 * a request's fields; this checks that each field it reads (usage, url, range and the cached copy's etag
 * and dates) reaches it, so the fresh cached style of a map recreated during a download is answered from
 * its own dates instead of asking the server.
 */
class OfflineMapResourcesRequestTest {
    private val now = Instant.fromEpochSeconds(1_800_000_000)
    private val clock =
        object : Clock {
            override fun now() = now
        }
    private val modified = Instant.parse("2026-11-22T18:54:46Z")

    private fun resources(
        engine: MockEngine,
        fetchedNewData: () -> Unit = {},
    ) = OfflineMapResources(
        { HttpClient(engine) { expectSuccess = false } },
        downloading = { true },
        clock = clock,
        fetchedNewData = fetchedNewData,
    )

    /**
     * A request like the ones MapLibre makes. maplibre-compose 0.20.0 keeps the constructor
     * internal (only the library makes requests), so it is called through reflection; its value-class
     * parameters are plain strings on the JVM. The getters are checked against what was passed, so a
     * library update that reorders or adds parameters fails here instead of testing a wrong request.
     */
    private fun mapLibreRequest(
        url: String,
        kind: MapResourceKind,
        usage: MapResourceLoadRequest.Usage,
        range: LongRange? = null,
        priorEtag: String? = null,
        priorModified: Instant? = null,
        priorExpires: Instant? = null,
    ): MapResourceLoadRequest {
        val text = String::class.java
        val constructor =
            MapResourceLoadRequest::class.java.getDeclaredConstructor(
                text,
                text,
                text,
                text,
                text,
                text,
                text,
                LongRange::class.java,
                text,
                Instant::class.java,
                Instant::class.java,
                ByteArray::class.java,
            )
        constructor.setAccessible(true)
        val request =
            constructor.newInstance(
                url,
                kind.value,
                // Before MapLibre resolved it: the provider loads [url], never this.
                "$url?requested",
                MapResourceLoadRequest.LoadingMethod.All.value,
                MapResourceLoadRequest.Priority.Regular.value,
                usage.value,
                MapResourceLoadRequest.StoragePolicy.Permanent.value,
                range,
                priorEtag,
                priorModified,
                priorExpires,
                null,
            )
        assertEquals(url, request.url)
        assertEquals(kind, request.kind)
        assertEquals(usage, request.usage)
        assertEquals(range, request.range)
        assertEquals(priorEtag, request.priorEtag)
        assertEquals(priorModified, request.priorModified)
        assertEquals(priorExpires, request.priorExpires)
        return request
    }

    @Test
    fun aMapsRequestForAFreshCachedStyleIsAnsweredFromItsOwnDates() =
        runTest {
            // The map server is unreachable (killed with a connection open).
            var asked = 0
            val unreachable =
                MockEngine {
                    asked++
                    throw IOException("unexpected end of stream")
                }
            val expires = now + 1.hours
            val request =
                mapLibreRequest(
                    url = "https://tiles.example.org/styles/liberty",
                    kind = MapResourceKind.Style,
                    usage = MapResourceLoadRequest.Usage.Online,
                    priorEtag = "W/\"abc\"",
                    priorModified = modified,
                    priorExpires = expires,
                )
            val load = assertIs<MapResourceLoad.NotModified>(resources(unreachable).load(request))
            assertEquals(modified, load.modified)
            assertEquals(expires, load.expires)
            assertEquals(0, asked)
        }

    @Test
    fun aDownloadsRequestAsksTheServerWithItsCachedCopyAndRange() =
        runTest {
            var fetched = 0
            var ifNoneMatch: String? = null
            var ifModifiedSince: String? = null
            var range: String? = null
            var asked: String? = null
            val engine =
                MockEngine { request ->
                    asked = request.url.toString()
                    ifNoneMatch = request.headers[HttpHeaders.IfNoneMatch]
                    ifModifiedSince = request.headers[HttpHeaders.IfModifiedSince]
                    range = request.headers[HttpHeaders.Range]
                    respond(byteArrayOf(1), HttpStatusCode.PartialContent)
                }
            // An update check's request for a tile whose cached copy is still fresh asks anyway: it
            // is how the check finds new map data.
            val tile = "https://tiles.example.org/planet/14/8580/5738.pbf"
            val request =
                mapLibreRequest(
                    url = tile,
                    kind = MapResourceKind.Tile,
                    usage = MapResourceLoadRequest.Usage.Offline,
                    range = 0L..99L,
                    priorEtag = "W/\"abc\"",
                    priorModified = modified,
                    priorExpires = now + 1.hours,
                )
            assertIs<MapResourceLoad.Bytes>(resources(engine) { fetched++ }.load(request))
            assertEquals(tile, asked)
            assertEquals("W/\"abc\"", ifNoneMatch)
            assertEquals("Sun, 22 Nov 2026 18:54:46 GMT", ifModifiedSince)
            assertEquals("bytes=0-99", range)
            assertEquals(1, fetched)
        }
}
