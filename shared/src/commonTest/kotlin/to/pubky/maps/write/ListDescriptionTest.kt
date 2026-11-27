package to.pubky.maps.write

import kotlin.test.Test
import kotlin.test.assertEquals

/** A collection's description is counted and kept as the spec and pubky.app read it. */
class ListDescriptionTest {
    @Test
    fun theLengthIsTheSpecsCharactersOfTheTextSent() {
        assertEquals(14, descriptionLength("Worth the walk"))
        assertEquals(14, descriptionLength("  Worth the walk \n"), "the spaces around it are not sent")
        assertEquals(3, descriptionLength("☕🍰🥐"), "an emoji is one character, as the spec counts code points")
        assertEquals(LIST_DESCRIPTION_MAX, descriptionLength("🍰".repeat(LIST_DESCRIPTION_MAX)))
    }

    @Test
    fun aBlankDescriptionIsNone() {
        assertEquals(null, listDescription(null))
        assertEquals(null, listDescription(" \n "))
        assertEquals("Worth the walk", listDescription(" Worth the walk "))
    }
}
