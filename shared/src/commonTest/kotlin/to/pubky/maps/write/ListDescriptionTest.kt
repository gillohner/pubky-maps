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

    @Test
    fun aListPostsDescriptionIsRead() {
        val content = """{\"name\":\"Cake\",\"description\":\" Worth the walk \",\"items\":[{\"uri\":\"geo:1,2\"}]}"""
        val post = """{"kind":"collection","content":"$content"}"""
        assertEquals(ListPost("Cake", "Worth the walk", listOf("geo:1,2")), listOfPost(post))
        val without = """{"kind":"collection","content":"{\"name\":\"Cake\",\"items\":[]}"}"""
        assertEquals(ListPost("Cake", null, emptyList()), listOfPost(without))
    }
}
