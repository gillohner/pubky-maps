package to.pubky.maps.links

import kotlinx.coroutines.runBlocking
import to.pubky.maps.account.SharedCore
import to.pubky.maps.core.PubkyMapsCore
import to.pubky.maps.core.defaultCoreConfig
import to.pubky.maps.place.PlaceSource
import to.pubky.maps.place.toPlaceRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [CoreMapLinks] on the real Rust core (a build for this machine, loaded through JNA), with no
 * network: Pinakes on a port that refuses, so a link opens from its text.
 */
class RealCoreMapLinksTest {
    private val core =
        SharedCore {
            val config = defaultCoreConfig()
            config.dhtEnabled = false
            config.pkarrRelays = listOf("http://127.0.0.1:9")
            config.authRelayUrls = listOf("http://127.0.0.1:9/inbox")
            PubkyMapsCore.create(config)
        }
    private val links = CoreMapLinks(core::get)
    private val noPinakes = listOf("http://127.0.0.1:9")

    @Test
    fun aGoogleLinkOpensAtItsPlaceFromTheLinkTextWhenPinakesIsUnreachable() =
        runBlocking {
            val link =
                "https://www.google.com/maps/place/Z%C3%BCrich+HB/@47.3721439,8.5351212,17z/data=!3m1!4b1!8m2!3d47.3778579!4d8.5403226"
            val opened = links.open("Zürich HB\n$link", noPinakes)!!
            assertEquals(link, opened.sourceUri, "the link the user had, without the shared text")
            assertEquals(LinkProvider.Google, opened.provider)
            assertEquals("Zürich HB", opened.name)
            assertEquals(GeoPoint(47.3778579, 8.5403226), opened.point)
            assertTrue(opened.offline)
            assertTrue(opened.pinakesUnreachable)
            assertFalse(opened.shortLinkUnfollowed)
            assertFalse(opened.fromPinakes)
            assertEquals(PlaceSource.LinkText, opened.toPlaceRef().source)
        }

    @Test
    fun anOsmLinkKeepsItsElementAndAGeoSearchItsQuery() =
        runBlocking {
            val osm = links.open("https://www.openstreetmap.org/way/50637691", noPinakes)!!
            assertEquals(OsmId(OsmType.Way, 50637691), osm.osm)
            val search = links.open("geo:0,0?q=Bahnhofstrasse+1+Z%C3%BCrich", noPinakes)!!
            assertNull(search.point)
            assertEquals("Bahnhofstrasse 1 Zürich", search.query)
        }

    @Test
    fun textWithoutAMapLinkOpensNothing() =
        runBlocking {
            assertNull(links.read("hello https://example.com"))
            assertNull(links.open("hello https://example.com", noPinakes))
        }

    @Test
    fun theTextAloneIsReadWithoutTheCoreInstance() {
        // A core that cannot be built: read never asks for it.
        val noCore = CoreMapLinks { error("no core") }
        val view = noCore.read("https://www.google.com/maps/@47.37,8.54,15z")!!
        assertTrue(view.pointIsView)
        assertEquals(GeoPoint(47.37, 8.54), view.point)
        val pin = noCore.read("geo:0,0?q=47.37,8.54(Test)")!!
        assertEquals("Test", pin.name)
        assertFalse(pin.pointIsView)
        assertFalse(pin.offline)
    }

    @Test
    fun sharedTextThatKotlinPassesTheCoreReads() {
        // Just under the byte limit both sides share, with two-byte characters.
        val text = "${"ü".repeat((IncomingLinks.MAX_TEXT_BYTES - 40) / 2)}\ngeo:47.37,8.54"
        val passed = IncomingLinks.textOf(IncomingLinks.ACTION_SEND, null, text, null)!!
        assertEquals(GeoPoint(47.37, 8.54), links.read(passed)?.point)
    }

    @Test
    fun linksAreBuiltByTheCore() {
        val place = LinkTarget(GeoPoint(47.37, 8.54), "Café & Bar")
        assertEquals("geo:47.37,8.54?z=16&q=47.37,8.54(Caf%C3%A9%20%26%20Bar)", links.placeLink(LinkFormat.Geo, place))
        assertEquals(
            "https://www.openstreetmap.org/way/4",
            links.placeLink(LinkFormat.OpenStreetMap, place.copy(osm = OsmId(OsmType.Way, 4))),
        )
        assertEquals(
            "https://waze.com/ul?ll=47.37,8.54&navigate=yes",
            links.directionsLink(LinkFormat.Waze, place, TravelMode.Driving),
        )
        assertNull(links.directionsLink(LinkFormat.OrganicMaps, place, TravelMode.Driving))
        // Every format the Open in list uses builds a place link.
        for (format in LinkFormat.entries - LinkFormat.GoogleNavigation) {
            assertTrue(links.placeLink(format, place) != null, "$format")
        }
    }
}
