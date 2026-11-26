package to.pubky.maps.offline

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import to.pubky.maps.links.GeoPoint
import to.pubky.maps.links.OsmId
import to.pubky.maps.links.OsmType
import to.pubky.maps.offline.db.OfflinePlacesDatabase
import to.pubky.maps.search.SearchArea
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.Properties
import java.util.zip.DeflaterOutputStream
import java.util.zip.GZIPOutputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.measureTime

/** The offline places on this machine's SQLite (the app uses Android's), and MapLibre's database as MapLibre lays it out. */
class OfflinePlaceIndexDatabaseTest {
    private val dir: File = Files.createTempDirectory("offline-places").toFile()

    @AfterTest
    fun cleanUp() {
        dir.deleteRecursively()
    }

    private fun index(): SqlOfflinePlaceIndex {
        val driver = JdbcSqliteDriver("jdbc:sqlite:${File(dir, "offline-places.db").path}", Properties())
        OfflinePlacesDatabase.Schema.create(driver)
        return SqlOfflinePlaceIndex(driver, io = Dispatchers.Unconfined)
    }

    private val zurich = SearchArea(GeoPoint(47.3769, 8.5417), zoom = 14.0, halfDiagonalMeters = 3_000.0)

    private fun place(
        name: String,
        lat: Double,
        lon: Double,
        osm: Long? = null,
        key: String = "amenity",
        value: String = "cafe",
        category: String? = "Café",
    ) = OfflinePlace(name, osm?.let { OsmId(OsmType.Node, it) }, key, value, category, GeoPoint(lat, lon))

    private fun SqlOfflinePlaceIndex.find(query: String) = runBlocking { search(query, zurich) }?.map { it.name }

    @Test
    fun findsPlacesByTheStartOfTheirWordsNearestFirst() {
        val index = index()
        assertNull(index.find("cafe"), "no area: nothing to search")
        index.replace(
            "a",
            sequenceOf(
                place("Café Rosa", 47.40, 8.54, osm = 1),
                place("Rosa Bar", 47.3770, 8.5418, osm = 2, value = "bar", category = "Bar"),
                place("Grand Café Odeon", 47.3667, 8.5458, osm = 3),
                place("Bahnhofstrasse", 47.3720, 8.5390, key = "highway", value = "minor", category = "Street"),
                place("Brunnen", 47.3769, 8.5417, osm = 4, value = "fountain", category = "Fountain"),
            ),
        )
        assertEquals(setOf("a"), index.areas())
        assertEquals(listOf("Rosa Bar", "Café Rosa"), index.find("rosa"))
        assertEquals(listOf("Rosa Bar", "Café Rosa"), index.find("ROS"))
        // Accents and case do not count, every word must start a word of the name or category.
        assertEquals(listOf("Grand Café Odeon", "Café Rosa"), index.find("cafe"))
        assertEquals(listOf("Café Rosa"), index.find("caf ros"))
        assertEquals(listOf("Bahnhofstrasse"), index.find("street"))
        assertEquals(emptyList(), index.find("hofstrasse"))
        assertEquals(emptyList(), index.find("  "))
    }

    @Test
    fun anAreaIsReplacedAndDeletedAsAWholeAndAPlaceSeenTwiceIsStoredOnce() {
        val index = index()
        index.replace(
            "a",
            sequenceOf(
                place("Café Rosa", 47.38, 8.54, osm = 1),
                place("Café Rosa", 47.38, 8.54, osm = 1),
                place("Beck", 47.38, 8.54, osm = 2),
            ),
        )
        assertEquals(listOf("Café Rosa"), index.find("rosa"))
        index.replace("b", sequenceOf(place("Café Zürichsee", 47.36, 8.54, osm = 3)))
        index.replace("a", sequenceOf(place("Beck Neu", 47.38, 8.54, osm = 2)))
        assertEquals(emptyList(), index.find("rosa"))
        assertEquals(listOf("Beck Neu"), index.find("beck"))
        index.delete("a")
        assertEquals(setOf("b"), index.areas())
        assertEquals(emptyList(), index.find("beck"))
        assertEquals(listOf("Café Zürichsee"), index.find("cafe"))
    }

    @Test
    fun aStreetCutIntoTilesIsOneResult() {
        val index = index()
        index.replace(
            "a",
            sequenceOf(
                place("Bahnhofstrasse", 47.3720, 8.5390, key = "highway", value = "minor", category = "Street"),
                // In the next tile: another sameness, so only the search's distance check makes it one.
                place("Bahnhofstrasse", 47.3760, 8.5395, key = "highway", value = "minor", category = "Street"),
                // Another town's street of the same name.
                place("Bahnhofstrasse", 47.4500, 8.5600, key = "highway", value = "minor", category = "Street"),
            ),
        )
        // The piece nearest the map centre stands for the street.
        val found = runBlocking { index.search("bahnhofstrasse", zurich) }!!
        assertEquals(listOf(47.3760, 47.4500), found.map { it.point.lat })
    }

    @Test
    fun aCityOfPlacesIsSearchedWellUnderATenthOfASecond() {
        val index = index()
        val places =
            (0 until 60_000).asSequence().map { i ->
                place("Place $i ${WORDS[i % WORDS.size]}", 47.30 + (i % 300) * 0.0005, 8.45 + (i / 300) * 0.0008, osm = i.toLong() + 1)
            }
        index.replace("a", places)
        index.find("bäckerei")
        val took = measureTime { assertEquals(30, index.find("bäck")?.size) }
        assertTrue(took.inWholeMilliseconds < 100, "search took $took")
    }

