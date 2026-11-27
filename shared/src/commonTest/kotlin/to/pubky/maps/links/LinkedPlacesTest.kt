package to.pubky.maps.links

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import to.pubky.maps.place.PlaceRef
import to.pubky.maps.place.PlaceSource
import to.pubky.maps.place.formatCoordinate
import to.pubky.maps.place.toPlaceRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LinkedPlacesTest {
    private fun TestScope.linkedPlaces(links: MapLinks) = LinkedPlaces(links, backgroundScope)

    @Test
    fun aLinkOpensAsThePlaceOnTheMap() =
        runTest(StandardTestDispatcher()) {
            val links = FakeMapLinks { openedLink(name = "Test") }
            links.gate = CompletableDeferred()
            val places = linkedPlaces(links)
            places.open("geo:47.37,8.54?q=Test", listOf("https://p1", "https://p2"))
            runCurrent()
            // Shown from the link text while the core still works on it.
            assertFalse(places.state.value.opening)
            assertEquals(
                "Test",
                places.state.value.place
                    ?.name,
            )
            links.gate!!.complete(Unit)
            runCurrent()
            val state = places.state.value
            assertFalse(state.opening)
            assertEquals("Test", state.place?.name)
            assertEquals(LinkProvider.Geo, state.provider)
            assertEquals(listOf("https://p1", "https://p2"), links.asked.single().second)
            places.close()
            assertNull(places.state.value.place)
        }

    @Test
    fun aShownPlaceAsksTheCoreNothingAndReplacesALinkStillOpening() =
        runTest(StandardTestDispatcher()) {
            val links = FakeMapLinks { text -> openedLink(source = text, name = text) }
            links.gate = CompletableDeferred()
            val places = linkedPlaces(links)
            places.open("geo:1,1", listOf("https://p1"))
            runCurrent()
            val before = places.state.value.placedAt
            val home = PlaceRef("geo:47.37031,8.55601", "Home", GeoPoint(47.37031, 8.55601), null, PlaceSource.Coordinates)
            places.show(home)
            links.gate!!.complete(Unit)
            runCurrent()
            val state = places.state.value
            assertEquals(home, state.place)
            assertNull(state.provider)
            assertFalse(state.opening)
            assertTrue(state.placedAt != before, "the map moves to the shown place")
            assertEquals(listOf("geo:1,1"), links.asked.map { it.first }, "the saved point went to no core and no Pinakes")
        }

    @Test
    fun aNewerLinkReplacesOneStillOpening() =
        runTest(StandardTestDispatcher()) {
            val links = FakeMapLinks { text -> openedLink(source = text, name = text) }
            links.gate = CompletableDeferred()
            val places = linkedPlaces(links)
            places.open("geo:1,1", emptyList())
            runCurrent()
            links.gate = null
            places.open("geo:2,2", emptyList())
            runCurrent()
            assertEquals(
                "geo:2,2",
                places.state.value.place
                    ?.uri,
            )
        }

    @Test
    fun textWithoutAPlaceSaysSoAndKeepsTheCurrentPlace() =
        runTest(StandardTestDispatcher()) {
            val links = FakeMapLinks { text -> if (text.startsWith("geo:")) openedLink(source = text) else null }
            val places = linkedPlaces(links)
            places.open("geo:47.37,8.54", emptyList())
            runCurrent()
            val message = async { places.messages.first() }
            runCurrent()
            places.open("hello", emptyList())
            runCurrent()
            assertEquals(LinkedPlaces.NOT_A_MAP_LINK, message.await())
            assertEquals(
                "geo:47.37,8.54",
                places.state.value.place
                    ?.uri,
            )
            assertFalse(places.state.value.opening)
            // The map does not move again to the place it already showed.
            val placedAt = places.state.value.placedAt
            places.open("hello again", emptyList())
            runCurrent()
            assertEquals(placedAt, places.state.value.placedAt)
        }

    @Test
    fun aNoteSentBeforeTheMapListensStillArrives() =
        runTest(StandardTestDispatcher()) {
            val places = linkedPlaces(FakeMapLinks())
            places.open("hello", emptyList())
            runCurrent()
            assertEquals(LinkedPlaces.NOT_A_MAP_LINK, places.messages.first())
        }

    @Test
    fun aResultAfterCloseIsDropped() =
        runTest(StandardTestDispatcher()) {
            val links = FakeMapLinks { text -> openedLink(source = text) }
            links.gate = CompletableDeferred()
            val places = linkedPlaces(links)
            places.open("geo:1,1", emptyList())
            runCurrent()
            places.close()
            links.gate!!.complete(Unit)
            runCurrent()
            assertNull(places.state.value.place)
            assertFalse(places.state.value.opening)
        }

    @Test
    fun theSameLinkOpenedAgainMovesTheMapAgain() =
        runTest(StandardTestDispatcher()) {
            val places = linkedPlaces(FakeMapLinks { text -> openedLink(source = text) })
            places.open("geo:1,1", emptyList())
            runCurrent()
            val first = places.state.value.placedAt
            places.open("geo:1,1", emptyList())
            runCurrent()
            assertEquals(
                "geo:1,1",
                places.state.value.place
                    ?.uri,
            )
            assertTrue(places.state.value.placedAt > first)
        }

    @Test
    fun aPointFromTheTextShowsBeforeTheCoreAnswersAndTheCoreRefinesIt() =
        runTest(StandardTestDispatcher()) {
            val link = "https://osmand.net/map?pin=52.51,13.39"
            val links =
                FakeMapLinks { openedLink(source = it, name = "Brandenburger Tor", point = GeoPoint(52.5163, 13.3777), fromPinakes = true) }
            links.fromText = { openedLink(source = it, name = null, point = GeoPoint(52.51, 13.39)) }
            links.gate = CompletableDeferred()
            val places = linkedPlaces(links)
            places.open(link, listOf("https://pinakes.example"))
            runCurrent()
            // Pinakes has not answered (a silent server can take its whole deadline): the place is
            // already on the map, from the link text.
            val early = places.state.value
            assertFalse(early.opening)
            assertEquals(GeoPoint(52.51, 13.39), early.place?.point)
            assertEquals(link, early.place?.uri)
            assertEquals(PlaceSource.LinkText, early.place?.source)
            links.gate!!.complete(Unit)
            runCurrent()
            val late = places.state.value
            assertEquals("Brandenburger Tor", late.place?.name)
            assertEquals(PlaceSource.Pinakes, late.place?.source)
            assertEquals(link, late.place?.uri, "the link the user had stays the place's URI")
            // The card's point moved, so the map moves once more.
            assertTrue(late.placedAt > early.placedAt)
        }

    @Test
    fun aShortLinkWaitsForItsRedirectAndAnUnmovedPointDoesNotMoveTheMapAgain() =
        runTest(StandardTestDispatcher()) {
            val links = FakeMapLinks { openedLink(source = it, point = GeoPoint(47.37786, 8.54032), provider = LinkProvider.Google) }
            links.fromText = { openedLink(source = it, name = "Zürich HB", point = null, provider = LinkProvider.Google) }
            links.gate = CompletableDeferred()
            val places = linkedPlaces(links)
            places.open("Zürich HB\nhttps://maps.app.goo.gl/abc", emptyList())
            runCurrent()
            assertTrue(places.state.value.opening)
            assertNull(places.state.value.place)
            links.gate!!.complete(Unit)
            runCurrent()
            assertEquals(
                GeoPoint(47.37786, 8.54032),
                places.state.value.place
                    ?.point,
            )

            // A geo: link that Pinakes does not know: same point before and after, one move.
            val same = FakeMapLinks { openedLink(source = it) }
            same.gate = CompletableDeferred()
            val other = linkedPlaces(same)
            other.open("geo:47.37,8.54", emptyList())
            runCurrent()
            val first = other.state.value.placedAt
            same.gate!!.complete(Unit)
            runCurrent()
            assertEquals(first, other.state.value.placedAt)
        }

    @Test
    fun withoutACoreTheLinkTextStillShowsMarkedOffline() =
        runTest(StandardTestDispatcher()) {
            val links = FakeMapLinks { openedLink(source = it, name = "Test") }
            links.coreFails = true
            val reported = mutableListOf<Boolean>()
            val places = LinkedPlaces(links, backgroundScope) { reported += it }
            places.open("geo:47.37,8.54?q=47.37,8.54(Test)", listOf("https://pinakes.example"))
            runCurrent()
            val place = places.state.value.place!!
            assertEquals("Test", place.name)
            assertTrue(place.offline)
            // Nothing was asked, so nothing is said about Pinakes.
            assertTrue(reported.isEmpty())
        }

    @Test
    fun pinakesReachabilityGoesToTheMissingLine() =
        runTest(StandardTestDispatcher()) {
            var unreachable = true
            val links = FakeMapLinks { openedLink(source = it, pinakesUnreachable = unreachable) }
            val reported = mutableListOf<Boolean>()
            val places = LinkedPlaces(links, backgroundScope) { reported += it }
            places.open("geo:1,1", listOf("https://pinakes.example"))
            runCurrent()
            assertTrue(
                places.state.value.place!!
                    .offline,
            )
            unreachable = false
            places.open("geo:2,2", listOf("https://pinakes.example"))
            runCurrent()
            // No servers: Pinakes is not asked, so nothing is reported.
            places.open("geo:3,3", emptyList())
            runCurrent()
            assertEquals(listOf(false, true), reported)
        }

    @Test
    fun aPlaceFromPinakesIsNotOfflineEvenWhenTheShortLinkFailed() =
        runTest(StandardTestDispatcher()) {
            val links = FakeMapLinks { openedLink(source = it, fromPinakes = true, shortLinkUnfollowed = true) }
            val places = linkedPlaces(links)
            places.open("https://maps.app.goo.gl/abc", emptyList())
            runCurrent()
            val place = places.state.value.place!!
            assertFalse(place.offline)
            assertFalse(place.shortLinkUnfollowed)

            val failed = linkedPlaces(FakeMapLinks { openedLink(source = it, point = null, shortLinkUnfollowed = true) })
            failed.open("https://maps.app.goo.gl/abc", emptyList())
            runCurrent()
            assertTrue(
                failed.state.value.place!!
                    .shortLinkUnfollowed,
            )
        }

    @Test
    fun mapViewsAndSearchesAreNotPinned() {
        val view = openedLink(name = null, pointIsView = true).toPlaceRef()
        assertFalse(view.pinned)
        assertTrue(view.viewOnly)
        val search = openedLink(name = null, query = "restaurants").toPlaceRef()
        assertFalse(search.pinned)
        assertFalse(search.viewOnly)
        val place = openedLink().toPlaceRef()
        assertTrue(place.pinned)
        assertFalse(place.viewOnly)
    }

    @Test
    fun placeTitles() {
        fun ref(
            name: String? = null,
            query: String? = null,
            point: GeoPoint? = null,
        ) = PlaceRef(uri = "geo:0,0?q=x", name = name, point = point, osm = null, source = PlaceSource.LinkText, query = query)

        assertEquals("Café", ref(name = "Café", point = GeoPoint(1.0, 2.0)).title)
        assertEquals("Bahnhofstrasse 1", ref(query = "Bahnhofstrasse 1").title)
        assertEquals("47.37687, -8.5", ref(point = GeoPoint(47.376871, -8.5)).title)
        assertEquals("geo:0,0?q=x", ref().title)
        assertEquals("0", formatCoordinate(-0.000001))
        assertEquals("-0.00001", formatCoordinate(-0.00001))
        assertEquals("180", formatCoordinate(180.0))
    }
}
