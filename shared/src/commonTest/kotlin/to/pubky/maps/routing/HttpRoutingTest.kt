package to.pubky.maps.routing

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.plugins.UserAgent
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import to.pubky.maps.links.GeoPoint
import to.pubky.maps.search.Fixtures
import to.pubky.maps.search.searchUserAgent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class HttpRoutingTest {
    private val json = headersOf(HttpHeaders.ContentType, "application/json")
    private val requests = ArrayList<String>()
    private val times = ArrayList<Long>()
    private val from = GeoPoint(47.3779, 8.5403)
    private val to = GeoPoint(47.3702, 8.5486)

    /** Serves by host: "ok" answers the Zürich route, the others as named. */
    private fun TestScope.routing(
        servers: List<String>,
        behaviour: (host: String) -> String = { "ok" },
    ): HttpRouting {
        val engine =
            MockEngine { request ->
                requests += "${request.method.value} ${request.url} ${request.headers[HttpHeaders.UserAgent]}"
                times += currentTime
                when (behaviour(request.url.host)) {
                    "ok" -> respond(Fixtures.read("hauptbahnhof-kunsthaus.valhalla.json"), HttpStatusCode.OK, json)
                    "far" -> respond("""{"error_code":154,"error":"too far","status_code":400}""", HttpStatusCode.BadRequest, json)
                    "400html" -> respond("<html>bad</html>", HttpStatusCode.BadRequest)
                    "500" -> respondError(HttpStatusCode.InternalServerError)
                    else -> throw okio.IOException("connection refused")
                }
            }
        val client = HttpClient(engine) { install(UserAgent) { agent = searchUserAgent("1.2.3") } }
        return HttpRouting(client, { servers }, timeSource = testScheduler.timeSource)
    }

    @Test
    fun aRouteIsPostedToTheFirstServerWithTheAppsUserAgent() =
        runTest {
            val answer = routing(listOf("https://valhalla.test")).route(from, to, TravelMode.Car)
            val found = assertIs<RouteAnswer.Found>(answer)
            assertEquals("https://valhalla.test", found.server)
            assertEquals(94, found.route.points.size)
            assertEquals(listOf("${HttpMethod.Post.value} https://valhalla.test/route PubkyMaps/1.2.3"), requests)
        }

    @Test
    fun aFailingServerHandsOverToTheNext() =
        runTest {
            val r =
                routing(listOf("https://down.test", "https://broken.test", "https://up.test")) {
                    if (it ==
                        "up.test"
                    ) {
                        "ok"
                    } else if (it == "broken.test") {
                        "500"
                    } else {
                        "down"
                    }
                }
            val found = assertIs<RouteAnswer.Found>(r.route(from, to, TravelMode.Walk))
            assertEquals("https://up.test", found.server)
            assertEquals(3, requests.size)
        }

    @Test
    fun aRefusedRouteIsTheAnswerAndNoOtherServerIsAsked() =
        runTest {
            val r = routing(listOf("https://a.test", "https://b.test")) { if (it == "a.test") "far" else "ok" }
            assertEquals(RouteAnswer.NoRoute(NoRouteReason.TooFar), r.route(from, to, TravelMode.Walk))
            assertEquals(1, requests.size)
        }

    @Test
    fun aBadRequestWithoutAValhallaErrorIsAFailingServer() =
        runTest {
            val r = routing(listOf("https://proxy.test", "https://b.test")) { if (it == "proxy.test") "400html" else "ok" }
            assertIs<RouteAnswer.Found>(r.route(from, to, TravelMode.Car))
            assertEquals(2, requests.size)
        }

    @Test
    fun noServerAnsweringIsUnreachable() =
        runTest {
            val r = routing(listOf("https://a.test", "https://b.test")) { "down" }
            assertEquals(RouteAnswer.Unreachable, r.route(from, to, TravelMode.Car))
        }

    @Test
    fun requestsToOneServerAreASecondApart() =
        runTest {
            val r = routing(listOf("https://valhalla.test"))
            r.route(from, to, TravelMode.Car)
            r.route(from, to, TravelMode.Bike)
            r.route(from, to, TravelMode.Walk)
            assertEquals(listOf(0L, 1_000L, 2_000L), times)
        }
}
