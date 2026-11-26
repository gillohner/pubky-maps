package to.pubky.maps.account

import kotlin.test.Test
import kotlin.test.assertEquals

class AccountPlacesTest {
    // Spells places as the core does for these: https, www., no trailing slash.
    private val normalize: (String) -> String = { place ->
        place
            .removeSuffix("/")
            .replace("http://", "https://")
            .replace("https://openstreetmap.org/", "https://www.openstreetmap.org/")
    }

    private val names = mapOf("https://www.openstreetmap.org/node/2405123" to "Café Henrici")

    @Test
    fun aPlaceReadsAsItsStoredNameHoweverTheHomeserverSpellsIt() {
        assertEquals("Café Henrici", accountPlaceLabel("https://www.openstreetmap.org/node/2405123", names, normalize))
        assertEquals("Café Henrici", accountPlaceLabel("http://openstreetmap.org/node/2405123/", names, normalize))
    }

    @Test
    fun aPlaceWithoutANameReadsAsItsLinkInWordsHoweverTheHomeserverSpellsIt() {
        assertEquals("OpenStreetMap way 48210334", accountPlaceLabel("https://openstreetmap.org/way/48210334/", names, normalize))
        assertEquals("47.37689, 8.54169", accountPlaceLabel("geo:47.37689,8.54169", names, normalize))
    }
}
