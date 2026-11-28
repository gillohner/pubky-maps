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
import to.pubky.maps.links.GeoPoint
import to.pubky.maps.search.searchUserAgent
import to.pubky.maps.status.ServiceHealth
import to.pubky.maps.status.ServicePart
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Street-level photos from Panoramax. `fixtures/panoramax/search-nantes.json` is the federation's real
 * answer (api.panoramax.xyz, 2026-11-28) for a place in Nantes, asked as [panoramaxSearchUrl] asks
 * with limit 20, with each photo's EXIF and unused properties left out: 20 photos of 4 sequences from
 * the OpenStreetMap France and IGN instances.
 */
class StreetPhotosTest {
    private val nantes = FileSystem.SYSTEM.read("src/commonTest/fixtures/panoramax/search-nantes.json".toPath()) { readUtf8() }
    private val place = GeoPoint(47.2184, -1.5536)
    private val json = headersOf(HttpHeaders.ContentType, "application/geo+json")

    @Test
    fun theSearchAsksForPhotosLookingAtThePlaceNearestFirst() {
        assertEquals(
            "https://api.panoramax.xyz/api/search?place_position=-1.5536%2C47.2184&place_distance=3-40&place_fov_tolerance=60&limit=30",
            panoramaxSearchUrl("https://api.panoramax.xyz", place),
        )
        // A server address with a path keeps it, as an instance behind a proxy.
        assertTrue(panoramaxSearchUrl("https://example.org/panoramax/", place).startsWith("https://example.org/panoramax/api/search?"))
    }

    @Test
    fun theFederationsAnswerGivesOnePhotoPerSequenceOpeningInItsInstancesViewer() {
        val photos = parsePanoramaxSearch(nantes, "https://api.panoramax.xyz")
        assertEquals(4, photos.size)
        val first = photos.first()
        assertEquals(
            StreetPhoto(
                id = "88949d59-c992-4016-ac51-5b983e8f5426",
                thumbnailUrl = "https://panoramax.openstreetmap.fr/derivates/88/94/9d/59/c992-4016-ac51-5b983e8f5426/thumb.jpg",
                viewerUrl = "https://panoramax.openstreetmap.fr/#focus=pic&pic=88949d59-c992-4016-ac51-5b983e8f5426",
                author = "Koreller",
                taken = "Jul 2025",
                licence = "CC-BY-SA-4.0",
                licenceUrl = "https://creativecommons.org/licenses/by-sa/4.0/",
                photoUrl = "https://panoramax.openstreetmap.fr/derivates/88/94/9d/59/c992-4016-ac51-5b983e8f5426/sd.jpg",
                panorama = true,
                point = GeoPoint(47.218295565, -1.553600284),
                heading = 250.0,
            ),
            first,
        )
        val ign = photos[1]
        assertEquals("immergis", ign.author)
        assertEquals("etalab-2.0", ign.licence)
        assertEquals("https://alliance.numerique.gouv.fr/licence-ouverte-open-licence/", ign.licenceUrl)
        assertTrue(ign.viewerUrl.startsWith("https://panoramax.ign.fr/#focus=pic&pic="))
        assertEquals(photos.size, photos.map { it.id }.toSet().size)
    }

    @Test
    fun theViewerShowsAPhotosStandardCopyAndTurnsOnlyThe360Ones() {
        val photos = parsePanoramaxSearch(nantes, "https://api.panoramax.xyz")
        assertEquals(listOf(true, true, false, false), photos.map { it.panorama })
        // A camera of 95° and one that names no field of view take flat photos.
        val flat = photos[3]
        assertTrue(flat.photoUrl.endsWith("/sd.jpg"), flat.photoUrl)
        assertEquals(306.0, flat.heading)
        assertEquals(GeoPoint(47.218289218, -1.553474814), flat.point)
    }

