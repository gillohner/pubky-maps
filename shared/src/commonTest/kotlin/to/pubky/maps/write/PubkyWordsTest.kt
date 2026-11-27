package to.pubky.maps.write

import to.pubky.maps.config.DataKind
import to.pubky.maps.settings.description
import to.pubky.maps.settings.title
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The sign-in reasons, the lines of a refused write and the storage settings name bookmarks and
 * collections as pubky.app and the Pubky specs do, never saved places or lists (Gil, 2026-11-27).
 */
class PubkyWordsTest {
    private val oldWords = Regex("\\b(save|saved|saving|list|lists)\\b", RegexOption.IGNORE_CASE)
    private val osm = "https://www.openstreetmap.org/node/270803095"

    @Test
    fun signInReasonsSayBookmarkAndCollection() {
        assertEquals("Sign in to bookmark this place.", WriteIntent.Save.signInReason)
        assertEquals("Sign in to add this place to a collection.", WriteIntent.AddToList.signInReason)
        assertEquals(emptyList<String>(), WriteIntent.entries.map { it.signInReason }.filter { oldWords.containsMatchIn(it) })
    }

    @Test
    fun aRefusedBookmarkOrCollectionWriteSaysSo() {
        val texts =
            listOf(
                WriteOp.Save(osm),
                WriteOp.Unsave(osm),
                WriteOp.CreateList(osm, "Coffee", private = true, path = "/priv/x", uri = "pubky://me/priv/x", json = "{}"),
                WriteOp.EditList(osm, "pubky://me/priv/x", remove = false),
                WriteOp.EditList(osm, "pubky://me/priv/x", remove = true),
            ).map { failedText(it, null) }
        assertEquals(
            listOf(
                "Couldn't bookmark the place.",
                "Couldn't remove the bookmark.",
                "Couldn't create the collection “Coffee”.",
                "Couldn't add the place to the collection.",
                "Couldn't remove the place from the collection.",
            ),
            texts,
        )
    }

    @Test
    fun storedOnlineNamesBookmarksAndCollections() {
        assertEquals("Bookmarks", DataKind.SavedPlaces.title)
        assertEquals("Collections", DataKind.Lists.title)
        val texts = DataKind.entries.flatMap { listOf(it.title, it.description) }
        assertEquals(emptyList<String>(), texts.filter { oldWords.containsMatchIn(it) })
    }
}
