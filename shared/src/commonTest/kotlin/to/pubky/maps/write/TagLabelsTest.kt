package to.pubky.maps.write

import to.pubky.maps.links.GeoPoint
import to.pubky.maps.place.PlaceCardInfo
import to.pubky.maps.place.PlacePageState
import to.pubky.maps.place.PlaceRef
import to.pubky.maps.place.PlaceSocial
import to.pubky.maps.place.PlaceSource
import to.pubky.maps.place.PlaceTag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class TagLabelsTest {
    private fun valid(text: String) = assertIs<LabelResult.Valid>(tagLabel(text)).label

    @Test
    fun labelsFollowTheSpecRules() {
        assertEquals("cafe", valid("Café"))
        assertEquals("strasse", valid("Straße"))
        assertEquals("good-coffee", valid("  Good   coffee "))
        assertEquals("fast-food", valid("fast_food"))
        assertEquals("ab", valid("a:b"))
        assertEquals("ab", valid("a,b"))
        assertEquals("smorrebrod", valid("Smørrebrød"))
        // Other scripts are kept as they are.
        assertEquals("咖啡", valid("咖啡"))
        assertEquals(TAG_LABEL_MAX, valid("a".repeat(TAG_LABEL_MAX)).length)
    }

    @Test
    fun emptyAndLongLabelsAreRefusedWithAReason() {
        assertIs<LabelResult.Invalid>(tagLabel("   "))
        assertIs<LabelResult.Invalid>(tagLabel(":,"))
        val long = assertIs<LabelResult.Invalid>(tagLabel("a".repeat(TAG_LABEL_MAX + 1)))
        assertEquals("A tag has at most 20 characters (21 now).", long.reason)
    }

    @Test
    fun suggestionsAreThePlacesTopTagsThenItsCategoryWords() {
        val page =
            PlacePageState(
                place =
                    PlaceRef(
                        "https://www.openstreetmap.org/node/1",
                        "Schober",
                        GeoPoint(47.0, 8.0),
                        null,
                        PlaceSource.Map,
                        category = "Café",
                    ),
                card =
                    PlaceCardInfo(
                        id = "p1",
                        uri = "https://www.openstreetmap.org/node/1",
                        name = "Schober",
                        type = null,
                        category = "amenity/cafe",
                        address = null,
                        lat = null,
                        lon = null,
                        description = null,
                        image = null,
                        credits = emptyList(),
                        attribution = null,
                        alsoOn = emptyList(),
                        refs = emptyList(),
                        collections = 0,
                        facts = mapOf("cuisine" to "coffee_shop;cake"),
                    ),
                social =
                    PlaceSocial(
                        tags = listOf(PlaceTag("hot-chocolate", 2, emptyList(), false), PlaceTag("cozy", 5, emptyList(), false)),
                        resourceIds = emptyList(),
                        replyCount = 0,
                        embedCount = 0,
                    ),
            )

        val suggestions = tagSuggestions(page, mine = setOf("cozy"))

        assertEquals("hot-chocolate", suggestions.first())
        assertEquals(setOf("hot-chocolate", "cafe", "coffee-shop", "cake"), suggestions.toSet())
    }

    @Test
    fun withoutAPageThereIsNothingToSuggest() {
        assertEquals(emptyList(), tagSuggestions(null, emptySet()))
    }
}
