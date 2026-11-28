package to.pubky.maps.write

import kotlin.test.Test
import kotlin.test.assertEquals

class NewCollectionDraftTest {
    private val typed = NewCollectionDraft(open = true, name = "Cake", description = "Worth the walk")

    @Test
    fun openingKeepsWhatIsTyped() {
        assertEquals(typed, typed.withOpen(true))
        assertEquals(NewCollectionDraft(open = true), NewCollectionDraft().withOpen(true))
    }

    @Test
    fun cancelOrBackDropsWhatWasTyped() {
        assertEquals(NewCollectionDraft(), typed.withOpen(false))
        // Opened again, the form is empty.
        assertEquals(NewCollectionDraft(open = true), typed.withOpen(false).withOpen(true))
    }

    @Test
    fun createClosesTheFormEmpty() {
        assertEquals(NewCollectionDraft(), typed.created())
    }
}
