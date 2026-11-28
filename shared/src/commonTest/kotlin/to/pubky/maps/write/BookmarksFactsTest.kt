package to.pubky.maps.write

import kotlin.test.Test
import kotlin.test.assertEquals

/** The Bookmarks page's bookmarks row counts its places and the ones still waiting to be sent. */
class BookmarksFactsTest {
    private val row = SavedRow("https://www.openstreetmap.org/node/270803095", "Conditorei Schober", Kept.Private)

    @Test
    fun oneOrMorePlaces() {
        assertEquals("1 place", bookmarksFacts(listOf(row)))
        assertEquals("2 places", bookmarksFacts(listOf(row, row.copy(kept = Kept.Phone))))
    }

    @Test
    fun placesWaitingToBeSentAreCounted() {
        assertEquals("1 place · 1 not sent yet", bookmarksFacts(listOf(row.copy(pending = true))))
        assertEquals("3 places · 2 not sent yet", bookmarksFacts(listOf(row, row.copy(pending = true), row.copy(pending = true))))
    }
}