    @Test
    fun aPhotoWithoutAStandardCopyShowsItsThumbnailAndOneWithoutAPointOrHeadingHasNone() {
        val id = "00000000-0000-4000-8000-000000000001"
        val bare = parsePanoramaxSearch("""{"features":[${feature(id)}]}""", "https://api.panoramax.xyz").single()
        assertEquals(bare.thumbnailUrl, bare.photoUrl)
        assertNull(bare.point)
        assertNull(bare.heading)
        assertEquals(false, bare.panorama)
        val odd =
            """{"features":[{"id":"$id","assets":{"thumb":{"href":"https://x.test/t.jpg"},"sd":{"href":"file:///sdcard/sd.jpg"}},
                "geometry":{"type":"Point","coordinates":[200.0,47.2]},
                "properties":{"view:azimuth":"250","pers:interior_orientation":{"field_of_view":"360"}}}]}"""
        val refused = parsePanoramaxSearch(odd, "https://api.panoramax.xyz").single()
        assertEquals("https://x.test/t.jpg", refused.photoUrl)
        // A 360° photo without its larger copy has only a crop of its front: shown flat.
        val cropOnly =
            """{"features":[{"id":"$id","assets":{"thumb":{"href":"https://x.test/t.jpg"}},
                "properties":{"pers:interior_orientation":{"field_of_view":360}}}]}"""
        assertEquals(false, parsePanoramaxSearch(cropOnly, "https://api.panoramax.xyz").single().panorama)
        assertEquals(
            true,
            parsePanoramaxSearch(
                cropOnly.replace("}},", "},\"sd\":{\"href\":\"https://x.test/sd.jpg\"}},"),
                "https://api.panoramax.xyz",
            ).single().panorama,
        )
        assertNull(refused.point)
        assertNull(refused.heading)
        assertEquals(false, refused.panorama)
    }

    @Test
    fun a360PhotoOpensTurnedToThePlace() {
        val at = GeoPoint(0.0, 0.0)

        fun facing(
            heading: Double?,
            place: GeoPoint,
            point: GeoPoint? = at,
        ) = panoramaFacing(
            StreetPhoto("p", "https://x.test/t.jpg", "https://x.test/#p", null, null, null, null, point = point, heading = heading),
            place,
        )
        val east = GeoPoint(0.0, 0.001)
        // The middle looks where the camera looked; the place to its right is right of the middle.
        assertEquals(0.5, facing(90.0, east), 1e-6)
        assertEquals(0.75, facing(0.0, east), 1e-6)
        assertEquals(0.25, facing(180.0, east), 1e-6)
        // Behind the camera: the photo's left edge (its right edge is the same direction).
        assertEquals(0.0, facing(270.0, east), 1e-6)
        assertEquals(0.5, facing(null, east))
        assertEquals(0.5, facing(90.0, east, point = null))
        assertEquals(0.5, facing(90.0, at))
        // The Nantes photo looks west-south-west (250°); the place is north of it, 110° to its right.
        val nantes = parsePanoramaxSearch(nantes, "https://api.panoramax.xyz").first()
        assertEquals(0.5 + 110.0 / 360, panoramaFacing(nantes, GeoPoint(47.2184, -1.5536)), 0.002)
        assertEquals(0.0, bearingOf(at, GeoPoint(0.001, 0.0)), 1e-9)
        assertEquals(90.0, bearingOf(at, east), 1e-9)
        assertEquals(270.0, bearingOf(east, at), 1e-9)
    }

    @Test
    fun anInstancesOwnPhotosOpenInItsViewer() {
        val answer = """{"features":[${feature("4b0c1f6e-0000-4000-8000-000000000001", via = null)}]}"""
        assertEquals(
            "https://panoramax.ign.fr/#focus=pic&pic=4b0c1f6e-0000-4000-8000-000000000001",
            parsePanoramaxSearch(answer, "https://panoramax.ign.fr/").single().viewerUrl,
        )
        // A server of the user's own on a port keeps it.
        assertEquals(
            "http://127.0.0.1:18492/#focus=pic&pic=4b0c1f6e-0000-4000-8000-000000000001",
            parsePanoramaxSearch(answer, "http://127.0.0.1:18492").single().viewerUrl,
        )
    }

    @Test
    fun photosWithoutAnIdAThumbnailOrASafeAddressAreLeftOut() {
        val answer =
            """{"features":[
                {"collection":"a","assets":{"thumb":{"href":"https://x.test/t.jpg"}}},
                ${feature("id with spaces", collection = "b")},
                ${feature("00000000-0000-4000-8000-00000000000c", collection = "c", thumb = "file:///sdcard/t.jpg")},
                ${feature("00000000-0000-4000-8000-00000000000d", collection = "d", thumb = null)},
                ${feature("00000000-0000-4000-8000-00000000000e", collection = "e")}
            ]}"""
        assertEquals(
            listOf("00000000-0000-4000-8000-00000000000e"),
            parsePanoramaxSearch(answer, "https://api.panoramax.xyz").map { it.id },
        )
    }

    @Test
    fun atMostARowOfPhotosIsKept() {
        val many =
            (1..10).joinToString(
                ",",
            ) { feature("00000000-0000-4000-8000-0000000000${it.toString().padStart(2, '0')}", collection = "s$it") }
        assertEquals(MAX_STREET_PHOTOS, parsePanoramaxSearch("""{"features":[$many]}""", "https://api.panoramax.xyz").size)
    }

