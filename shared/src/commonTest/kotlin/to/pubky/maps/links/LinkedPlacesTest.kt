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
            assertTrue(places.state.value.opening)
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
