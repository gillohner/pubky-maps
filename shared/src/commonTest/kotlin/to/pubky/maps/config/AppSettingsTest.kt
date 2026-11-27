package to.pubky.maps.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AppSettingsTest {
    private val defaults = AppSettings()

    private fun AppSettings.added(
        service: Service,
        url: String,
        name: String = "",
    ): AppSettings = assertIs<AppSettings.AddServerResult.Added>(withServerAdded(service, url, name)).settings

    private fun AppSettings.urls(service: Service) = servers[service].map { it.url }

    @Test
    fun startsWithTheDefaultServers() {
        for (service in Service.entries) {
            assertEquals(ServerDefaults.lists[service], defaults.servers[service])
            assertTrue(defaults.isDefaultServerList(service))
        }
    }

    @Test
    fun privacyDefaultsKeepEverythingOffThePublicWebExceptWhatIsPublicByNature() {
        val expected =
            mapOf(
                DataKind.SavedPlaces to StorageChoice.HomeserverPrivate,
                DataKind.HomeAndWork to StorageChoice.PhoneOnly,
                DataKind.Lists to StorageChoice.HomeserverPrivate,
                DataKind.CheckIns to StorageChoice.PhoneOnly,
                DataKind.RecordedTracks to StorageChoice.PhoneOnly,
                DataKind.LocationHistory to StorageChoice.PhoneOnly,
                DataKind.PhotoLocations to StorageChoice.PhoneOnly,
                DataKind.Tags to StorageChoice.Public,
                DataKind.ReviewsAndPosts to StorageChoice.Public,
            )
        assertEquals(expected, DataKind.entries.associateWith { defaults.storage(it) })
    }

    @Test
    fun locationHistoryAndHomeCanNeverBePublic() {
        assertFalse(StorageChoice.Public in DataKind.LocationHistory.choices)
        assertFalse(StorageChoice.Public in DataKind.HomeAndWork.choices)
        assertFailsWith<IllegalArgumentException> { defaults.withStorage(DataKind.LocationHistory, StorageChoice.Public) }
    }

    @Test
    fun tagsAndReviewsArePublicByNature() {
        assertFalse(DataKind.Tags.hasChoice)
        assertFalse(DataKind.ReviewsAndPosts.hasChoice)
        assertFailsWith<IllegalArgumentException> { defaults.withStorage(DataKind.Tags, StorageChoice.PhoneOnly) }
    }

    @Test
    fun aStorageChoiceIsKept() {
        val settings = defaults.withStorage(DataKind.Lists, StorageChoice.Public)

        assertEquals(StorageChoice.Public, settings.storage(DataKind.Lists))
        assertEquals(StorageChoice.HomeserverPrivate, settings.storage(DataKind.SavedPlaces))
    }

    @Test
    fun openInAppsAreOnExceptAppleMaps() {
        for (app in OpenInApp.entries) assertEquals(app != OpenInApp.AppleMaps, defaults.isOpenInEnabled(app), "$app")

        val settings = defaults.withOpenIn(OpenInApp.Waze, false).withOpenIn(OpenInApp.AppleMaps, true)
        assertFalse(settings.isOpenInEnabled(OpenInApp.Waze))
        assertTrue(settings.isOpenInEnabled(OpenInApp.AppleMaps))
    }

    @Test
    fun anOpenInSwitchBackAtItsDefaultFollowsTheDefaultAgain() {
        assertEquals(defaults, defaults.withOpenIn(OpenInApp.Waze, false).withOpenIn(OpenInApp.Waze, true))
    }

    @Test
    fun afterALostFileEveryKindIsKeptAsPrivatelyAsItCanBe() {
        val recovered = AppSettings.afterLostFile()

        for (kind in DataKind.entries) {
            val expected = if (kind.hasChoice) StorageChoice.PhoneOnly else StorageChoice.Public
            assertEquals(expected, recovered.storage(kind), "$kind")
        }
        assertEquals(defaults.servers[Service.Nexus], recovered.servers[Service.Nexus])
    }

    @Test
    fun followLocalRulesIsOnByDefault() {
        assertTrue(defaults.followLocalRules)
    }

    @Test
    fun anAddedServerGoesToTheEndWithItsHostAsName() {
        val settings = defaults.added(Service.Photon, "https://photon.example.org/")

        assertEquals(listOf("https://photon.komoot.io", "https://photon.example.org"), settings.urls(Service.Photon))
        assertEquals("photon.example.org", settings.servers[Service.Photon].last().name)
        // A search server credits its own host, like a map style (see anAddedPhotonServerIsCreditedForSearch).
        assertEquals(
            listOf(AttributionLink("photon.example.org", "https://photon.example.org"), ServerDefaults.openStreetMap),
            settings.servers[Service.Photon].last().attribution,
        )
        assertFalse(settings.isDefaultServerList(Service.Photon))
        assertTrue(settings.isDefaultServerList(Service.Nexus))
    }

    @Test
    fun anAddedMapStyleCreditsItsOwnServer() {
        val style = defaults.added(Service.MapStyleDark, "https://tiles.example.org/styles/night.json?v=2", "  Night  ")

        val entry = style.servers[Service.MapStyleDark].last()
        assertEquals("Night", entry.name)
        assertEquals(listOf(AttributionLink("Night", "https://tiles.example.org"), ServerDefaults.openStreetMap), entry.attribution)
        assertTrue(AttributionLink("Night", "https://tiles.example.org") in style.servers.allAttribution())
    }

    @Test
    fun anAddedPhotonServerIsCreditedForSearch() {
        val own = defaults.added(Service.Photon, "https://photon.example.org", "My Photon")

        val credit = AttributionLink("My Photon", "https://photon.example.org")
        assertEquals(listOf(credit, ServerDefaults.openStreetMap), own.servers[Service.Photon].last().attribution)
        assertTrue(credit in own.servers.searchAttribution())
        assertTrue(credit in own.servers.allAttribution())
        // Other services carry no credit of their own.
        assertEquals(
            emptyList(),
            defaults
                .added(Service.Nexus, "https://nexus.example.org")
                .servers[Service.Nexus]
                .last()
                .attribution,
        )
    }

    @Test
    fun theOpenStreetMapCreditStaysWhenEveryDefaultStyleIsGone() {
        var own = defaults
        for (service in listOf(Service.MapStyleLight, Service.MapStyleDark)) {
            own = own.added(service, "https://tiles.example.org/${service.key}.json", "Own")
            for (url in ServerDefaults.lists[service].map { it.url }) own = own.withServerRemoved(service, url)
            assertEquals(1, own.servers[service].size)
        }

        assertEquals(1, own.servers.allAttribution().count { it == ServerDefaults.openStreetMap })
        for (service in listOf(Service.MapStyleLight, Service.MapStyleDark)) {
            assertTrue(ServerDefaults.openStreetMap in own.servers[service].single().attribution)
        }
    }

    @Test
    fun aBadOrDuplicateAddressIsRefusedWithAReason() {
        val bad = assertIs<AppSettings.AddServerResult.Refused>(defaults.withServerAdded(Service.Nexus, "http://nexus.example"))
        assertTrue(bad.reason.startsWith("Use https://"))

        val duplicate =
            assertIs<AppSettings.AddServerResult.Refused>(defaults.withServerAdded(Service.Photon, "HTTPS://Photon.Komoot.io/"))
        assertEquals("This server is already in the list.", duplicate.reason)
    }

    @Test
    fun serversMoveUpAndDown() {
        val light = Service.MapStyleLight
        val (first, second) = ServerDefaults.lists[light].map { it.url }
        val added = defaults.added(light, "https://tiles.example.org/style.json")

        val third = "https://tiles.example.org/style.json"
        assertEquals(listOf(third, first, second), added.withServerMoved(light, third, -2).urls(light))
        assertEquals(listOf(second, first, third), added.withServerMoved(light, first, 1).urls(light))
        assertEquals(added, added.withServerMoved(light, first, -1), "past the top")
        assertEquals(added, added.withServerMoved(light, third, 1), "past the end")
        assertEquals(added, added.withServerMoved(light, "https://gone.example", 1), "no longer in the list")
    }

    @Test
    fun aDroppedServerTakesItsNewPlaceAndTheOthersKeepTheirOrder() {
        val light = Service.MapStyleLight
        val (first, second) = ServerDefaults.lists[light].map { it.url }
        val third = "https://tiles.example.org/style.json"
        val added = defaults.added(light, third)

        assertEquals(listOf(third, first, second), added.withServerMovedTo(light, third, 0).urls(light))
        assertEquals(listOf(second, third, first), added.withServerMovedTo(light, first, 2).urls(light))
        assertEquals(listOf(second, first, third), added.withServerMovedTo(light, first, 1).urls(light))
        assertEquals(added, added.withServerMovedTo(light, second, 1), "dropped where it was")
        assertEquals(added, added.withServerMovedTo(light, first, 3), "past the end")
        assertEquals(added, added.withServerMovedTo(light, first, -1), "before the top")
        assertEquals(added, added.withServerMovedTo(light, "https://gone.example", 0), "no longer in the list")
    }

    @Test
    fun serversCanBeRemovedButNotTheLastOne() {
        val relays = defaults.withServerRemoved(Service.PkarrRelay, "https://pkarr.pubky.app")

        assertEquals(listOf("https://pkarr.pubky.org"), relays.urls(Service.PkarrRelay))
        assertEquals(relays, relays.withServerRemoved(Service.PkarrRelay, "https://pkarr.pubky.org"), "the last one stays")
        assertEquals(defaults, defaults.withServerRemoved(Service.AuthRelay, "https://httprelay.pubky.app/inbox"))
    }

    @Test
    fun aRepeatedChangeFromAnOlderScreenNeverHitsAnotherServer() {
        val light = Service.MapStyleLight
        val own = "https://tiles.example.org/style.json"
        val three = defaults.added(light, own)

        // A double tap on Remove of the second server: the second tap must not remove the next one.
        val once = three.withServerRemoved(light, three.urls(light)[1])
        assertEquals(once, once.withServerRemoved(light, three.urls(light)[1]))
        assertEquals(listOf(three.urls(light)[0], own), once.urls(light))
    }

    @Test
    fun resetBringsBackTheDefaults() {
        val edited =
            defaults
                .added(
                    Service.Pinakes,
                    "https://pinakes.example.org",
                ).withServerMoved(Service.Pinakes, "https://pinakes.example.org", -1)

        val reset = edited.withServersReset(Service.Pinakes)

        assertEquals(ServerDefaults.lists[Service.Pinakes], reset.servers[Service.Pinakes])
        assertTrue(reset.isDefaultServerList(Service.Pinakes))
        assertEquals(defaults, reset)
    }

    @Test
    fun aListEditedBackToTheDefaultsFollowsTheDefaultsAgain() {
        val first = ServerDefaults.lists[Service.MapStyleLight].first().url
        val moved = defaults.withServerMoved(Service.MapStyleLight, first, 1)
        assertFalse(moved.isDefaultServerList(Service.MapStyleLight))

        val movedBack = moved.withServerMoved(Service.MapStyleLight, first, -1)
        assertTrue(movedBack.isDefaultServerList(Service.MapStyleLight))
        assertEquals(defaults, movedBack)
    }

    @Test
    fun anEmptyOverrideOrAChoiceAKindDoesNotOfferIsRejected() {
        assertFailsWith<IllegalArgumentException> { AppSettings(serverOverrides = mapOf(Service.Nexus to emptyList())) }
        assertFailsWith<IllegalArgumentException> {
            AppSettings(storageChoices = mapOf(DataKind.HomeAndWork to StorageChoice.Public))
        }
    }

    @Test
    fun synonymHostsAreRecognised() {
        assertTrue(SynonymHosts.isSynonymServer("https://pkarr.pubky.app"))
        assertTrue(SynonymHosts.isSynonymServer("https://httprelay.pubky.app/inbox"))
        assertTrue(SynonymHosts.isSynonymServer("https://pkarr.pubky.org"))
        assertTrue(SynonymHosts.isSynonymServer("https://homeserver.staging.synonym.to"))
        assertTrue(SynonymHosts.isSynonymServer(ServerDefaults.lists[Service.Pinakes].first().url))
        assertTrue(SynonymHosts.isSynonymHost("PUBKY.APP."))
        assertFalse(SynonymHosts.isSynonymServer("https://photon.komoot.io"))
        assertFalse(SynonymHosts.isSynonymServer("https://notpubky.app"))
        assertFalse(SynonymHosts.isSynonymServer("https://pubky.app.example.org"))
        assertFalse(SynonymHosts.isSynonymServer("not an address"))
    }
}