    @Test
    fun aPhotoWithoutAProducerOrDateHasNeither() {
        val answer = """{"features":[{"id":"00000000-0000-4000-8000-000000000001","assets":{"thumb":{"href":"https://x.test/t.jpg"}}}]}"""
        val photo = parsePanoramaxSearch(answer, "https://api.panoramax.xyz").single()
        assertNull(photo.author)
        assertNull(photo.taken)
        assertNull(photo.licence)
    }

    @Test
    fun anAnswerThatIsNoSearchIsRefused() {
        assertFailsWith<IllegalArgumentException> { parsePanoramaxSearch("""{"type":"Catalog"}""", "https://api.panoramax.xyz") }
    }

    @Test
    fun licencesAndDatesAreWrittenForPeople() {
        assertEquals("CC BY-SA 4.0", licenceName("CC-BY-SA-4.0"))
        assertEquals("CC BY 4.0", licenceName("CC-BY-4.0"))
        assertEquals("Etalab 2.0", licenceName("etalab-2.0"))
        assertEquals("CC0-1.0", licenceName("CC0-1.0"))
        assertEquals("Apr 2024", monthOf("2024-04-29T16:54:30+00:00"))
        assertEquals("Dec 2019", monthOf("2019-12-01"))
        assertNull(monthOf("2024-13-01"))
        assertNull(monthOf("yesterday"))
    }

    private val requests = ArrayList<String>()

    /** Serves by host: "ok" the Nantes answer, "none" no photos, the others as named. */
    private fun panoramax(
        servers: List<String>,
        behaviour: (host: String) -> String = { "ok" },
    ): HttpPanoramax {
        val engine =
            MockEngine { request ->
                requests += "${request.url.host} ${request.headers[HttpHeaders.UserAgent]}"
                when (behaviour(request.url.host)) {
                    "ok" -> respond(nantes, HttpStatusCode.OK, json)
                    "none" -> respond("""{"features":[],"links":[]}""", HttpStatusCode.OK, json)
                    "page" -> respond("<html>Welcome</html>", HttpStatusCode.OK)
                    "busy" -> respond("", HttpStatusCode.TooManyRequests, headersOf(HttpHeaders.RetryAfter, "60"))
                    "500" -> respondError(HttpStatusCode.InternalServerError)
                    else -> throw okio.IOException("connection refused")
                }
            }
        return HttpPanoramax(HttpClient(engine) { install(UserAgent) { agent = searchUserAgent("1.2.3") } }, { servers })
    }

    @Test
    fun theFirstServerAnswersWithTheAppsUserAgent() =
        runTest {
            val found = assertIs<StreetPhotosAnswer.Found>(panoramax(listOf("https://api.panoramax.xyz")).near(place))
            assertEquals(4, found.photos.size)
            assertEquals(listOf("api.panoramax.xyz PubkyMaps/1.2.3"), requests)
        }

    @Test
    fun aFailingServerHandsOverToTheNext() =
        runTest {
            val servers = listOf("https://down.test", "https://broken.test", "https://page.test", "https://up.test")
            val p = panoramax(servers) { host -> mapOf("broken.test" to "500", "page.test" to "page", "up.test" to "ok")[host] ?: "down" }
            assertEquals(4, assertIs<StreetPhotosAnswer.Found>(p.near(place)).photos.size)
            assertEquals(4, requests.size)
        }

    @Test
    fun noPhotosIsAnAnswerAndTheNextServerIsNotAsked() =
        runTest {
            val p = panoramax(listOf("https://a.test", "https://b.test")) { if (it == "a.test") "none" else "ok" }
            assertEquals(StreetPhotosAnswer.Found(emptyList()), p.near(place))
            assertEquals(1, requests.size)
        }

    @Test
    fun aBusyServerIsLeftAloneUntilItsRetryAfter() =
        runTest {
            val p = panoramax(listOf("https://a.test", "https://b.test")) { if (it == "a.test") "busy" else "ok" }
            p.near(place)
            p.near(place)
            assertEquals(listOf("a.test", "b.test", "b.test"), requests.map { it.substringBefore(' ') })
        }

    @Test
    fun noServerAnsweringIsUnreachable() =
        runTest {
            assertEquals(StreetPhotosAnswer.Unreachable, panoramax(listOf("https://a.test", "https://b.test")) { "down" }.near(place))
        }

    private val photo = StreetPhoto("p", "https://x.test/t.jpg", "https://x.test/#focus=pic&pic=p", "Koreller", "Jul 2025", null, null)
    private val other = GeoPoint(47.3752, 8.5302)

