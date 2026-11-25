package to.pubky.maps.links

import kotlinx.coroutines.test.runTest
import to.pubky.maps.place.PlaceSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class IncomingLinksTest {
    private val view = IncomingLinks.ACTION_VIEW
    private val send = IncomingLinks.ACTION_SEND

    @Test
    fun viewTakesTheDataUri() {
        assertEquals("geo:47.37,8.54?q=Test", IncomingLinks.textOf(view, " geo:47.37,8.54?q=Test ", null, null))
        assertNull(IncomingLinks.textOf(view, null, "ignored", null))
        assertNull(IncomingLinks.textOf(view, "  ", null, null))
    }

    @Test
    fun sendTakesTheTextWithTheSubjectAsFirstLine() {
        assertEquals(
            "Zürich HB\nhttps://maps.app.goo.gl/abc",
            IncomingLinks.textOf(send, null, "https://maps.app.goo.gl/abc", "Zürich HB"),
        )
        // Share sheets that already start the text with the name are left alone.
        assertEquals(
            "Zürich HB\nhttps://maps.app.goo.gl/abc",
            IncomingLinks.textOf(send, null, "Zürich HB\nhttps://maps.app.goo.gl/abc", "Zürich HB"),
        )
        assertEquals("https://osm.org/node/1", IncomingLinks.textOf(send, null, "https://osm.org/node/1", "  "))
        assertNull(IncomingLinks.textOf(send, null, null, "Name only"))
    }

    @Test
    fun otherActionsAndHugeTextAreIgnored() {
        assertNull(IncomingLinks.textOf("android.intent.action.MAIN", "geo:1,2", null, null))
        assertNull(IncomingLinks.textOf(null, "geo:1,2", null, null))
        val huge = "x".repeat(IncomingLinks.MAX_TEXT_LENGTH + 1)
        assertNull(IncomingLinks.textOf(send, null, huge, null))
    }

    @Test
    fun openingMakesAPlaceRefThatKeepsTheLinkTheUserHad() =
        runTest {
            val link = "https://www.google.com/maps/place/Z%C3%BCrich+HB/@47.37,8.53,17z/data=!3d47.3778579!4d8.5403226"
            val links = FakeMapLinks { openedLink(source = it, name = "Zürich HB", provider = LinkProvider.Google) }
            val result = links.openAsPlace(link, listOf("https://pinakes.example"))
            assertIs<LinkOpening.Opened>(result)
            assertEquals(link, result.place.uri)
            assertEquals("Zürich HB", result.place.title)
            assertEquals(PlaceSource.LinkText, result.place.source)
            assertEquals(LinkProvider.Google, result.provider)
            assertEquals(listOf(link to listOf("https://pinakes.example")), links.asked)
        }

    @Test
    fun openingFromPinakesOrWithoutALink() =
        runTest {
            val fromPinakes = FakeMapLinks { openedLink(fromPinakes = true) }.openAsPlace("geo:1,2", emptyList())
            assertIs<LinkOpening.Opened>(fromPinakes)
            assertEquals(PlaceSource.Pinakes, fromPinakes.place.source)
            assertEquals(LinkOpening.NotAMapLink, FakeMapLinks().openAsPlace("hello", emptyList()))
            val failing = FakeMapLinks { error("core refused it") }
            assertEquals(LinkOpening.NotAMapLink, failing.openAsPlace("hello", emptyList()))
        }
}
