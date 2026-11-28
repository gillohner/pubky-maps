package to.pubky.maps.place

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
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

    @Test
    fun aCollectionCountsOnlyTheItemsTheCoresPlaceRuleTakes() =
        runTest {
            val asked = ArrayList<Pair<Url, String>>()
            val client =
                HttpClient(
                    MockEngine { request ->
                        asked += request.url to request.body.toByteArray().decodeToString()
                        respond(PlaceFixtures.read("nexus-collection-posts.json"), HttpStatusCode.OK, json)
                    },
                )
            // The rule the core gives: here only OpenStreetMap links, so pubky:// objects and other web links are no places.
            val osmOnly = { uri: String -> uri.startsWith("https://www.openstreetmap.org/") }
            val data = HttpPlaceData(client, { emptyList() }, { listOf("https://nexus.example.org") }, mayBeMapPlace = osmOnly)
            val post = "pubky://${PlaceFixtures.TESTER}/pub/social/v1/posts/0035ZRTMYFEZ2"

            val answer = data.lists(listOf(post, "https://not-a-post.example.org"), viewer = null)

            assertIs<SourceAnswer.Answered<List<PlaceList>>>(answer)
            assertEquals(listOf(7, 7, 1), answer.value.map { it.itemCount })
            val (url, body) = asked.single()
            assertEquals("/v1/stream/posts/by_ids", url.encodedPath)
            // Nexus takes author:id; a URI that is no post is not asked for.
            assertEquals("""{"post_ids":["${PlaceFixtures.TESTER}:0035ZRTMYFEZ2"]}""", body)
        }
}
