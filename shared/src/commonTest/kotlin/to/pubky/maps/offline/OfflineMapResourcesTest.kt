package to.pubky.maps.offline

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.UserAgent
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import org.maplibre.compose.resource.MapResourceError
import org.maplibre.compose.resource.MapResourceLoad
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

class OfflineMapResourcesTest {
    private val now = Instant.fromEpochSeconds(1_800_000_000)
    private val clock =
        object : Clock {
            override fun now() = now
        }
    private val tile = "https://tiles.example.org/planet/14/8580/5738.pbf"

    private fun resources(
        downloading: Boolean = true,
        engine: MockEngine,
        onClientMade: () -> Unit = {},
        fetchedNewData: () -> Unit = {},
    ) = OfflineMapResources(
        {
            onClientMade()
            HttpClient(engine) {
                expectSuccess = false
                install(UserAgent) { agent = "PubkyMaps/1.0" }
            }
        },
        downloading = { downloading },
        clock = clock,
        fetchedNewData = fetchedNewData,
    )

    @Test
    fun theClientIsMadeByTheFirstRequestNotAtStart() =
        runTest {
            var made = 0
            val resources = resources(engine = MockEngine { respond("") }, onClientMade = { made++ })
            // Set up at app start and asked about every request of the map: no client yet.
            assertTrue(resources.accepts(tile))
            assertEquals(0, made)
            resources.load(offline = true, url = tile)
            resources.load(offline = false, url = tile)
            assertEquals(1, made)
        }

    @Test
    fun aDownloadsRequestThatGetsDataSaysSo() =
        runTest {
            var fetched = 0
            val ok = resources(engine = MockEngine { respond(byteArrayOf(1)) }) { fetched++ }
            ok.load(offline = true, url = tile)
            assertEquals(1, fetched)
            // The map's own requests, an unchanged copy and a failure fetched no new map data.
            ok.load(offline = false, url = tile)
            resources(engine = MockEngine { respond("", HttpStatusCode.NotModified) }) { fetched++ }.load(offline = true, url = tile)
            resources(engine = MockEngine { respond("", HttpStatusCode.BadGateway) }) { fetched++ }.load(offline = true, url = tile)
            assertEquals(1, fetched)
        }

    @Test
    fun takesOverRequestsOnlyWhileADownloadRuns() {
        val engine = MockEngine { respond("") }
        assertTrue(resources(downloading = true, engine = engine).accepts(tile))
        assertFalse(resources(downloading = true, engine = engine).accepts("asset://styles/local.json"))
        assertFalse(resources(downloading = false, engine = engine).accepts(tile))
    }

    @Test
    fun aTileComesWithItsCacheHeadersAndTheAppsUserAgent() =
        runTest {
            var userAgent: String? = null
            val engine =
                MockEngine { request ->
                    userAgent = request.headers[HttpHeaders.UserAgent]
                    respond(
                        byteArrayOf(1, 2, 3),
                        HttpStatusCode.OK,
                        headersOf(
                            HttpHeaders.ETag to listOf("W/\"abc\""),
                            HttpHeaders.CacheControl to listOf("max-age=86400", "public"),
                            HttpHeaders.LastModified to listOf("Sun, 22 Nov 2026 18:54:46 GMT"),
                        ),
                    )
                }
            val load = assertIs<MapResourceLoad.Bytes>(resources(engine = engine).load(offline = true, url = tile))
            assertContentEquals(byteArrayOf(1, 2, 3), load.bytes)
            assertEquals("W/\"abc\"", load.etag)
            assertFalse(load.mustRevalidate)
            assertEquals(now.epochSeconds + 86_400, load.expires?.epochSeconds)
            assertEquals(Instant.parse("2026-11-22T18:54:46Z"), load.modified)
            assertEquals("PubkyMaps/1.0", userAgent)
        }

    @Test
    fun aCachedCopyIsRevalidated() =
        runTest {
            var ifNoneMatch: String? = null
            var ifModifiedSince: String? = null
            val engine =
                MockEngine { request ->
                    ifNoneMatch = request.headers[HttpHeaders.IfNoneMatch]
                    ifModifiedSince = request.headers[HttpHeaders.IfModifiedSince]
                    respond("", HttpStatusCode.NotModified, headersOf(HttpHeaders.Expires, "Fri, 21 Nov 2036 02:54:58 GMT"))
                }
            val load =
                resources(engine = engine).load(
                    offline = false,
                    url = tile,
                    priorEtag = "W/\"abc\"",
                    priorModified = Instant.parse("2026-11-22T18:54:46Z"),
                )
            assertEquals(Instant.parse("2036-11-21T02:54:58Z"), assertIs<MapResourceLoad.NotModified>(load).expires)
            assertEquals("W/\"abc\"", ifNoneMatch)
            assertEquals("Sun, 22 Nov 2026 18:54:46 GMT", ifModifiedSince)
        }

    @Test
    fun failuresAreAnsweredAsMapLibreWouldTakeThem() =
        runTest {
            fun failure(
                status: HttpStatusCode,
                headers: Map<String, String> = emptyMap(),
            ) = MockEngine { respond("", status, headersOf(*headers.map { it.key to listOf(it.value) }.toTypedArray())) }

            suspend fun load(engine: MockEngine) = resources(engine = engine).load(offline = true, url = tile)

            assertIs<MapResourceLoad.NoContent>(load(failure(HttpStatusCode.NoContent)))
            assertEquals(MapResourceError.NotFound, assertIs<MapResourceLoad.Failed>(load(failure(HttpStatusCode.NotFound))).reason)
            val busy =
                assertIs<MapResourceLoad.Failed>(load(failure(HttpStatusCode.TooManyRequests, mapOf(HttpHeaders.RetryAfter to "30"))))
            assertEquals(MapResourceError.RateLimit, busy.reason)
            assertEquals(now.epochSeconds + 30, busy.retryAfter?.epochSeconds)
            assertEquals(MapResourceError.Server, assertIs<MapResourceLoad.Failed>(load(failure(HttpStatusCode.BadGateway))).reason)
            assertEquals(MapResourceError.Other, assertIs<MapResourceLoad.Failed>(load(failure(HttpStatusCode.Forbidden))).reason)
            val offline = MockEngine { throw IOException("Unable to resolve host") }
            val connection = assertIs<MapResourceLoad.Failed>(load(offline))
            assertEquals(MapResourceError.Connection, connection.reason)
            assertEquals("Unable to resolve host", connection.message)
        }

    @Test
    fun aDownloadSendsAtMostFourRequestsAtATimeTheMapAsManyAsItNeeds() =
        runTest {
            var running = 0
            var most = 0
            val release = CompletableDeferred<Unit>()
            // The engine runs on the test's thread, so the counts need no locking.
            val engine =
                MockEngine(
                    MockEngineConfig().apply {
                        dispatcher = UnconfinedTestDispatcher(testScheduler)
                        addHandler {
                            running++
                            most = maxOf(most, running)
                            release.await()
                            running--
                            respond("tile")
                        }
                    },
                )
            val resources = resources(engine = engine)
            val download = (1..12).map { async { resources.load(offline = true, url = "$tile?$it") } }
            runCurrent()
            assertEquals(OfflineLimits.PARALLEL_REQUESTS, most)
            val map = (1..6).map { async { resources.load(offline = false, url = "$tile?map$it") } }
            runCurrent()
            assertEquals(OfflineLimits.PARALLEL_REQUESTS + 6, most)
            release.complete(Unit)
            (download + map).awaitAll().forEach { assertIs<MapResourceLoad.Bytes>(it) }
        }
}