    @Test
    fun aPlaceIsAskedOnceAndItsAnswerKeptForWhenItIsOpenedAgain() =
        runTest {
            val asked = ArrayList<GeoPoint>()
            val controller =
                StreetPhotosController({
                    asked += it
                    StreetPhotosAnswer.Found(listOf(photo))
                }, backgroundScope)
            controller.open(place)
            assertEquals(StreetPhotosState(place), controller.state.value)
            runCurrent()
            assertEquals(StreetPhotosState(place, listOf(photo), loading = false), controller.state.value)
            // A recomposition or a rotation opens it again.
            controller.open(place)
            runCurrent()
            controller.open(other)
            runCurrent()
            controller.open(place)
            runCurrent()
            assertEquals(listOf(place, other), asked)
            assertEquals(StreetPhotosState(place, listOf(photo), loading = false), controller.state.value)
        }

    @Test
    fun theAnswerForAPlaceLeftIsNotShownForTheNext() =
        runTest {
            val first = CompletableDeferred<StreetPhotosAnswer>()
            val controller =
                StreetPhotosController(
                    { if (it == place) first.await() else StreetPhotosAnswer.Found(emptyList()) },
                    backgroundScope,
                )
            controller.open(place)
            runCurrent()
            controller.open(other)
            runCurrent()
            first.complete(StreetPhotosAnswer.Found(listOf(photo)))
            runCurrent()
            assertEquals(StreetPhotosState(other, emptyList(), loading = false), controller.state.value)
        }

    @Test
    fun anOutageIsNamedOnTheMapAndTheNextOpeningAsksAgain() =
        runTest {
            val health = ServiceHealth()
            var answer: StreetPhotosAnswer = StreetPhotosAnswer.Unreachable
            var asked = 0
            val controller =
                StreetPhotosController({
                    asked++
                    answer
                }, backgroundScope, health)
            controller.open(place)
            runCurrent()
            assertEquals(StreetPhotosState(place, loading = false, failed = true), controller.state.value)
            assertEquals(setOf(ServicePart.StreetPhotos), health.missing.value)
            answer = StreetPhotosAnswer.Found(listOf(photo))
            controller.open(place)
            runCurrent()
            assertEquals(2, asked)
            assertEquals(listOf(photo), controller.state.value?.photos)
            assertEquals(emptySet(), health.missing.value)
        }

    @Test
    fun theAnswersKeptAreThoseOfThePlacesOpenedLast() =
        runTest {
            val asked = ArrayList<GeoPoint>()
            val controller =
                StreetPhotosController({
                    asked += it
                    StreetPhotosAnswer.Found(listOf(photo))
                }, backgroundScope)
            val others = (1..32).map { GeoPoint(47.0 + it / 1000.0, 8.5) }
            controller.open(place)
            runCurrent()
            // The first place opened again halfway is kept over the ones opened before it since.
            others.forEachIndexed { i, point ->
                controller.open(point)
                runCurrent()
                if (i == 15) {
                    controller.open(place)
                    runCurrent()
                }
            }
            controller.open(place)
            runCurrent()
            controller.open(others.first())
            runCurrent()
            assertEquals(listOf(place) + others + others.first(), asked)
        }

    @Test
    fun aFailureOutsideTheServersEndsTheLoadingAndTheNextOpeningAsksAgain() =
        runTest {
            var asked = 0
            val controller =
                StreetPhotosController(
                    {
                        asked++
                        if (asked == 1) throw IllegalStateException("no settings") else StreetPhotosAnswer.Found(listOf(photo))
                    },
                    backgroundScope,
                )
            controller.open(place)
            runCurrent()
            assertEquals(StreetPhotosState(place, loading = false, failed = true), controller.state.value)
            controller.open(place)
            runCurrent()
            assertEquals(listOf(photo), controller.state.value?.photos)
        }

    @Test
    fun closingOrAPlaceWithoutAPointShowsNone() =
        runTest {
            val controller = StreetPhotosController({ StreetPhotosAnswer.Found(listOf(photo)) }, backgroundScope)
            controller.open(place)
            runCurrent()
            controller.open(null)
            assertNull(controller.state.value)
            controller.open(place)
            controller.close()
            runCurrent()
            assertNull(controller.state.value)
        }

    private fun feature(
        id: String,
        collection: String = "s",
        thumb: String? = "https://x.test/$id/thumb.jpg",
        via: String? = "https://panoramax.openstreetmap.fr",
    ): String {
        val assets = thumb?.let { """"assets":{"thumb":{"href":"$it"}},""" }.orEmpty()
        val links = via?.let { """"links":[{"rel":"via","href":"$it"}],""" }.orEmpty()
        return """{"id":"$id","collection":"$collection",$assets$links"properties":{"datetime":"2024-04-29T16:54:30+00:00"}}"""
    }
}
