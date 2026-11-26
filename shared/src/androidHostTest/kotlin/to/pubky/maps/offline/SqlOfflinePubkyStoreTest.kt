package to.pubky.maps.offline

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import to.pubky.maps.offline.pubky.db.OfflinePubkyDatabase
import to.pubky.maps.place.GeoBox
import to.pubky.maps.place.MapPlace
import java.io.File
import java.nio.file.Files
import java.util.Properties
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

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
        store.replace("zh", zurich, fetchedAt = 1_000, places = listOf(kunsthaus, schober, oerlikon))
        driver.close()

        val (again, reopened) = open()
        assertEquals(mapOf("zh" to 1_000L), reopened.areas())
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
        store.replace("zh", zurich, fetchedAt = 1_000, places = listOf(kunsthaus, schober))
        store.replace("zh", zurich, fetchedAt = 2_000, places = listOf(kunsthaus.copy(name = "Kunsthaus Zürich")))
        assertEquals(mapOf("zh" to 2_000L), store.areas())
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
        store.replace("centre", centre, fetchedAt = 2_000, places = listOf(kunsthaus.copy(name = "Kunsthaus Zürich")))
        store.replace("zh", zurich, fetchedAt = 1_000, places = listOf(kunsthaus))
        assertEquals(listOf("Kunsthaus Zürich"), store.placesIn(zurich, limit = 10)?.map { it.name })
        store.replace("zh", zurich, fetchedAt = 3_000, places = listOf(kunsthaus.copy(name = "Kunsthaus")))
        assertEquals(listOf("Kunsthaus"), store.placesIn(zurich, limit = 10)?.map { it.name })
        driver.close()
    }

    @Test
    fun aPlaceOfTwoOverlappingAreasCountsOnceAndStaysWithTheOther() {
        val (driver, store) = open()
        store.replace("zh", zurich, fetchedAt = 1_000, places = listOf(kunsthaus, schober))
        store.replace("centre", GeoBox(8.5, 47.3125, 8.625, 47.375), fetchedAt = 1_500, places = listOf(kunsthaus))
        assertEquals(listOf(schober.uri, kunsthaus.uri), store.placesIn(zurich, limit = 10)?.map { it.uri })
        store.delete("zh")
        assertEquals(listOf(kunsthaus), store.placesIn(zurich, limit = 10))
        driver.close()
    }
}
