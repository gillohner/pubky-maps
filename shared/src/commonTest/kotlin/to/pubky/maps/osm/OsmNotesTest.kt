package to.pubky.maps.osm

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.UserAgent
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.decodeURLQueryComponent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import to.pubky.maps.config.ServerDefaults
import to.pubky.maps.config.Service
import to.pubky.maps.links.GeoPoint
import to.pubky.maps.links.OsmId
import to.pubky.maps.links.OsmType
import to.pubky.maps.search.searchUserAgent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OsmNotesTest {
    private val schober = NoteTarget(GeoPoint(47.3722, 8.5446), "Conditorei Schober", OsmId(OsmType.Node, 270803095))
    private val pin = NoteTarget(GeoPoint(47.37689, 8.54169))

    private class Sent(
        val line: String,
        val form: Map<String, String>,
    )

    private val sent = ArrayList<Sent>()

    /** Serves by host: "ok" takes the note, the others as named. */
    private fun notes(
        servers: List<String>,
        behaviour: (host: String) -> String = { "ok" },
    ): HttpOsmNotes {
        val engine =
            MockEngine { request ->
                val body = (request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
                // A form body as Rack reads it: '+' is a space.
                val form =
                    body.split("&").associate { pair ->
                        val (k, v) = pair.split("=", limit = 2)
                        k.decodeURLQueryComponent(plusIsSpace = true) to v.decodeURLQueryComponent(plusIsSpace = true)
                    }
                sent += Sent("${request.method.value} ${request.url} ${request.headers[HttpHeaders.UserAgent]}", form)
                when (behaviour(request.url.host)) {
                    "ok" -> respond("""{"type":"Feature","properties":{"id":4711}}""", HttpStatusCode.OK, json)
                    "refused" -> respond("", HttpStatusCode.BadRequest, headersOf("Error", "Text is too long"))
                    "notFound" -> respond("<html>Not Found</html>", HttpStatusCode.NotFound)
                    "page" -> respond("<html>Welcome</html>", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/html"))
                    "empty" -> respond("", HttpStatusCode.NoContent)
                    "otherJson" -> respond("""{"type":"FeatureCollection","features":[]}""", HttpStatusCode.OK, json)
                    "slow" -> throw HttpRequestTimeoutException(request.url.toString(), 15_000)
                    "noConnect" -> throw ConnectTimeoutException("connect timeout")
                    "busy" -> respond("", HttpStatusCode.TooManyRequests)
                    "500" -> respondError(HttpStatusCode.InternalServerError)
                    else -> throw okio.IOException("connection refused")
                }
            }
        val client = HttpClient(engine) { install(UserAgent) { agent = searchUserAgent("1.2.3") } }
        return HttpOsmNotes(client) { servers }
    }

    private val json = headersOf(HttpHeaders.ContentType, "application/json")

    @Test
    fun aNoteIsPostedToTheNotesEndpointAtThePlacesPointWithTheAppsUserAgent() =
        runTest {
            val answer = notes(listOf("https://api.osm.test")).create(schober, "  Closed for good.  ")
            assertEquals(NoteAnswer.Sent, answer)
            assertEquals(listOf("POST https://api.osm.test/api/0.6/notes.json PubkyMaps/1.2.3"), sent.map { it.line })
            val form = sent.single().form
            assertEquals("47.3722", form["lat"])
            assertEquals("8.5446", form["lon"])
            assertEquals(
                "Closed for good.\n\nPlace: Conditorei Schober, https://www.openstreetmap.org/node/270803095\n\n#PubkyMaps",
                form["text"],
            )
        }

    @Test
    fun aNoteAboutAPointHasNoPlaceLine() {
        assertEquals("A bench is missing here.\n\n#PubkyMaps", noteText(pin, "A bench is missing here."))
        assertEquals("Gone.\n\nPlace: Bäckerei\n\n#PubkyMaps", noteText(pin.copy(name = "Bäckerei"), "Gone."))
    }

    @Test
    fun aFailingServerHandsOverToTheNext() =
        runTest {
            val behaviour = mapOf("a.test" to "down", "b.test" to "500", "c.test" to "busy", "d.test" to "ok")
            val answer =
                notes(listOf("https://a.test", "https://b.test", "https://c.test", "https://d.test")) {
                    behaviour.getValue(it)
                }.create(pin, "x")
            assertEquals(NoteAnswer.Sent, answer)
            assertEquals(4, sent.size)
        }

    @Test
    fun aRefusalIsTheAnswerAndNoOtherServerIsAsked() =
        runTest {
            val behaviour = mapOf("a.test" to "refused", "b.test" to "ok")
            val answer = notes(listOf("https://a.test", "https://b.test")) { behaviour.getValue(it) }.create(pin, "x")
            assertEquals(NoteAnswer.Refused("Text is too long"), answer)
            assertEquals(1, sent.size)
        }

    @Test
    fun aServerAnsweringNotAsTheApiHandsOver() =
        runTest {
            // A 4xx without the API's Error header: a wrong address or a proxy, not a refusal of the note.
            val behaviour = mapOf("a.test" to "notFound", "b.test" to "noConnect", "c.test" to "ok")
            val answer = notes(listOf("https://a.test", "https://b.test", "https://c.test")) { behaviour.getValue(it) }.create(pin, "x")
            assertEquals(NoteAnswer.Sent, answer)
            assertEquals(3, sent.size)
        }

    @Test
    fun aSuccessThatIsNotTheApisNewNoteHandsOver() =
        runTest {
            // A wrong address whose site answers every path, a proxy's empty 204, other JSON: no note was made.
            val behaviour = mapOf("a.test" to "page", "b.test" to "empty", "c.test" to "otherJson", "d.test" to "ok")
            val servers = listOf("https://a.test", "https://b.test", "https://c.test", "https://d.test")
            assertEquals(NoteAnswer.Sent, notes(servers) { behaviour.getValue(it) }.create(pin, "x"))
            assertEquals(4, sent.size)
        }

    @Test
    fun onlySuccessesThatAreNotNotesFailed() =
        runTest {
            // A server answered, so the note is not kept to go again: something there may have it.
            assertEquals(NoteAnswer.Failed, notes(listOf("https://a.test")) { "page" }.create(pin, "x"))
        }

    @Test
    fun aNoteWhoseAnswerDidNotComeIsNotSentAgainElsewhere() =
        runTest {
            // It went out: the first server may have it, so another one would make it two.
            val behaviour = mapOf("a.test" to "slow", "b.test" to "ok")
            val answer = notes(listOf("https://a.test", "https://b.test")) { behaviour.getValue(it) }.create(pin, "x")
            assertEquals(NoteAnswer.NoAnswer, answer)
            assertEquals(1, sent.size)
        }

    @Test
    fun eachNoteNotSentSaysWhy() {
        assertEquals("The server did not take the note: Text is too long.", notSentText(NoteAnswer.Refused("Text is too long")))
        assertEquals("The server did not take the note: Blocked.", notSentText(NoteAnswer.Refused("Blocked.")))
        assertEquals("Not sent: no server could be reached. Check your connection and send it again.", notSentText(NoteAnswer.Unreachable))
        assertEquals("Not sent: the server had a problem. Send it again later.", notSentText(NoteAnswer.Failed))
        assertEquals(
            "No answer in time: the note may be on the map already. Send it again only if it is not there tomorrow.",
            notSentText(NoteAnswer.NoAnswer),
        )
    }

    @Test
    fun noServerReachedIsUnreachable() =
        runTest {
            assertEquals(NoteAnswer.Unreachable, notes(listOf("https://a.test", "https://b.test")) { "noConnect" }.create(pin, "x"))
            assertEquals(2, sent.size)
        }

    @Test
    fun everyServerFailingWhereOneMayHaveTheRequestIsFailedNotUnreachable() =
        runTest {
            // Each of these may have the note: a server error, a rate limit, a connection that broke after it went out.
            for (failing in listOf("500", "busy", "notFound", "down")) {
                val behaviour = mapOf("a.test" to failing, "b.test" to "noConnect")
                val answer = notes(listOf("https://a.test", "https://b.test")) { behaviour.getValue(it) }.create(pin, "x")
                assertEquals(NoteAnswer.Failed, answer, failing)
            }
        }

    @Test
    fun theTextFitsOpenStreetMapsLimitWithThePlaceLineAndTag() {
        val signature = noteText(schober, "").length
        assertEquals(NOTE_MAX - signature, noteCharactersLeft(schober, ""))
        val longest = "a".repeat(NOTE_MAX - signature)
        assertTrue(canSendNote(schober, longest))
        assertFalse(canSendNote(schober, longest + "a"))
        assertEquals(-1, noteCharactersLeft(schober, longest + "a"))
        // A character outside the BMP counts once, as the API counts it.
        assertEquals(noteCharactersLeft(pin, "a"), noteCharactersLeft(pin, "😀"))
        assertFalse(canSendNote(pin, "   "))
    }

    @Test
    fun theDefaultServerIsOpenStreetMapsApi() {
        assertEquals(listOf("https://api.openstreetmap.org"), ServerDefaults.lists[Service.OsmApi].map { it.url })
        assertEquals("https://api.openstreetmap.org/api/0.6/notes.json", osmNotesUrl("https://api.openstreetmap.org"))
    }

    @Test
    fun anElementGoesThroughAPagesRouteAsOneWord() {
        val element = OsmId(OsmType.Relation, 3768127)
        assertEquals("Relation/3768127", element.routeText)
        assertEquals(element, osmIdOfRouteText(element.routeText))
        assertNull(osmIdOfRouteText("Relation"))
        assertNull(osmIdOfRouteText("Area/1"))
        assertNull(osmIdOfRouteText("Node/x"))
    }
}
