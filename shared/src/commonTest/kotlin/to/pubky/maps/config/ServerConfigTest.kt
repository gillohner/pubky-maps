package to.pubky.maps.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ServerConfigTest {
    @Test
    fun theDefaultsHaveAFallbackForEachTheme() {
        val styles = ServerDefaults.mapStyles

        assertTrue(styles.light.size >= 2)
        assertTrue(styles.dark.size >= 2)
        assertEquals(styles.light, styles.forTheme(darkTheme = false))
        assertEquals(styles.dark, styles.forTheme(darkTheme = true))
    }

    @Test
    fun everyDefaultStyleCreditsOpenStreetMapAndUsesHttps() {
        for (source in ServerDefaults.mapStyles.light + ServerDefaults.mapStyles.dark) {
            assertTrue(source.styleUrl.startsWith("https://"), source.styleUrl)
            assertTrue(source.attribution.any { it.url == "https://www.openstreetmap.org/copyright" }, source.name)
        }
    }

    @Test
    fun allAttributionListsEachCreditOnce() {
        val credits = ServerDefaults.mapStyles.allAttribution()

        assertEquals(credits.distinct(), credits)
        assertEquals(1, credits.count { it.url == "https://www.openstreetmap.org/copyright" })
    }

    @Test
    fun aThemeWithoutStylesIsRejected() {
        val one = listOf(MapStyleSource("One", "https://one.example/style.json", emptyList()))

        assertFailsWith<IllegalArgumentException> { MapStyleServers(light = emptyList(), dark = one) }
        assertFailsWith<IllegalArgumentException> { MapStyleServers(light = one, dark = emptyList()) }
    }

    @Test
    fun theRepositoryPublishesEdits() {
        val repository = InMemoryServerConfigRepository()
        val own = MapStyleSource("Own", "https://tiles.example/style.json", emptyList())

        repository.update { it.copy(mapStyles = it.mapStyles.copy(light = listOf(own) + it.mapStyles.light)) }

        assertEquals(
            own,
            repository.config.value.mapStyles.light
                .first(),
        )
        assertEquals(ServerDefaults.mapStyles.dark, repository.config.value.mapStyles.dark)
    }
}
