package to.pubky.maps.offline

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import to.pubky.maps.offline.pubky.db.OfflinePubkyDatabase
import to.pubky.maps.place.GeoBox
import to.pubky.maps.place.KeptSocial
import to.pubky.maps.place.MapPlace
import to.pubky.maps.place.PlaceSocial
import to.pubky.maps.place.PlaceTag
import java.io.File
import java.nio.file.Files
import java.util.Properties
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The Pubky places of the offline areas on this machine's SQLite (the app uses Android's). */
class SqlOfflinePubkyStoreTest {
    private val dir: File = Files.createTempDirectory("offline-pubky").toFile()
    private val file = File(dir, "offline-pubky-places.db")

    private fun open(): Pair<JdbcSqliteDriver, SqlOfflinePubkyStore> {
        val created = file.exists()
        val driver = JdbcSqliteDriver("jdbc:sqlite:${file.path}", Properties())
        if (!created) OfflinePubkyDatabase.Schema.create(driver)
        return driver to SqlOfflinePubkyStore(driver)
    }

    @AfterTest
    fun cleanUp() {
        dir.deleteRecursively()
    }

    private val zurich = GeoBox(8.5, 47.3125, 8.5625, 47.4375)
    private val kunsthaus =
        MapPlace("https://www.openstreetmap.org/relation/3768127", "Kunsthaus", 47.3702, 8.548, "Museum", "tourism/museum")
    private val schober = MapPlace("https://www.openstreetmap.org/node/1", "Schober", 47.3717, 8.5446, null, "amenity/cafe")
    private val oerlikon = MapPlace("https://www.openstreetmap.org/node/2", null, 47.41, 8.545, null, null)

    @Test
    fun keptPlacesAreReadInABoxAfterTheAppRestarts() {
        val (driver, store) = open()
        store.replace("zh", zurich, fetchedAt = 1_000, places = listOf(kunsthaus, schober, oerlikon), links = emptyMap())
        driver.close()

        val (again, reopened) = open()
        assertEquals(mapOf("zh" to KeptArea(1_000L, socialAt = null)), reopened.areas())
        val oldTown = GeoBox(8.535, 47.36, 8.55, 47.38)
        assertEquals(listOf(schober, kunsthaus), reopened.placesIn(oldTown, limit = 10)?.sortedBy { it.lon })
        assertEquals(1, reopened.placesIn(oldTown, limit = 1)?.size)
        // Inside the kept area but no place there: an empty answer, not "no offline area here".
        assertEquals(emptyList(), reopened.placesIn(GeoBox(8.5, 47.32, 8.51, 47.33), limit = 10))
        // No kept area overlaps.
        assertNull(reopened.placesIn(GeoBox(7.4, 46.9, 7.5, 47.0), limit = 10))
        // The next cell of the grid only touches the kept area: what the map shows stays.
        assertNull(reopened.placesIn(GeoBox(8.5625, 47.3125, 8.625, 47.4375), limit = 10))
        again.close()
    }

    @Test
    fun aReplaceDropsThePlacesThatAreGoneAndADeleteDropsTheArea() {
        val (driver, store) = open()
        store.replace("zh", zurich, fetchedAt = 1_000, places = listOf(kunsthaus, schober), links = emptyMap())
        store.replace("zh", zurich, fetchedAt = 2_000, places = listOf(kunsthaus.copy(name = "Kunsthaus Zürich")), links = emptyMap())
        assertEquals(mapOf("zh" to KeptArea(2_000L, socialAt = null)), store.areas())
        assertEquals(listOf("Kunsthaus Zürich"), store.placesIn(zurich, limit = 10)?.map { it.name })
        store.delete("zh")
        assertEquals(emptyMap(), store.areas())
        assertNull(store.placesIn(zurich, limit = 10))
        driver.close()
    }

