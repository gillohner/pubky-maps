package to.pubky.maps.offline

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.Dispatchers
import to.pubky.maps.links.GeoPoint
import to.pubky.maps.links.OsmId
import to.pubky.maps.links.OsmType
import to.pubky.maps.offline.db.OfflinePlacesDatabase
import to.pubky.maps.place.KeptDetails
import java.io.File
import java.nio.file.Files
import java.util.Properties
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.measureTime

/** The OSM details of the offline areas on this machine's SQLite (the app uses Android's), in the offline places' file. */
class OfflineDetailsDatabaseTest {
    private val dir: File = Files.createTempDirectory("offline-details").toFile()

    @AfterTest
    fun cleanUp() {
        dir.deleteRecursively()
    }

    private fun driver(name: String = "offline-places.db") = JdbcSqliteDriver("jdbc:sqlite:${File(dir, name).path}", Properties())

    private fun node(id: Long) = OsmId(OsmType.Node, id)

    @Test
    fun keepsEachAreasDetailsAndGivesAnElementThoseOfTheAreaFetchedLast() {
        val driver = driver()
        OfflinePlacesDatabase.Schema.create(driver)
        val store = SqlOfflineDetails(driver)
        assertEquals(emptyMap(), store.areas())
        assertNull(store.detailsOf(node(1)))

        store.replace(
            "zh",
            100,
            listOf(ElementDetails(node(1), mapOf("opening_hours" to "Mo-Fr 08:00-18:00", "phone" to "+41 44 000 00 00"))),
        )
        store.replace("west", 200, listOf(ElementDetails(node(1), mapOf("opening_hours" to "24/7"))))
        store.replace("zh", 50, listOf(ElementDetails(OsmId(OsmType.Way, 1), mapOf("website" to "https://example.ch"))))

        assertEquals(mapOf("zh" to 50L, "west" to 200L), store.areas())
        assertEquals(KeptDetails(mapOf("opening_hours" to "24/7"), 200), store.detailsOf(node(1)))
        assertEquals(KeptDetails(mapOf("website" to "https://example.ch"), 50), store.detailsOf(OsmId(OsmType.Way, 1)))
        store.delete("west")
        assertNull(store.detailsOf(node(1)), "the area that had it replaced its details, the other was deleted")
        assertEquals(mapOf("zh" to 50L), store.areas())
    }

    @Test
    fun detailsWrittenInAShapeThisVersionCannotReadAreAsIfNotKept() {
        val driver = driver()
        OfflinePlacesDatabase.Schema.create(driver)
        driver.execute(null, "INSERT INTO details_area VALUES ('zh', 1)", 0)
        driver.execute(null, "INSERT INTO details VALUES ('zh', 'Node', 1, '[\"not a map\"]')", 0)
        assertNull(SqlOfflineDetails(driver).detailsOf(node(1)))
    }

    @Test
    fun theDetailsLiveBesideThePlacesAndDoNotGoWhenAnAreasPlacesAreReadAgain() {
        val driver = driver()
        OfflinePlacesDatabase.Schema.create(driver)
        val index = SqlOfflinePlaceIndex(driver, io = Dispatchers.Unconfined)
        val details = SqlOfflineDetails(driver)
        details.replace("zh", 1, listOf(ElementDetails(node(1), mapOf("phone" to "+41"))))
        index.replace("zh", 2, sequenceOf(OfflinePlace("Café", node(1), "amenity", "cafe", "Café", GeoPoint(47.37, 8.54))))
        index.delete("zh")
        assertEquals(mapOf("phone" to "+41"), details.detailsOf(node(1))?.facts)
    }

