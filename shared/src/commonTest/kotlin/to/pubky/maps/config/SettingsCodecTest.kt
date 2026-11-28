package to.pubky.maps.config

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SettingsCodecTest {
    private val codec = SettingsCodec()

    private val edited =
        assertAdded(AppSettings().withServerAdded(Service.MapStyleLight, "https://tiles.example.org/style.json", "Mine"))
            .withServerMoved(Service.MapStyleLight, "https://tiles.example.org/style.json", -2)
            .withServerRemoved(Service.PkarrRelay, "https://pkarr.pubky.org")
            .withStorage(DataKind.CheckIns, StorageChoice.Public)
            .withOpenIn(OpenInApp.Waze, false)
            .copy(followLocalRules = false, voiceGuidance = false, streetPhotosLayer = true)

    private fun assertAdded(result: AppSettings.AddServerResult) = (result as AppSettings.AddServerResult.Added).settings

    @Test
    fun everySettingSurvivesARoundTrip() {
        assertEquals(edited, codec.decode(codec.encode(edited)))
        assertEquals(AppSettings(), codec.decode(codec.encode(AppSettings())))
    }

    @Test
    fun theFileNamesItsSchemaVersionAndOnlyHoldsChanges() {
        val text = codec.encode(AppSettings().withOpenIn(OpenInApp.Waze, false))

        assertTrue("\"schemaVersion\": $SETTINGS_SCHEMA_VERSION" in text, text)
        assertTrue("\"waze\": false" in text, text)
        assertFalse("photon" in text, text)
    }

    @Test
    fun settingsEqualToTheDefaultsAreNotWritten() {
        val text = codec.encode(AppSettings().withOpenIn(OpenInApp.Waze, false).withOpenIn(OpenInApp.Waze, true))

        assertFalse("waze" in text, text)
        assertFalse("followLocalRules" in text, text)
        assertTrue("\"followLocalRules\": false" in codec.encode(AppSettings(followLocalRules = false)))
        assertFalse("voiceGuidance" in text, text)
        assertTrue("\"voiceGuidance\": false" in codec.encode(AppSettings(voiceGuidance = false)))
        assertFalse("streetPhotosLayer" in text, text)
        assertTrue("\"streetPhotosLayer\": true" in codec.encode(AppSettings(streetPhotosLayer = true)))
    }

    @Test
    fun theStreetPhotoLayerIsOffUntilTheUserTurnsItOn() {
        assertFalse(codec.decode("{}").streetPhotosLayer)
        assertTrue(codec.decode("""{"schemaVersion": $SETTINGS_SCHEMA_VERSION, "streetPhotosLayer": true}""").streetPhotosLayer)
    }

    @Test
    fun guidanceSpeaksUntilTheUserSwitchesItOff() {
        assertTrue(codec.decode("{}").voiceGuidance)
        assertFalse(codec.decode("""{"schemaVersion": $SETTINGS_SCHEMA_VERSION, "voiceGuidance": false}""").voiceGuidance)
    }

    @Test
    fun aStorageChoiceTheKindDoesNotOfferBecomesItsMostPrivateOne() {
        val settings =
            codec.decode(
                """{ "storage": { "saved_places": "public", "lists": "elsewhere", "photo_locations": "homeserver_private" } }""",
            )

        assertEquals(StorageChoice.PhoneOnly, settings.storage(DataKind.SavedPlaces))
        assertEquals(StorageChoice.PhoneOnly, settings.storage(DataKind.Lists))
        assertEquals(StorageChoice.PhoneOnly, settings.storage(DataKind.PhotoLocations))
    }

    @Test
    fun creditLinksThatAreNotServerAddressesAreDropped() {
        val text =
            """{ "servers": { "map_style_dark": [{ "name": "Own", "url": "https://tiles.example.org/dark.json",
            "attribution": [{ "label": "Own", "url": "javascript:alert(1)" }, { "label": "OSM", "url": "https://www.openstreetmap.org/copyright" }] }] } }"""

        val credits =
            codec
                .decode(text)
                .servers[Service.MapStyleDark]
                .single()
                .attribution

        assertEquals(listOf(AttributionLink("OSM", "https://www.openstreetmap.org/copyright")), credits)
    }

    @Test
    fun aMigrationThatFailsMakesTheFileUnreadableInsteadOfCrashing() {
        val v2 = SettingsCodec(schemaVersion = 2, migrations = mapOf(1 to SettingsMigration { error("unexpected file") }))

        assertFailsWith<UnreadableSettingsException> { v2.decode("{}") }
    }

    @Test
    fun anEmptyDocumentGivesTheDefaults() {
        assertEquals(AppSettings(), codec.decode("{}"))
    }

    @Test
    fun aFileFromANewerVersionKeepsWhatThisVersionUnderstands() {
        val newer =
            """
            {
              "schemaVersion": 7,
              "servers": {
                "photon": [{ "name": "Own", "url": "https://photon.example.org", "weight": 3 }],
                "routing": [{ "url": "https://valhalla.example.org" }]
              },
              "storage": { "lists": "public", "voice_notes": "public" },
              "openIn": { "waze": false, "magic_earth": true },
              "followLocalRules": false,
              "theme": "sepia"
            }
            """.trimIndent()

        val settings = codec.decode(newer)

        assertEquals(listOf(ServerEntry("Own", "https://photon.example.org")), settings.servers[Service.Photon])
        assertEquals(StorageChoice.Public, settings.storage(DataKind.Lists))
        assertFalse(settings.isOpenInEnabled(OpenInApp.Waze))
        assertFalse(settings.followLocalRules)
        assertEquals(setOf(Service.Photon), settings.serverOverrides.keys)
    }

    @Test
    fun invalidEntriesFallBackToTheDefaults() {
        val text =
            """
            {
              "schemaVersion": 1,
              "servers": {
                "nexus": [{ "url": "http://nexus.example.org" }, { "url": "not a url" }],
                "photon": [{ "url": "https://photon.example.org/" }, { "url": "https://PHOTON.example.org" }, { "url": "https://b.example" }]
              },
              "storage": { "location_history": "public", "check_ins": "somewhere" }
            }
            """.trimIndent()

        val settings = codec.decode(text)

        assertTrue(settings.isDefaultServerList(Service.Nexus))
        assertEquals(listOf("https://photon.example.org", "https://b.example"), settings.servers[Service.Photon].map { it.url })
        assertEquals("photon.example.org", settings.servers[Service.Photon].first().name)
        assertEquals(StorageChoice.PhoneOnly, settings.storage(DataKind.LocationHistory))
        assertEquals(StorageChoice.PhoneOnly, settings.storage(DataKind.CheckIns))
    }

    @Test
    fun anUnreadableFileIsReported() {
        for (text in listOf("", "not json", "[]", "{\"servers\": \"photon\"}", "{\"schemaVersion\": \"one\"")) {
            assertFailsWith<UnreadableSettingsException>(text) { codec.decode(text) }
        }
    }

    @Test
    fun olderFilesAreMigratedOneVersionAtATime() {
        // A future version 3 that renamed "follow_local_rules" (version 1) to "followLocalRules" in
        // version 2, and "open_in" to "openIn" in version 3.
        val steps = mutableListOf<Int>()
        val migrations =
            mapOf(
                1 to
                    SettingsMigration { doc ->
                        steps +=
                            doc
                                .getValue("schemaVersion")
                                .jsonPrimitive.content
                                .toInt()
                        JsonObject(doc - "follow_local_rules" + ("followLocalRules" to doc.getValue("follow_local_rules")))
                    },
                2 to
                    SettingsMigration { doc ->
                        steps +=
                            doc
                                .getValue("schemaVersion")
                                .jsonPrimitive.content
                                .toInt()
                        JsonObject(doc - "open_in" + ("openIn" to doc.getValue("open_in")))
                    },
            )
        val v3 = SettingsCodec(schemaVersion = 3, migrations = migrations)
        val v1File = """{ "schemaVersion": 1, "follow_local_rules": false, "open_in": { "osmand": false } }"""

        val settings = v3.decode(v1File)

        assertEquals(listOf(1, 2), steps)
        assertFalse(settings.followLocalRules)
        assertFalse(settings.isOpenInEnabled(OpenInApp.OsmAnd))
        assertTrue("\"schemaVersion\": 3" in v3.encode(settings))
    }

    @Test
    fun aFileWithoutAVersionIsReadAsTheFirstVersion() {
        val v2 =
            SettingsCodec(
                schemaVersion = 2,
                migrations = mapOf(1 to SettingsMigration { JsonObject(it + ("followLocalRules" to JsonPrimitive(false))) }),
            )

        assertFalse(v2.decode("{}").followLocalRules)
    }

    @Test
    fun aMissingMigrationMakesTheFileUnreadable() {
        val v2 = SettingsCodec(schemaVersion = 2, migrations = emptyMap())

        assertFailsWith<UnreadableSettingsException> { v2.decode("""{ "schemaVersion": 1 }""") }
    }
}