    @Test
    fun aPlaceOfTwoAreasIsShownAsTheAreaFetchedLastHasIt() {
        val (driver, store) = open()
        val centre = GeoBox(8.5, 47.3125, 8.625, 47.375)
        store.replace("centre", centre, fetchedAt = 2_000, places = listOf(kunsthaus.copy(name = "Kunsthaus Zürich")), links = emptyMap())
        store.replace("zh", zurich, fetchedAt = 1_000, places = listOf(kunsthaus), links = emptyMap())
        assertEquals(listOf("Kunsthaus Zürich"), store.placesIn(zurich, limit = 10)?.map { it.name })
        store.replace("zh", zurich, fetchedAt = 3_000, places = listOf(kunsthaus.copy(name = "Kunsthaus")), links = emptyMap())
        assertEquals(listOf("Kunsthaus"), store.placesIn(zurich, limit = 10)?.map { it.name })
        driver.close()
    }

    @Test
    fun aPlaceOfTwoOverlappingAreasCountsOnceAndStaysWithTheOther() {
        val (driver, store) = open()
        store.replace("zh", zurich, fetchedAt = 1_000, places = listOf(kunsthaus, schober), links = emptyMap())
        store.replace("centre", GeoBox(8.5, 47.3125, 8.625, 47.375), fetchedAt = 1_500, places = listOf(kunsthaus), links = emptyMap())
        assertEquals(listOf(schober.uri, kunsthaus.uri), store.placesIn(zurich, limit = 10)?.map { it.uri })
        store.delete("zh")
        assertEquals(listOf(kunsthaus), store.placesIn(zurich, limit = 10))
        driver.close()
    }

    private fun social(vararg labels: String) =
        PlaceSocial(
            labels.map { PlaceTag(it, count = 1, taggers = listOf("p"), mine = false) },
            listOf("r"),
            replyCount = 2,
            embedCount = 0,
        )

    private val kunsthausLinks = mapOf(kunsthaus.uri to kunsthaus.uri, "http://www.openstreetmap.org/relation/3768127" to kunsthaus.uri)

    @Test
    fun keptTagsAreReadByAnyLinkOfThePlaceAfterTheAppRestartsFromTheNewestTags() {
        val (driver, store) = open()
        val centre = GeoBox(8.5, 47.3125, 8.625, 47.375)
        store.replace(
            "zh",
            zurich,
            fetchedAt = 1_000,
            places = listOf(kunsthaus, schober),
            links =
                kunsthausLinks + (schober.uri to schober.uri),
        )
        store.replaceSocial("zh", fetchedAt = 1_100, social = mapOf(kunsthaus.uri to social("art")))
        store.replace("centre", centre, fetchedAt = 500, places = listOf(kunsthaus), links = kunsthausLinks)
        store.replaceSocial("centre", fetchedAt = 2_000, social = mapOf(kunsthaus.uri to social("museum")))
        driver.close()

        val (again, reopened) = open()
        assertEquals(mapOf("zh" to KeptArea(1_000, 1_100), "centre" to KeptArea(500, 2_000)), reopened.areas())
        assertEquals(kunsthausLinks.keys.toList(), reopened.links("centre").keys.toList())
        // By a link people referenced; the newest tags win, whenever the places were fetched.
        val other = listOf("https://example.com/other", "http://www.openstreetmap.org/relation/3768127")
        assertEquals(KeptSocial(social("museum"), 2_000), reopened.socialOf(other))
        // A place Nexus did not know has no tags kept.
        assertNull(reopened.socialOf(listOf(schober.uri)))
        assertNull(reopened.socialOf(emptyList()))
        reopened.delete("centre")
        assertEquals(KeptSocial(social("art"), 1_100), reopened.socialOf(listOf(kunsthaus.uri)))
        reopened.delete("zh")
        assertNull(reopened.socialOf(listOf(kunsthaus.uri)))
        assertEquals(emptyMap(), reopened.links("zh"))
        again.close()
    }

