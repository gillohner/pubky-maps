package to.pubky.maps.search

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.plugins.UserAgent
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class HttpPlaceSearchTest {
    private class FakeClock(
        var now: Instant = Instant.fromEpochSeconds(1_800_000_000),
    ) : Clock {
        override fun now(): Instant = now
    }

    private val json = headersOf(HttpHeaders.ContentType, "application/json")
    private val requests = ArrayList<String>()
    private val userAgents = ArrayList<String?>()

    /** Serves by host: "ok" hosts answer the Kunsthaus fixture, others as [behaviour] says. */
    private fun search(
        photon: List<String>,
        pinakes: List<String>,
        clock: FakeClock = FakeClock(),
        behaviour: (host: String) -> String = { "ok" },
    ): HttpPlaceSearch {
        val engine =
            MockEngine { request ->
                requests += request.url.toString()
                userAgents += request.headers[HttpHeaders.UserAgent]
                when (behaviour(request.url.host)) {
                    "ok" -> {
                        val body =
                            if (request.url.encodedPath.endsWith(
                                    "/api",
                                )
                            ) {
                                Fixtures.read("kunsthaus.photon.json")
                            } else {
                                Fixtures.read("kunsthaus.pinakes.json")
                            }
                        respond(body, HttpStatusCode.OK, json)
                    }

                    "500" -> {
                        respondError(HttpStatusCode.InternalServerError)
                    }

                    "429" -> {
                        respond("{\"error\":\"slow down\"}", HttpStatusCode.TooManyRequests, headersOf(HttpHeaders.RetryAfter, "30"))
                    }

                    "html" -> {
                        respond("<html>blocked</html>", HttpStatusCode.OK)
                    }

                    else -> {
                        throw okio.IOException("connection refused")
                    }
                }
            }
        val client = HttpClient(engine) { install(UserAgent) { agent = searchUserAgent("1.2.3") } }
        return HttpPlaceSearch(client, { photon }, { pinakes }, { "de" }, clock)
    }

    @Test
    fun bothSourcesAnswerWithTheAppsUserAgent() =
        runTest {
            val s = search(listOf("https://photon.test"), listOf("https://pinakes.test"))
            val photon = s.photon("kunsthaus", Fixtures.zurich)
            val pinakes = s.pinakes("kunsthaus", Fixtures.zurich, viewer = null)
            assertEquals("Kunsthaus", (photon as SourceAnswer.Answered).value.first().title)
            assertEquals("https://photon.test", photon.server)
            assertEquals("Kunsthaus", (pinakes as SourceAnswer.Answered).value.first().name)
            assertEquals(listOf<String?>("PubkyMaps/1.2.3", "PubkyMaps/1.2.3"), userAgents)
            assertTrue(requests[0].startsWith("https://photon.test/api?q=kunsthaus&lat=47.3769&lon=8.5417&zoom=13&limit=10&lang=de"))
            assertTrue(requests[1].startsWith("https://pinakes.test/v1/search?q=kunsthaus&type=Place"))
        }

    @Test
    fun aFailingServerHandsOverToTheNextOne() =
        runTest {
            for (failure in listOf("500", "down", "html")) {
                requests.clear()
                val s = search(listOf("https://bad.test", "https://good.test"), emptyList()) { if (it == "bad.test") failure else "ok" }
                val answer = s.photon("kunsthaus", Fixtures.zurich)
                assertEquals("https://good.test", (answer as SourceAnswer.Answered).server, failure)
                assertEquals(listOf("bad.test", "good.test"), requests.map { it.substringAfter("://").substringBefore('/') })
            }
        }

    @Test
    fun everyServerFailingIsUnreachable() =
        runTest {
            val s = search(listOf("https://a.test"), listOf("https://b.test", "https://c.test")) { "down" }
            assertIs<SourceAnswer.Unreachable>(s.photon("kunsthaus", Fixtures.zurich))
            assertIs<SourceAnswer.Unreachable>(s.pinakes("kunsthaus", Fixtures.zurich, null))
            assertEquals(3, requests.size)
        }

    @Test
    fun aServerThatSaidTooManyRequestsIsLeftAloneForItsRetryAfter() =
        runTest {
            val clock = FakeClock()
            var busy = true
            val s =
                search(emptyList(), listOf("https://busy.test", "https://spare.test"), clock) {
                    if (it == "busy.test" &&
                        busy
                    ) {
                        "429"
                    } else {
                        "ok"
                    }
                }
            assertEquals("https://spare.test", (s.pinakes("zoo", Fixtures.zurich, null) as SourceAnswer.Answered).server)
            busy = false
            requests.clear()
            clock.now += 20.seconds
            assertEquals("https://spare.test", (s.pinakes("zoo", Fixtures.zurich, null) as SourceAnswer.Answered).server)
            assertEquals(1, requests.size)
            clock.now += 11.seconds
            assertEquals("https://busy.test", (s.pinakes("zoo", Fixtures.zurich, null) as SourceAnswer.Answered).server)
        }

    @Test
    fun theServerListIsReadAtEverySearch() =
        runTest {
            var servers = listOf("https://one.test")
            val engine =
                MockEngine { request ->
                    requests += request.url.host
                    respond(Fixtures.read("zoo.photon.json"), HttpStatusCode.OK, json)
                }
            val s = HttpPlaceSearch(HttpClient(engine), { servers }, { emptyList() }, { "en" })
            s.photon("zoo", Fixtures.zurich)
            servers = listOf("https://two.test")
            s.photon("zoo", Fixtures.zurich)
            assertEquals(listOf("one.test", "two.test"), requests)
        }
}
