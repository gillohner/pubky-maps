package to.pubky.maps.links

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

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
        val huge = "x".repeat(IncomingLinks.MAX_TEXT_BYTES + 1)
        assertNull(IncomingLinks.textOf(send, null, huge, null))
    }

    @Test
    fun theLimitCountsUtf8BytesLikeTheCore() {
        // 6000 "ü" are 6000 chars but 12000 bytes: within the limit. 9000 are 18000 bytes: over it,
        // though under the limit in chars, so the core (which counts bytes) is never handed them.
        val link = "geo:47.37,8.54"
        assertEquals("${"ü".repeat(6000)} $link", IncomingLinks.textOf(send, null, "${"ü".repeat(6000)} $link", null))
        val over = "${"ü".repeat(9000)} $link"
        assertTrue(over.length < IncomingLinks.MAX_TEXT_BYTES)
        assertNull(IncomingLinks.textOf(send, null, over, null))
    }
}
