package to.pubky.maps.place

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.plugins.UserAgent
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okio.FileSystem
import okio.Path.Companion.toPath
import to.pubky.maps.config.AttributionLink
import to.pubky.maps.map.streetPhotoCredit
import to.pubky.maps.search.SourceAnswer
import to.pubky.maps.search.searchUserAgent
import to.pubky.maps.status.ServiceHealth
import to.pubky.maps.status.ServicePart
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

/**
 * The map's street-level photo layer. `fixtures/panoramax/style-federation.json` is the federation's
 * real map style (api.panoramax.xyz/api/map/style.json, 2026-11-28); the OpenStreetMap France and IGN
 * instances serve the same style with their own tile address.
 */
class StreetPhotoLayerTest {
    private val federationStyle =
        FileSystem.SYSTEM.read("src/commonTest/fixtures/panoramax/style-federation.json".toPath()) { readUtf8() }
    private val federation = "https://api.panoramax.xyz"
    private val federationTiles = StreetPhotoTiles("https://api.panoramax.xyz/api/map/{z}/{x}/{y}.mvt", 15, federation)
    private val servers = listOf(federation, "https://panoramax.openstreetmap.fr")

    @Test
    fun theStyleIsAskedUnderTheServersApi() {
        assertEquals("https://api.panoramax.xyz/api/map/style.json", panoramaxStyleUrl(federation))
        // A server address with a path keeps it, as an instance behind a proxy.
        assertEquals("https://example.org/panoramax/api/map/style.json", panoramaxStyleUrl("https://example.org/panoramax/"))
    }

    @Test
    fun theFederationsStyleNamesItsTilesAndTheirLastZoom() {
        assertEquals(federationTiles, parsePanoramaxStyle(federationStyle, federation))
    }

    @Test
    fun aStyleWithoutPhotoTilesIsRefused() {
        val noSequences = """{"sources":{"s":{"type":"vector","tiles":["https://a.test/{z}/{x}/{y}.mvt"]}},"layers":[]}"""
        val raster = style(type = "raster")
        val noPlaceholders = style(tiles = """["https://a.test/tiles.mvt"]""")
        val appScheme = style(tiles = """["file:///sdcard/{z}/{x}/{y}.mvt"]""")
        for (answer in listOf(noSequences, raster, noPlaceholders, appScheme, "<html>Welcome</html>")) {
            assertFailsWith<Exception>(answer) { parsePanoramaxStyle(answer, federation) }
        }
    }

    @Test
    fun theFirstUsableTileAddressIsTakenAndAMissingLastZoomIsPanoramaxs() {
        val tiles =
            parsePanoramaxStyle(
                style(tiles = """["file:///x/{z}/{x}/{y}", "https://b.test/{z}/{x}/{y}.mvt"]""", maxZoom = null),
                "https://b.test",
            )
        assertEquals(StreetPhotoTiles("https://b.test/{z}/{x}/{y}.mvt", 15, "https://b.test"), tiles)
    }

    @Test
    fun theMapCreditsPanoramaxAtTheServerWhoseTilesItDraws() {
        assertEquals(AttributionLink("Panoramax", federation), streetPhotoCredit(federationTiles))
    }

    private val requests = mutableListOf<String>()

