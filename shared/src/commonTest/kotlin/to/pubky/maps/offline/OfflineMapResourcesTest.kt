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
import org.maplibre.compose.resource.MapResourceKind
import org.maplibre.compose.resource.MapResourceLoad
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class OfflineMapResourcesTest {
    private val now = Instant.fromEpochSeconds(1_800_000_000)
    private val clock =
        object : Clock {
            override fun now() = now
        }
    private val tile = "https://tiles.example.org/planet/14/8580/5738.pbf"
    private val style = "https://tiles.example.org/styles/liberty"

    private fun resources(
        downloading: Boolean = true,
        engine: MockEngine,
        onClientMade: () -> Unit = {},
        offlineStyles: Set<String> = setOf(style),
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
        keepsCopy = { it in offlineStyles },
    )

    @Test
    fun theClientIsMadeByTheFirstRequestNotAtStart() =
        runTest {
            var made = 0
            val resources = resources(engine = MockEngine { respond("") }, onClientMade = { made++ })
            // Set up at app start and asked about every request of the map: no client yet.
            assertTrue(resources.accepts(tile, MapResourceKind.Tile))
            assertTrue(resources.accepts(style, MapResourceKind.Style))
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
    fun takesOverTheMapsStylesAlwaysAndEveryOtherRequestOnlyWhileADownloadRuns() {
        val engine = MockEngine { respond("") }
        val downloading = resources(downloading = true, engine = engine)
        val idle = resources(downloading = false, engine = engine)
        for (kind in listOf(MapResourceKind.Tile, MapResourceKind.Source, MapResourceKind.Glyphs, MapResourceKind.SpriteImage)) {
            assertTrue(downloading.accepts(tile, kind), "$kind while downloading")
            assertFalse(idle.accepts(tile, kind), "$kind without a download")
        }
        assertTrue(idle.accepts(style, MapResourceKind.Style))
        assertTrue(idle.accepts("http://127.0.0.1:18493/light.json", MapResourceKind.Style))
        // Only what comes from a server.
        assertFalse(downloading.accepts("asset://styles/local.json", MapResourceKind.Style))
        assertFalse(idle.accepts("asset://styles/local.json", MapResourceKind.Style))
    }

    /** A server that cannot answer, in each way: no connection to it, a server error, too busy. */
    private fun unreachable(asked: () -> Unit = {}): Map<String, MockEngine> =
        mapOf(
            "no connection" to
                MockEngine {
                    asked()
                    throw IOException("Failed to connect to tiles.example.org")
                },
            "502" to
                MockEngine {
                    asked()
                    respond("", HttpStatusCode.BadGateway)
                },
            "429" to
                MockEngine {
                    asked()
                    respond("", HttpStatusCode.TooManyRequests)
                },
        )

    @Test
    fun anOfflineAreasExpiredStyleWhoseServerCannotBeReachedKeepsTheCopyTheMapDrew() =
        runTest {
            // OpenFreeMap's style a day after it was fetched (max-age=86400); MapLibre gave the map
            // the expired copy and asks the server whether it changed.
            val modified = Instant.parse("2026-11-22T18:54:46Z")
            val expired = now - 1.hours
            var asked = 0
            for ((way, engine) in unreachable { asked++ }) {
                val load =
                    resources(downloading = false, engine = engine).load(
                        offline = false,
                        url = style,
                        priorModified = modified,
                        priorExpires = expired,
                    )
                // Unchanged with its own dates: still expired, so the next load asks again.
                val kept = assertIs<MapResourceLoad.NotModified>(load, way)
                assertEquals(modified, kept.modified)
                assertEquals(expired, kept.expires)
            }
            assertEquals(3, asked)
            // An etag alone says MapLibre has a copy too.
            for ((way, engine) in unreachable()) {
                val load = resources(engine = engine).load(offline = false, url = style, priorEtag = "W/\"abc\"")
                assertIs<MapResourceLoad.NotModified>(load, way)
            }
        }

    @Test
    fun anOfflineAreasStyleHeldBackToRevalidateIsGivenFromTheCacheWhenItsServerCannotBeReached() =
        runTest {
            // A style served must-revalidate: MapLibre gives the map no copy before the server said
            // it is unchanged, and hands it to the request instead.
            val cached = "{\"version\": 8}".encodeToByteArray()
            val modified = Instant.parse("2026-11-22T18:54:46Z")
            for ((way, engine) in unreachable()) {
                val load =
                    resources(downloading = false, engine = engine).load(
                        offline = false,
                        url = style,
                        priorEtag = "W/\"abc\"",
                        priorModified = modified,
                        priorData = cached,
                    )
                val copy = assertIs<MapResourceLoad.Bytes>(load, way)
                assertContentEquals(cached, copy.bytes)
                assertEquals("W/\"abc\"", copy.etag)
                assertTrue(copy.mustRevalidate)
                assertEquals(modified, copy.modified)
                assertEquals(null, copy.expires)
            }
        }

    @Test
    fun aServersAnswerAboutTheResourceAndARequestWithoutACopyStillFail() =
        runTest {
            val cached = "{\"version\": 8}".encodeToByteArray()

            suspend fun load(
                status: HttpStatusCode,
                offline: Boolean = false,
                withCopy: Boolean = true,
            ) = resources(engine = MockEngine { respond("", status) }).load(
                offline = offline,
                url = style,
                priorEtag = if (withCopy) "W/\"abc\"" else null,
                priorExpires = if (withCopy) now - 1.hours else null,
                priorData = if (withCopy) cached else null,
            )
            // The server says the style is gone or refused: the map hands over to the next server.
            assertEquals(MapResourceError.NotFound, assertIs<MapResourceLoad.Failed>(load(HttpStatusCode.NotFound)).reason)
            assertEquals(MapResourceError.Other, assertIs<MapResourceLoad.Failed>(load(HttpStatusCode.Forbidden)).reason)
            // Not an offline area's style: the map hands over to the next server, whose tiles it can fetch.
            val online =
                resources(engine = MockEngine { respond("", HttpStatusCode.BadGateway) }, offlineStyles = emptySet()).load(
                    offline = false,
                    url = style,
                    priorEtag = "W/\"abc\"",
                    priorExpires = now - 1.hours,
                    priorData = cached,
                )
            assertEquals(MapResourceError.Server, assertIs<MapResourceLoad.Failed>(online).reason)
            // Nothing cached to keep.
            assertEquals(
                MapResourceError.Server,
                assertIs<MapResourceLoad.Failed>(load(HttpStatusCode.BadGateway, withCopy = false)).reason,
            )
            val offline = MockEngine { throw IOException("Unable to resolve host") }
            val none = resources(engine = offline).load(offline = false, url = style)
            assertEquals(MapResourceError.Connection, assertIs<MapResourceLoad.Failed>(none).reason)
            // A download's request is not the map's: it fails as before.
            assertEquals(MapResourceError.Server, assertIs<MapResourceLoad.Failed>(load(HttpStatusCode.BadGateway, offline = true)).reason)
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
    fun aCopyHeldBackToRevalidateIsTheDataWhenTheServerSaysItIsUnchanged() =
        runTest {
            val cached = "{\"version\": 8}".encodeToByteArray()
            val engine =
                MockEngine {
                    respond("", HttpStatusCode.NotModified, headersOf(HttpHeaders.CacheControl, "max-age=0, must-revalidate"))
                }
            val load =
                resources(engine = engine, offlineStyles = emptySet()).load(
                    offline = false,
                    url = style,
                    priorEtag = "W/\"abc\"",
                    priorModified = Instant.parse("2026-11-22T18:54:46Z"),
                    priorData = cached,
                )
            // MapLibre gave the map nothing yet: an answer without data would leave it without a style.
            val copy = assertIs<MapResourceLoad.Bytes>(load)
            assertContentEquals(cached, copy.bytes)
            assertEquals("W/\"abc\"", copy.etag)
            assertTrue(copy.mustRevalidate)
            assertEquals(Instant.parse("2026-11-22T18:54:46Z"), copy.modified)
            assertEquals(now, copy.expires)
            // A copy the map has already is only unchanged.
            assertIs<MapResourceLoad.NotModified>(
                resources(engine = engine).load(offline = false, url = style, priorEtag = "W/\"abc\"", priorExpires = now - 1.hours),
            )
        }

    @Test
    fun onlyMustRevalidateHoldsACopyBackAsMapLibreReadsCacheControl() =
        runTest {
            suspend fun mustRevalidate(cacheControl: String) =
                assertIs<MapResourceLoad.Bytes>(
                    resources(
                        engine = MockEngine { respond("{}", HttpStatusCode.OK, headersOf(HttpHeaders.CacheControl, cacheControl)) },
                    ).load(offline = false, url = style),
                ).mustRevalidate
            // VersaTiles serves its styles no-cache; MapLibre's own client keeps them as usable copies,
            // so the map draws one at once instead of waiting for the server at every load.
            assertFalse(mustRevalidate("no-cache"))
            assertTrue(mustRevalidate("max-age=0, must-revalidate"))
            assertFalse(mustRevalidate("max-age=86400, public"))
        }

    @Test
    fun aMapRequestWhoseCachedCopyIsFreshIsAnsweredUnchangedWithoutAskingTheServer() =
        runTest {
            // The map server is unreachable (killed with a connection open, as run 45865109025 saw).
            var asked = 0
            val unreachable =
                MockEngine {
                    asked++
                    throw IOException("unexpected end of stream")
                }
            val modified = Instant.parse("2026-11-22T18:54:46Z")
            val expires = now + 1.hours
            // The style of a map made while a download runs, which MapLibre already gave it from the cache.
            val load =
                assertIs<MapResourceLoad.NotModified>(
                    resources(engine = unreachable).load(
                        offline = false,
                        url = "https://tiles.example.org/styles/liberty",
                        priorEtag = "W/\"abc\"",
                        priorModified = modified,
                        priorExpires = expires,
                    ),
                )
            assertEquals(modified, load.modified)
            assertEquals(expires, load.expires)
            assertEquals(0, asked)
        }

    @Test
    fun aStaleCopyAndADownloadsRequestStillAskTheServer() =
        runTest {
            var asked = 0
            val engine =
                MockEngine {
                    asked++
                    respond("", HttpStatusCode.NotModified)
                }
            val resources = resources(engine = engine)
            // A map's copy that has expired is revalidated, as MapLibre's own client would.
            resources.load(offline = false, url = tile, priorEtag = "W/\"abc\"", priorExpires = now - 1.seconds)
            assertEquals(1, asked)
            // So is one with no expiry date.
            resources.load(offline = false, url = tile, priorEtag = "W/\"abc\"")
            assertEquals(2, asked)
            // A download's request always asks: it is how an update check finds new map data.
            resources.load(offline = true, url = tile, priorEtag = "W/\"abc\"", priorExpires = now + 1.hours)
            assertEquals(3, asked)
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
