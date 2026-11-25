package to.pubky.maps.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ServerConfigTest {
    private val defaults = ServerDefaults.lists

    @Test
    fun everyServiceHasADefaultList() {
        for (service in Service.entries) assertTrue(defaults[service].isNotEmpty(), "$service")
    }

    @Test
    fun theMapStylesHaveAFallbackForEachTheme() {
        assertTrue(defaults.mapStyles(darkTheme = false).size >= 2)
        assertTrue(defaults.mapStyles(darkTheme = true).size >= 2)
        assertEquals(defaults[Service.MapStyleLight], defaults.mapStyles(darkTheme = false))
        assertEquals(defaults[Service.MapStyleDark], defaults.mapStyles(darkTheme = true))
    }

    @Test
    fun everyDefaultIsAValidHttpsAddressInItsNormalForm() {
        for (service in Service.entries) {
            for (entry in defaults[service]) {
                val parsed = ServerUrl.parse(entry.url)
                assertTrue(parsed is ServerUrl.Valid && parsed.url == entry.url, entry.url)
                assertTrue(entry.url.startsWith("https://"), entry.url)
            }
        }
    }

    @Test
    fun everyDefaultMapStyleCreditsOpenStreetMap() {
        for (entry in defaults[Service.MapStyleLight] + defaults[Service.MapStyleDark]) {
            assertTrue(entry.attribution.any { it.url == "https://www.openstreetmap.org/copyright" }, entry.name)
        }
    }

    @Test
    fun theDefaultMapStylesDoNotDependOnSynonym() {
        for (entry in defaults[Service.MapStyleLight] + defaults[Service.MapStyleDark]) {
            assertTrue(!SynonymHosts.isSynonymServer(entry.url), entry.url)
        }
    }

    @Test
    fun theDefaultsFollowThePubkySdk() {
        assertEquals(listOf("https://pkarr.pubky.app", "https://pkarr.pubky.org"), defaults[Service.PkarrRelay].map { it.url })
        assertEquals(listOf("https://httprelay.pubky.app/inbox"), defaults[Service.AuthRelay].map { it.url })
    }

    @Test
    fun allAttributionListsEachCreditOnce() {
        val credits = defaults.allAttribution()

        assertTrue(ServerDefaults.openStreetMap in credits)

        assertEquals(credits.distinct(), credits)
        assertEquals(1, credits.count { it.url == "https://www.openstreetmap.org/copyright" })
    }

    @Test
    fun aServiceWithoutServersIsRejected() {
        val lists = Service.entries.associateWith { defaults[it] }

        assertFailsWith<IllegalArgumentException> { ServerLists(lists - Service.Nexus) }
        assertFailsWith<IllegalArgumentException> { ServerLists(lists + (Service.Photon to emptyList())) }
    }

    @Test
    fun serviceKeysAreStableAndUnique() {
        assertEquals(
            Service.entries.size,
            Service.entries
                .map { it.key }
                .toSet()
                .size,
        )
        for (service in Service.entries) assertEquals(service, Service.fromKey(service.key))
        assertEquals(null, Service.fromKey("not_a_service"))
    }
}