    @Test
    fun aVersionThreeDatabaseKeepsItsPlacesAndGetsTheDetailsTables() {
        val driver = driver("v3.db")
        // The schema of version 3.
        listOf(
            "CREATE TABLE area (id TEXT NOT NULL PRIMARY KEY, new_data_at INTEGER NOT NULL)",
            "CREATE TABLE place (id INTEGER PRIMARY KEY AUTOINCREMENT, area TEXT NOT NULL, name TEXT NOT NULL, osm_type TEXT, " +
                "osm_id INTEGER, tag_key TEXT NOT NULL, tag_value TEXT NOT NULL, category TEXT, lat REAL NOT NULL, lon REAL NOT NULL, " +
                "locality TEXT)",
            "CREATE INDEX place_area ON place(area)",
            "CREATE INDEX place_osm ON place(osm_id, osm_type) WHERE osm_id IS NOT NULL",
            "CREATE INDEX place_point ON place(lat, lon)",
            "CREATE INDEX place_label ON place(lat, lon) WHERE tag_key = 'place'",
            "CREATE TABLE word (word TEXT NOT NULL, place INTEGER NOT NULL)",
            "CREATE INDEX word_word ON word(word)",
            "CREATE INDEX word_place ON word(place)",
            "INSERT INTO area VALUES ('a', 5)",
            "INSERT INTO place VALUES (1, 'a', 'Café Rosa', 'Node', 1, 'amenity', 'cafe', 'Café', 47.3700, 8.5410, 'Zürich')",
        ).forEach { driver.execute(null, it, 0) }

        OfflinePlacesDatabase.Schema.migrate(driver, 3, OfflinePlacesDatabase.Schema.version)

        val fresh = driver("fresh.db").also { OfflinePlacesDatabase.Schema.create(it) }
        assertEquals(layout(fresh), layout(driver), "the migrated file has the tables and indexes of a new one")
        assertEquals(mapOf("a" to 5L), SqlOfflinePlaceIndex(driver, io = Dispatchers.Unconfined).areas())
        val details = SqlOfflineDetails(driver)
        assertEquals(emptyMap(), details.areas(), "no details yet: every area is fetched")
        details.replace("a", 7, listOf(ElementDetails(node(1), mapOf("wheelchair" to "yes"))))
        assertEquals(KeptDetails(mapOf("wheelchair" to "yes"), 7), details.detailsOf(node(1)))
    }

    /** Each table's columns (name, type, not null, primary key) and each index's table and columns, by name. */
    private fun layout(driver: JdbcSqliteDriver): Map<String, List<String>> {
        fun rows(
            sql: String,
            columns: Int,
        ): List<List<String?>> =
            driver
                .executeQuery(null, sql, { c ->
                    QueryResult.Value(buildList { while (c.next().value) add((0 until columns).map { c.getString(it) }) })
                }, 0)
                .value
        return rows("SELECT type, name, tbl_name FROM sqlite_master WHERE name NOT LIKE 'sqlite_%'", 3).associate { (type, name, table) ->
            val info =
                if (type == "table") {
                    rows("SELECT name, type, \"notnull\", pk FROM pragma_table_info('$name')", 4)
                } else {
                    rows("SELECT name FROM pragma_index_info('$name')", 1)
                }
            "$type $name" to listOf(table!!) + info.map { it.joinToString(" ") } +
                listOfNotNull(
                    rows(
                        "SELECT sql FROM sqlite_master WHERE name = '$name'",
                        1,
                    ).single().single()?.substringAfter(" WHERE ", "")?.ifEmpty { null },
                )
        }
    }

    @Test
    fun theLargestAreaOneDownloadMayTakeIsKeptInSecondsAndReadInAMillisecond() {
        val driver = driver()
        OfflinePlacesDatabase.Schema.create(driver)
        val store = SqlOfflineDetails(driver)
        // 84,000 elements: Zürich and its surroundings, about the largest area one download may take.
        val details =
            (1L..84_000L).map {
                ElementDetails(
                    node(it),
                    mapOf("opening_hours" to "Mo-Fr 08:00-18:00", "website" to "https://example.ch/$it"),
                )
            }
        val write = measureTime { store.replace("zh", 1, details) }
        assertTrue(write.inWholeSeconds < 10, "writing took $write")
        // The fastest of a few warm rounds: the first reads run before the JIT has compiled the read
        // path and take 2-4 times a warm read. A read that misses the index scans 84,000 rows in
        // every round.
        val read = fastestOf { repeat(100) { assertNotNull(store.detailsOf(node(42_000L + it))) } }
        assertTrue(read.inWholeMilliseconds < 100, "100 reads took $read at best")
    }
}