    @Test
    fun readsThePlacesOfAnAreaFromMapLibresDatabase() {
        val maplibre = mapLibreDatabase()
        val tiles = MapLibreTiles(maplibre, ::inflateZlibOrGzip)
        val places = tiles.places("area-1")!!.toList()
        // Newest tiles first; each tile once although both styles link it; zoom 13 tiles left out.
        assertEquals(listOf("Neu", "Café Rosa", "Beck"), places.map { it.name })
        assertEquals(listOf("Fremd"), tiles.places("area-2")!!.map { it.name }.toList())
        assertNull(tiles.places("area-3"))
    }

    @Test
    fun anotherLayoutOfMapLibresDatabaseIsNotRead() {
        val maplibre = mapLibreDatabase(version = 7)
        assertFailsWith<MapLibreTiles.UnknownLayout> { MapLibreTiles(maplibre, ::inflateZlibOrGzip).places("area-1") }
    }

    @Test
    fun theDatabaseIsCopiedOnlyWhileNoWriteRuns() {
        val database = File(dir, "maplibre.db").apply { writeBytes(ByteArray(10_000) { it.toByte() }) }
        val copy = File(dir, "copy.db")
        assertTrue(copyUnchanged(database, copy))
        assertTrue(database.readBytes().contentEquals(copy.readBytes()))
        val journal = File(dir, "maplibre.db-journal").apply { writeBytes(byteArrayOf(1)) }
        assertFalse(copyUnchanged(database, copy))
        journal.delete()
        assertFalse(copyUnchanged(File(dir, "missing.db"), copy))
    }

    /**
     * MapLibre's offline database (schema version 6, as offline_schema.sql in MapLibre Native): two
     * styles of area-1 sharing their tiles, one tile of an older release, a tile of area-2.
     */
    private fun mapLibreDatabase(version: Int = 6): JdbcSqliteDriver {
        val driver = JdbcSqliteDriver("jdbc:sqlite:${File(dir, "maplibre.db").path}", Properties())
        listOf(
            "CREATE TABLE regions (id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT, definition TEXT NOT NULL, description BLOB)",
            "CREATE TABLE tiles (id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT, url_template TEXT NOT NULL, " +
                "pixel_ratio INTEGER NOT NULL, z INTEGER NOT NULL, x INTEGER NOT NULL, y INTEGER NOT NULL, " +
                "expires INTEGER, modified INTEGER, etag TEXT, data BLOB, " +
                "compressed INTEGER NOT NULL DEFAULT 0, accessed INTEGER NOT NULL, must_revalidate INTEGER NOT NULL DEFAULT 0, " +
                "UNIQUE (url_template, pixel_ratio, z, x, y))",
            "CREATE TABLE region_tiles (region_id INTEGER NOT NULL REFERENCES regions(id) ON DELETE CASCADE, " +
                "tile_id INTEGER NOT NULL REFERENCES tiles(id), UNIQUE (region_id, tile_id))",
            "PRAGMA user_version = $version",
        ).forEach { driver.execute(null, it, 0) }

        fun region(
            id: Long,
            area: String,
        ) = driver.execute(null, "INSERT INTO regions (id, definition, description) VALUES (?, '{}', ?)", 2) {
            bindLong(0, id)
            bindBytes(1, PackMetadata(area, "Map", 0, 8.5, 47.3, 8.6, 47.4).encode())
        }

        fun tile(
            id: Long,
            z: Long,
            data: ByteArray,
            compressed: Boolean,
            vararg regions: Long,
        ) {
            driver.execute(
                null,
                "INSERT INTO tiles (id, url_template, pixel_ratio, z, x, y, data, compressed, accessed) " +
                    "VALUES (?, ?, 1, ?, 8580, 5737, ?, ?, 0)",
                5,
            ) {
                bindLong(0, id)
                bindString(1, "https://tiles.example/$id/{z}/{x}/{y}.pbf")
                bindLong(2, z)
                bindBytes(3, data)
                bindLong(4, if (compressed) 1 else 0)
            }
            regions.forEach { region ->
                driver.execute(null, "INSERT INTO region_tiles (region_id, tile_id) VALUES (?, ?)", 2) {
                    bindLong(0, region)
                    bindLong(1, id)
                }
            }
        }
        region(1, "area-1")
        region(2, "area-1")
        region(3, "area-2")
        // A tile MapLibre compressed, one the server sent gzipped, one stored as it came.
        tile(1, 14, zlib(cafes("Café Rosa", "Beck")), compressed = true, 1, 2)
        tile(2, 14, gzip(cafes("Neu")), compressed = false, 1, 2)
        tile(3, 13, cafes("Zoom 13"), compressed = false, 1, 2)
        tile(4, 14, cafes("Fremd"), compressed = false, 3)
        tile(5, 14, byteArrayOf(1, 2, 3), compressed = false, 1)
        return driver
    }

    private fun cafes(vararg names: String): ByteArray =
        vectorTile(
            mapOf(
                "poi" to
                    names.mapIndexed { i, name ->
                        TestFeature(
                            10L * (i + 1) + 1,
                            mapOf("name" to name, "class" to "cafe", "subclass" to "cafe"),
                            listOf(100 * (i + 1) to 100),
                        )
                    },
            ),
        )

    private fun zlib(bytes: ByteArray): ByteArray =
        ByteArrayOutputStream()
            .also { out ->
                DeflaterOutputStream(out).use {
                    it.write(bytes)
                }
            }.toByteArray()

    private fun gzip(bytes: ByteArray): ByteArray =
        ByteArrayOutputStream()
            .also { out ->
                GZIPOutputStream(out).use {
                    it.write(bytes)
                }
            }.toByteArray()

    private companion object {
        val WORDS = listOf("Bäckerei", "Café", "Apotheke", "Kiosk", "Metzgerei", "Bar")
    }
}
