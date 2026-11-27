package to.pubky.maps.place

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import to.pubky.maps.search.SourceAnswer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class HttpPlaceDataTest {
    private val json = headersOf(HttpHeaders.ContentType, "application/json")
    private val zurich = GeoBox(8.5375, 47.3625, 8.55, 47.375)

    @Test
    fun theMapAsksOnlyForPlacesSomeoneReferencedOnPubkyMostActiveFirst() =
        runTest {
            val asked = ArrayList<Url>()
            val client =
                HttpClient(
                    MockEngine { request ->
                        asked += request.url
                        respond(PlaceFixtures.read("pinakes-bbox-zurich.json"), HttpStatusCode.OK, json)
                    },
                )
            val data = HttpPlaceData(client, { listOf("https://pinakes.example.org") }, { emptyList() }, mayBeMapPlace = { true })

            val answer = data.placesIn(zurich, cursor = "next")

            assertIs<SourceAnswer.Answered<MapPlacesPage>>(answer)
            val query = asked.single().parameters
            assertEquals("Place", query["type"])
            // Pinakes' catalogues hold every OSM point of interest; only referenced ones are Pubky places.
            assertEquals("true", query["referenced"])
            // At the cap, the most active places are the ones kept.
            assertEquals("activity", query["sort"])
            assertEquals("8.5375,47.3625,8.55,47.375", query["bbox"])
            assertEquals("next", query["cursor"])
        }

    @Test
    fun aPinakesThatRefusesTheFilterIsNeverAskedWithoutIt() =
        runTest {
            val asked = ArrayList<Url>()
            val client =
                HttpClient(
                    MockEngine { request ->
                        asked += request.url
                        if (request.url.host == "old.example.org") {
                            respond("""{"error":"a query parameter could not be read"}""", HttpStatusCode.BadRequest, json)
                        } else {
                            respond(PlaceFixtures.read("pinakes-bbox-zurich.json"), HttpStatusCode.OK, json)
                        }
                    },
                )
            val data =
                HttpPlaceData(
                    client,
                    { listOf("https://old.example.org", "https://pinakes.example.org") },
                    { emptyList() },
                    mayBeMapPlace = { true },
                )

            val answer = data.placesIn(zurich, cursor = null)

            assertIs<SourceAnswer.Answered<MapPlacesPage>>(answer)
            assertEquals("https://pinakes.example.org", answer.server)
            // The next server is asked, with the filter: an unfiltered answer would badge every point of interest.
            assertEquals(listOf("old.example.org", "pinakes.example.org"), asked.map { it.host })
            assertEquals(listOf("true", "true"), asked.map { it.parameters["referenced"] })
        }
}
