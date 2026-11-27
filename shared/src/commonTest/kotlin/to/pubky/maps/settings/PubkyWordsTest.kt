package to.pubky.maps.settings

import to.pubky.maps.config.DataKind
import to.pubky.maps.write.WriteIntent
import to.pubky.maps.write.signInReason
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The sign-in reasons and the storage settings name bookmarks and collections as pubky.app and the
 * Pubky specs do, never saved places or lists (Gil, 2026-11-27).
 */
class PubkyWordsTest {
    private val oldWords = Regex("\\b(save|saved|saving|list|lists)\\b", RegexOption.IGNORE_CASE)

    @Test
    fun signInReasonsSayBookmarkAndCollection() {
        assertEquals("Sign in to bookmark this place.", WriteIntent.Save.signInReason)
        assertEquals("Sign in to add this place to a collection.", WriteIntent.AddToList.signInReason)
        assertEquals(emptyList<String>(), WriteIntent.entries.map { it.signInReason }.filter { oldWords.containsMatchIn(it) })
    }

    @Test
    fun storedOnlineNamesBookmarksAndCollections() {
        assertEquals("Bookmarks", DataKind.SavedPlaces.title)
        assertEquals("Collections", DataKind.Lists.title)
        val texts = DataKind.entries.flatMap { listOf(it.title, it.description) }
        assertEquals(emptyList<String>(), texts.filter { oldWords.containsMatchIn(it) })
    }
}
