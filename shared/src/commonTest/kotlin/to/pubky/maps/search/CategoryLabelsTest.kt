package to.pubky.maps.search

import to.pubky.maps.links.GeoPoint
import to.pubky.maps.place.PlaceCardInfo
import to.pubky.maps.place.PlacePageState
import to.pubky.maps.place.PlaceRef
import to.pubky.maps.place.PlaceSource
import to.pubky.maps.place.categoryText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CategoryLabelsTest {
    private fun label(
        key: String,
        value: String,
    ) = categoryOf(key, value, photonType = null)

    @Test
    fun aShopIsCalledWhatPeopleCallIt() {
        assertEquals("Bakery", label("shop", "bakery"))
        assertEquals("Supermarket", label("shop", "supermarket"))
        assertEquals("Bookshop", label("shop", "books"))
        assertEquals("Shoe shop", label("shop", "shoes"))
        assertEquals("Convenience store", label("shop", "convenience"))
        assertEquals("Hairdresser", label("shop", "hairdresser"))
    }

    @Test
    fun otherShopsStillSayShop() {
        assertEquals("Gift shop", label("shop", "gift"))
        assertEquals("Mobile phone shop", label("shop", "mobile_phone"))
        assertEquals("Shop", label("shop", "yes"))
    }

    @Test
    fun abbreviationsAndPluralsReadAsNames() {
        assertEquals("ATM", label("amenity", "atm"))
        assertEquals("Town hall", label("amenity", "townhall"))
        assertEquals("Doctor", label("amenity", "doctors"))
        assertEquals("Café", label("amenity", "cafe"))
        assertEquals("Bus stop", label("highway", "bus_stop"))
        assertNull(categoryOf("boundary", "administrative", photonType = null))
    }

    @Test
    fun noLabelDoublesAWordOrEndsInShopShop() {
        for ((tag, name) in TAG_LABELS) {
            assertTrue(name.isNotBlank() && name.first().isUpperCase(), "$tag -> $name")
            assertTrue(!name.lowercase().endsWith("shop shop"), "$tag -> $name")
        }
    }

    @Test
    fun thePlacePageNamesTheCategoryAsSearchDoes() {
        val uri = "https://www.openstreetmap.org/node/1"
        val page =
            PlacePageState(
                place = PlaceRef(uri, "Babu's Bakery", GeoPoint(47.37, 8.53), null, PlaceSource.Map),
                card =
                    PlaceCardInfo(
                        id = "p1",
                        uri = uri,
                        name = "Babu's Bakery",
                        type = null,
                        category = "shop/bakery",
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
                        facts = emptyMap(),
                    ),
            )
        assertEquals("Bakery", page.categoryText())
    }
}