    @Test
    fun newPlacesKeepTheAreasTagsAndTheirDateUntilNexusAnswers() {
        val (driver, store) = open()
        store.replace("zh", zurich, fetchedAt = 1_000, places = listOf(kunsthaus), links = kunsthausLinks)
        assertEquals(KeptArea(1_000, socialAt = null), store.areas()["zh"])
        assertNull(store.socialOf(listOf(kunsthaus.uri)))
        store.replaceSocial("zh", fetchedAt = 1_000, social = mapOf(kunsthaus.uri to social("art")))
        // Refreshed while Nexus does not answer: the old tags, dated when Nexus gave them.
        store.replace(
            "zh",
            zurich,
            fetchedAt = 2_000,
            places = listOf(kunsthaus, schober),
            links =
                kunsthausLinks + (schober.uri to schober.uri),
        )
        assertEquals(KeptArea(2_000, socialAt = 1_000), store.areas()["zh"])
        assertTrue(store.areas().getValue("zh").socialMissing)
        assertEquals(2, store.placesIn(zurich, limit = 10)?.size)
        assertEquals(KeptSocial(social("art"), 1_000), store.socialOf(listOf(kunsthaus.uri)))
        // Nexus answers again: its answer replaces the area's tags.
        store.replaceSocial("zh", fetchedAt = 3_000, social = mapOf(schober.uri to social("cake")))
        assertFalse(store.areas().getValue("zh").socialMissing)
        assertNull(store.socialOf(listOf(kunsthaus.uri)))
        assertEquals(KeptSocial(social("cake"), 3_000), store.socialOf(listOf(schober.uri)))
        driver.close()
    }

    @Test
    fun anAreaKeptByTheVersionBeforeTheTagsIsKeptWithoutThemAndItsPlacesAsLinks() {
        val driver = JdbcSqliteDriver("jdbc:sqlite:${file.path}", Properties())
        // Version 1 as #38 shipped it.
        driver.execute(
            null,
            "CREATE TABLE area (id TEXT NOT NULL PRIMARY KEY, fetched_at INTEGER NOT NULL, west REAL NOT NULL, " +
                "south REAL NOT NULL, east REAL NOT NULL, north REAL NOT NULL)",
            0,
        )
        driver.execute(
            null,
            "CREATE TABLE area_place (area TEXT NOT NULL, uri TEXT NOT NULL, name TEXT, lat REAL NOT NULL, lon REAL NOT NULL, " +
                "type TEXT, category TEXT, PRIMARY KEY (area, uri))",
            0,
        )
        driver.execute(null, "CREATE INDEX area_place_lat ON area_place(lat)", 0)
        driver.execute(null, "INSERT INTO area VALUES ('zh', 1000, 8.5, 47.3125, 8.5625, 47.4375)", 0)
        driver.execute(null, "INSERT INTO area_place VALUES ('zh', '${kunsthaus.uri}', 'Kunsthaus', 47.3702, 8.548, 'Museum', NULL)", 0)
        driver.execute(null, "INSERT INTO area_place VALUES ('zh', '${schober.uri}', 'Schober', 47.3717, 8.5446, NULL, NULL)", 0)
        assertEquals(2L, OfflinePubkyDatabase.Schema.version)
        OfflinePubkyDatabase.Schema.migrate(driver, 1, 2)
        val store = SqlOfflinePubkyStore(driver)
        assertEquals(mapOf("zh" to KeptArea(1_000, socialAt = null)), store.areas())
        assertEquals(listOf("Kunsthaus", "Schober"), store.placesIn(zurich, limit = 10)?.mapNotNull { it.name }?.sorted())
        assertEquals(mapOf(kunsthaus.uri to kunsthaus.uri, schober.uri to schober.uri), store.links("zh"))
        assertNull(store.socialOf(listOf(kunsthaus.uri)))
        store.replaceSocial("zh", fetchedAt = 2_000, social = mapOf(kunsthaus.uri to social("art")))
        assertEquals(KeptSocial(social("art"), 2_000), store.socialOf(listOf(kunsthaus.uri)))
        driver.close()
    }
}