    /** Serves by host: "ok" the federation's style, the others as named. */
    private fun panoramax(behaviour: (host: String) -> String = { "ok" }): HttpStreetPhotoTiles {
        val engine =
            MockEngine { request ->
                requests += "${request.url.host}${request.url.encodedPath} ${request.headers[HttpHeaders.UserAgent]}"
                when (behaviour(request.url.host)) {
                    "ok" -> respond(federationStyle, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                    "page" -> respond("<html>Welcome</html>", HttpStatusCode.OK)
                    "500" -> respondError(HttpStatusCode.InternalServerError)
                    else -> throw okio.IOException("connection refused")
                }
            }
        return HttpStreetPhotoTiles(HttpClient(engine) { install(UserAgent) { agent = searchUserAgent("1.2.3") } })
    }

    @Test
    fun theFirstServerAnswersWithTheAppsUserAgent() =
        runTest {
            val answer = assertIs<SourceAnswer.Answered<StreetPhotoTiles>>(panoramax().tiles(servers))
            assertEquals(federationTiles, answer.value)
            assertEquals(listOf("api.panoramax.xyz/api/map/style.json PubkyMaps/1.2.3"), requests)
        }

    @Test
    fun aFailingServerHandsOverToTheNext() =
        runTest {
            val list = listOf("https://down.test", "https://broken.test", "https://page.test", "https://up.test")
            val source =
                panoramax { host ->
                    when (host) {
                        "down.test" -> "refused"
                        "broken.test" -> "500"
                        "page.test" -> "page"
                        else -> "ok"
                    }
                }
            val answer = assertIs<SourceAnswer.Answered<StreetPhotoTiles>>(source.tiles(list))
            assertEquals("https://up.test", answer.value.server)
            assertEquals(4, requests.size)
            assertEquals(SourceAnswer.Unreachable, panoramax { "refused" }.tiles(list))
        }

    @Test
    fun theLayerAsksOnceForAListAndDrawsTheAnswer() =
        runTest {
            var asked = 0
            val layer =
                StreetPhotoLayer({
                    asked++
                    SourceAnswer.Answered(federationTiles, federation)
                }, backgroundScope)
            assertEquals(StreetPhotoLayerState.Off, layer.state.value)
            layer.show(servers)
            assertEquals(StreetPhotoLayerState.Finding, layer.state.value)
            runCurrent()
            assertEquals(StreetPhotoLayerState.Shown(federationTiles), layer.state.value)
            // Off and on again, or shown again after a trip to Settings: the answer is kept.
            layer.hide()
            assertEquals(StreetPhotoLayerState.Off, layer.state.value)
            layer.show(servers)
            runCurrent()
            assertEquals(StreetPhotoLayerState.Shown(federationTiles), layer.state.value)
            assertEquals(1, asked)
            // An edited server list is asked again.
            layer.show(servers.reversed())
            runCurrent()
            assertEquals(2, asked)
        }

    @Test
    fun anOutageIsNamedOnTheMapUntilAServerAnswersOrTheLayerIsOff() =
        runTest {
            val health = ServiceHealth()
            var answer: SourceAnswer<StreetPhotoTiles> = SourceAnswer.Unreachable
            var asked = 0
            val layer =
                StreetPhotoLayer({
                    asked++
                    answer
                }, backgroundScope, health)
            layer.show(servers)
            runCurrent()
            assertEquals(StreetPhotoLayerState.Unreachable, layer.state.value)
            assertEquals(setOf(ServicePart.StreetPhotos), health.missing.value)
            // Try again.
            answer = SourceAnswer.Answered(federationTiles, federation)
            layer.show(servers)
            runCurrent()
            assertEquals(2, asked)
            assertEquals(StreetPhotoLayerState.Shown(federationTiles), layer.state.value)
            assertEquals(emptySet(), health.missing.value)
            // Switched off while no server answers: nothing on the map is missing any more.
            answer = SourceAnswer.Unreachable
            layer.show(listOf("https://down.test"))
            runCurrent()
            assertEquals(setOf(ServicePart.StreetPhotos), health.missing.value)
            layer.hide()
            assertEquals(emptySet(), health.missing.value)
        }

    @Test
    fun aListThatAnsweredBeforeTakesBackTheOutageOfAnotherList() =
        runTest {
            val health = ServiceHealth()
            val layer =
                StreetPhotoLayer({ list ->
                    if (list == servers) SourceAnswer.Answered(federationTiles, federation) else SourceAnswer.Unreachable
                }, backgroundScope, health)
            layer.show(servers)
            runCurrent()
            layer.show(listOf("https://down.test"))
            runCurrent()
            assertEquals(setOf(ServicePart.StreetPhotos), health.missing.value)
            // The user puts the list back: its kept answer draws, and nothing is missing any more.
            layer.show(servers)
            assertEquals(StreetPhotoLayerState.Shown(federationTiles), layer.state.value)
            assertEquals(emptySet(), health.missing.value)
        }

    @Test
    fun theLayerLeavesAPlacePagesOutageToThePage() =
        runTest {
            val health = ServiceHealth()
            health.failed(ServicePart.StreetPhotos)
            val layer = StreetPhotoLayer({ SourceAnswer.Answered(federationTiles, federation) }, backgroundScope, health)
            layer.show(servers)
            runCurrent()
            layer.hide()
            assertEquals(setOf(ServicePart.StreetPhotos), health.missing.value)
        }

    @Test
    fun switchedOffWhileAskingDrawsNothingAndAFailureOutsideTheServersIsUnreachable() =
        runTest {
            val pending = CompletableDeferred<SourceAnswer<StreetPhotoTiles>>()
            var fail = false
            var asked = 0
            val layer =
                StreetPhotoLayer({
                    asked++
                    if (fail) error("settings unreadable")
                    pending.await()
                }, backgroundScope)
            layer.show(servers)
            runCurrent()
            // Shown again with the same list while asking: one question.
            layer.show(servers)
            runCurrent()
            assertEquals(1, asked)
            layer.hide()
            pending.complete(SourceAnswer.Answered(federationTiles, federation))
            runCurrent()
            assertEquals(StreetPhotoLayerState.Off, layer.state.value)
            fail = true
            layer.show(listOf("https://other.test"))
            runCurrent()
            assertEquals(StreetPhotoLayerState.Unreachable, layer.state.value)
        }

    private fun style(
        type: String = "vector",
        tiles: String = """["https://a.test/{z}/{x}/{y}.mvt"]""",
        maxZoom: Int? = 14,
    ): String =
        """{"sources":{"geovisio":{"type":"$type","tiles":$tiles${maxZoom?.let { ",\"maxzoom\":$it" } ?: ""}}},
           "layers":[{"id":"l","type":"line","source":"geovisio","source-layer":"sequences"}]}"""
}
