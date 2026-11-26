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
import java.io.RandomAccessFile
import java.nio.file.Files
import java.util.Properties
import java.util.zip.DeflaterOutputStream
import java.util.zip.GZIPOutputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
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
            0,
            sequenceOf(
                place("Café Rosa", 47.40, 8.54, osm = 1),
                place("Rosa Bar", 47.3770, 8.5418, osm = 2, value = "bar", category = "Bar"),
                place("Grand Café Odeon", 47.3667, 8.5458, osm = 3),
                place("Bahnhofstrasse", 47.3720, 8.5390, key = "highway", value = "minor", category = "Street"),
                place("Brunnen", 47.3769, 8.5417, osm = 4, value = "fountain", category = "Fountain"),
            ),
        )
        assertEquals(mapOf("a" to 0L), index.areas())
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
            0,
            sequenceOf(
                place("Café Rosa", 47.38, 8.54, osm = 1),
                place("Café Rosa", 47.38, 8.54, osm = 1),
                place("Beck", 47.38, 8.54, osm = 2),
            ),
        )
        assertEquals(listOf("Café Rosa"), index.find("rosa"))
        index.replace("b", 0, sequenceOf(place("Café Zürichsee", 47.36, 8.54, osm = 3)))
        index.replace("a", 7, sequenceOf(place("Beck Neu", 47.38, 8.54, osm = 2)))
        assertEquals(emptyList(), index.find("rosa"))
        assertEquals(listOf("Beck Neu"), index.find("beck"))
        assertEquals(mapOf("a" to 7L, "b" to 0L), index.areas())
        index.delete("a")
        assertEquals(mapOf("b" to 0L), index.areas())
        assertEquals(emptyList(), index.find("beck"))
        assertEquals(listOf("Café Zürichsee"), index.find("cafe"))
    }

    @Test
    fun aStreetCutIntoTilesIsOneResult() {
        val index = index()
        index.replace(
            "a",
            0,
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
    fun theRarestWordFindsAPlaceAndAnExactNamePassesTheCut() {
        val index = index()
        val restaurants =
            (0 until 500).map { i ->
                place("Restaurant $i", 47.37 + i * 0.0001, 8.54, osm = i + 10L, value = "restaurant", category = "Restaurant")
            }
        val stops =
            (0 until 450).map { i ->
                place(
                    "Ustermatt $i",
                    47.37 + i * 0.0001,
                    8.55,
                    osm = i + 1_000L,
                    key = "highway",
                    value = "bus_stop",
                    category = "Bus stop",
                )
            }
        index.replace(
            "a",
            0,
            (
                restaurants + stops +
                    place("Krone", 47.45, 8.70, osm = 1, value = "restaurant", category = "Restaurant") +
                    place("Uster", 47.35, 8.72, osm = 2, key = "place", value = "town", category = "Town")
            ).asSequence(),
        )
        // "restaurant" is in 501 places, "krone" in one: the Krone beyond the nearest 400 restaurants is found.
        assertEquals(listOf("Krone"), index.find("restaurant krone"))
        // The town is farther than the 450 stops whose name starts with its name, and named exactly so.
        assertEquals("Uster", index.find("uster")?.first())
    }

    @Test
    fun placesKeepTheirDistrictAndTownAndAreFoundByThem() {
        val index = index()
        index.replace(
            "a",
            0,
            sequenceOf(
                place("Kunsthaus Zürich", 47.3703, 8.5481, osm = 1, key = "tourism", value = "museum", category = "Museum"),
                place("Bahnhofstrasse", 47.3720, 8.5390, key = "highway", value = "minor", category = "Street"),
                place("Bahnhofstrasse", 47.4995, 8.7235, key = "highway", value = "minor", category = "Street"),
                // The labels come after the places they name, as the tiles give them in any order.
                place("Zürich", 47.3700, 8.5400, osm = 10, key = "place", value = "city", category = null),
                place("Hottingen", 47.3700, 8.5600, osm = 11, key = "place", value = "quarter", category = null),
                place("Winterthur", 47.5000, 8.7240, osm = 12, key = "place", value = "town", category = null),
            ),
        )
        val all = runBlocking { index.search("bahnhofstrasse", zurich) }!!
        assertEquals(listOf("Zürich", "Winterthur"), all.map { it.locality })
        assertEquals(listOf(47.4995), runBlocking { index.search("bahnhofstrasse winterthur", zurich) }!!.map { it.point.lat })
        assertEquals(listOf("Hottingen, Zürich"), runBlocking { index.search("kunsthaus", zurich) }!!.map { it.locality })
        // A district's label is in its town; a town's label in nothing.
        assertEquals(listOf("Zürich"), runBlocking { index.search("hottingen", zurich) }!!.map { it.locality })
        assertEquals(listOf(null), runBlocking { index.search("winterthur", zurich) }!!.map { it.locality })
        // A district's or town's name alone finds the district or town, not every place in it.
        assertEquals(listOf("Hottingen"), index.find("hottingen"))
        assertEquals(listOf("Winterthur"), index.find("winterthur"))
    }

    @Test
    fun aTownIsFoundByTheStartOfItsNameAmongThePlacesThatLieInIt() {
        val index = index()
        val shops =
            (0 until 450).map { i ->
                place("Shop $i", 47.3700 + i * 0.00001, 8.5400, osm = i + 100L, value = "shop", category = "Shop")
            }
        index.replace(
            "a",
            0,
            (
                shops +
                    place("Zürichstrasse", 47.3600, 8.5300, key = "highway", value = "minor", category = "Street") +
                    place("Zürich", 47.3500, 8.5400, osm = 1, key = "place", value = "city", category = null)
            ).asSequence(),
        )
        // Every shop lies in Zürich and is nearer the map centre than the label and the street.
        assertEquals(setOf("Zürich"), runBlocking { index.search("shop", zurich) }!!.map { it.locality }.toSet())
        assertEquals(setOf("Zürich", "Zürichstrasse"), index.find("zur")?.toSet())
        assertEquals(listOf("Zürich", "Zürichstrasse"), index.find("zürich"))
    }

    @Test
    fun aTownComesBeforeThePlacesNamedAfterIt() {
        val index = index()
        // 450 stops named after the town, all nearer the map centre than its label.
        val stops =
            (0 until 450).map { i ->
                place(
                    "Zürich, Halt $i",
                    47.3700 + i * 0.00001,
                    8.5400,
                    osm = i + 100L,
                    key = "highway",
                    value = "bus_stop",
                    category = "Bus stop",
                )
            }
        val city = place("Zürich", 47.3200, 8.5400, osm = 1, key = "place", value = "city", category = null)
        index.replace("a", 0, (stops + city + place("Café Rosa", 47.3210, 8.5410, osm = 2)).asSequence())
        assertEquals("Zürich", index.find("zürich")?.first())
        // And the other words of a query naming it still match the places in it (the lookup
        // around a town is theOtherWordsAreLookedUpAroundATownNamed's).
        assertEquals(listOf("Café Rosa"), index.find("cafe zurich"))
    }

    @Test
    fun aShortbreadCapitalNamesThePlacesInItAndIsLookedUpAround() {
        val index = index()
        // A VersaTiles (Shortbread) tile over Zürich, whose label is a `state_capital`.
        val tile =
            vectorTile(
                mapOf(
                    "pois" to
                        listOf(
                            TestFeature(11, mapOf("name" to "Kunsthaus", "tourism" to "museum"), listOf(3000 to 3000)),
                            TestFeature(21, mapOf("name" to "Café Rosa", "amenity" to "cafe"), listOf(3050 to 3050)),
                        ),
                    "place_labels" to
                        listOf(
                            TestFeature(31, mapOf("name" to "Zürich", "kind" to "state_capital"), listOf(2000 to 2000)),
                            TestFeature(41, mapOf("name" to "Hottingen", "kind" to "quarter"), listOf(3100 to 3100)),
                        ),
                ),
            )
        // The map is on Winterthur, 20 km away, among 450 cafés of its own.
        val winterthur = SearchArea(GeoPoint(47.5000, 8.7240), zoom = 14.0, halfDiagonalMeters = 3_000.0)
        val near = (0 until 450).map { i -> place("Café $i", 47.5000 + i * 0.00001, 8.7240, osm = i + 100L) }
        index.replace("a", 0, (near + placesOfTile(14, 8580, 5737, decodeVectorTile(tile))).asSequence())
        val museum = runBlocking { index.search("kunsthaus", winterthur) }!!
        assertEquals(listOf("Hottingen, Zürich"), museum.map { it.locality })
        assertEquals("city", runBlocking { index.search("zürich", winterthur) }!!.first().photonType)
        // "cafe zurich" looks around the capital, past the cafés nearer the map.
        assertEquals(listOf("Café Rosa"), runBlocking { index.search("cafe zurich", winterthur) }!!.map { it.name })
    }

    @Test
    fun theOtherWordsAreLookedUpAroundATownNamed() {
        val index = index()
        // 450 cafés around the map centre, and two in Uster, 20 km away.
        val near = (0 until 450).map { i -> place("Café $i", 47.3700 + i * 0.00001, 8.5400, osm = i + 100L) }
        index.replace(
            "a",
            0,
            (
                near +
                    place("Café Uster", 47.3500, 8.7200, osm = 1) +
                    place("Café am Stadtpark", 47.3510, 8.7210, osm = 2) +
                    place("Uster", 47.3505, 8.7205, osm = 3, key = "place", value = "town", category = null)
            ).asSequence(),
        )
        assertEquals(setOf("Café Uster", "Café am Stadtpark"), index.find("cafe uster")?.toSet())
        assertEquals(listOf("Café am Stadtpark"), index.find("cafe stadtpark uster"))
    }

    @Test
    fun aPlacePageGetsTheLocalityOfItsElementElseOfItsPointNearTheAreasPlaces() {
        val index = index()
        assertNull(index.localityOf(OsmId(OsmType.Node, 1), GeoPoint(47.3703, 8.5481)), "no area")
        index.replace(
            "a",
            0,
            sequenceOf(
                place("Kunsthaus Zürich", 47.3703, 8.5481, osm = 1, key = "tourism", value = "museum", category = "Museum"),
                place("Bahnhofstrasse", 47.3720, 8.5390, key = "highway", value = "minor", category = "Street"),
                place("Zürich", 47.3700, 8.5400, osm = 10, key = "place", value = "city", category = null),
                place("Hottingen", 47.3700, 8.5600, osm = 11, key = "place", value = "quarter", category = null),
                place("Winterthur", 47.5000, 8.7240, osm = 12, key = "place", value = "town", category = null),
            ),
        )
        // The element's stored locality, as offline search shows it; a town's label is in nothing.
        assertEquals("Hottingen, Zürich", index.localityOf(OsmId(OsmType.Node, 1), GeoPoint(0.0, 0.0)))
        assertNull(index.localityOf(OsmId(OsmType.Node, 12), GeoPoint(47.5000, 8.7240)))
        // Another type with the same id is another element: looked up by its point.
        assertEquals("Zürich", index.localityOf(OsmId(OsmType.Way, 1), GeoPoint(47.3722, 8.5392)))
        // A Pubky pin or a dropped pin next to the Bahnhofstrasse, which the tiles have no element of.
        assertEquals("Zürich", index.localityOf(null, GeoPoint(47.3725, 8.5395)))
        assertEquals("Hottingen, Zürich", index.localityOf(OsmId(OsmType.Node, 99), GeoPoint(47.3705, 8.5490)))
        // 2 km from every place of the area, where a label outside its tiles could be nearer.
        assertNull(index.localityOf(null, GeoPoint(47.3900, 8.5390)))
        index.delete("a")
        assertNull(index.localityOf(OsmId(OsmType.Node, 1), GeoPoint(47.3703, 8.5481)))
    }

    @Test
    fun aVersionTwoDatabaseKeepsItsPlacesAndGetsTheIndexesOfAPlacePage() {
        val driver = JdbcSqliteDriver("jdbc:sqlite:${File(dir, "v2.db").path}", Properties())
        // The schema of version 2.
        listOf(
            "CREATE TABLE area (id TEXT NOT NULL PRIMARY KEY, new_data_at INTEGER NOT NULL)",
            "CREATE TABLE place (id INTEGER PRIMARY KEY AUTOINCREMENT, area TEXT NOT NULL, name TEXT NOT NULL, osm_type TEXT, " +
                "osm_id INTEGER, tag_key TEXT NOT NULL, tag_value TEXT NOT NULL, category TEXT, lat REAL NOT NULL, lon REAL NOT NULL, " +
                "locality TEXT)",
            "CREATE INDEX place_area ON place(area)",
            "CREATE TABLE word (word TEXT NOT NULL, place INTEGER NOT NULL)",
            "CREATE INDEX word_word ON word(word)",
            "CREATE INDEX word_place ON word(place)",
            "INSERT INTO area VALUES ('a', 5)",
            "INSERT INTO place VALUES (1, 'a', 'Café Rosa', 'Node', 1, 'amenity', 'cafe', 'Café', 47.3700, 8.5410, 'Zürich')",
            "INSERT INTO place VALUES (2, 'a', 'Zürich', 'Node', 10, 'place', 'city', NULL, 47.3700, 8.5400, NULL)",
            "INSERT INTO word VALUES ('rosa', 1)",
        ).forEach { driver.execute(null, it, 0) }
        assertEquals(3L, OfflinePlacesDatabase.Schema.version)

        OfflinePlacesDatabase.Schema.migrate(driver, 2, OfflinePlacesDatabase.Schema.version)

        val index = SqlOfflinePlaceIndex(driver, io = Dispatchers.Unconfined)
        assertEquals(mapOf("a" to 5L), index.areas())
        assertEquals(listOf("Café Rosa"), index.find("rosa"))
        assertEquals("Zürich", index.localityOf(OsmId(OsmType.Node, 1), GeoPoint(47.3700, 8.5410)))
        assertEquals("Zürich", index.localityOf(null, GeoPoint(47.3710, 8.5420)))
        val indexes =
            driver
                .executeQuery(null, "SELECT name FROM sqlite_master WHERE type = 'index' AND name LIKE 'place_%'", { c ->
                    app.cash.sqldelight.db.QueryResult
                        .Value(buildList { while (c.next().value) add(c.getString(0)!!) })
                }, 0)
                .value
        assertEquals(setOf("place_area", "place_osm", "place_lat", "place_label"), indexes.toSet())
    }

    @Test
    fun aVersionOneDatabaseIsEmptiedSoItsAreasAreReadAgain() {
        val driver = JdbcSqliteDriver("jdbc:sqlite:${File(dir, "v1.db").path}", Properties())
        // The schema the first released offline places had.
        listOf(
            "CREATE TABLE area (id TEXT NOT NULL PRIMARY KEY, new_data_at INTEGER NOT NULL)",
            "CREATE TABLE place (id INTEGER PRIMARY KEY AUTOINCREMENT, area TEXT NOT NULL, name TEXT NOT NULL, osm_type TEXT, " +
                "osm_id INTEGER, tag_key TEXT NOT NULL, tag_value TEXT NOT NULL, category TEXT, lat REAL NOT NULL, lon REAL NOT NULL)",
            "CREATE INDEX place_area ON place(area)",
            "CREATE TABLE word (word TEXT NOT NULL, place INTEGER NOT NULL)",
            "CREATE INDEX word_word ON word(word)",
            "CREATE INDEX word_place ON word(place)",
            "INSERT INTO area VALUES ('a', 5)",
            "INSERT INTO place VALUES (1, 'a', 'Café Rosa', 'node', 1, 'amenity', 'cafe', 'Café', 47.37, 8.54)",
            "INSERT INTO word VALUES ('rosa', 1)",
        ).forEach { driver.execute(null, it, 0) }
        OfflinePlacesDatabase.Schema.migrate(driver, 1, OfflinePlacesDatabase.Schema.version)

        val index = SqlOfflinePlaceIndex(driver, io = Dispatchers.Unconfined)
        // No area: the indexer reads every Ready area's places again, now with their localities.
        assertEquals(emptyMap(), index.areas())
        index.replace(
            "a",
            6,
            sequenceOf(
                place("Café Rosa", 47.3700, 8.5410, osm = 1),
                place("Zürich", 47.3700, 8.5400, osm = 10, key = "place", value = "city", category = null),
            ),
        )
        assertEquals(listOf("Zürich"), runBlocking { index.search("rosa", zurich) }!!.map { it.locality })
    }

    @Test
    fun aCityOfPlacesIsSearchedWellUnderATenthOfASecond() {
        val index = index()
        val places =
            (0 until 60_000).asSequence().map { i ->
                place("Place $i ${WORDS[i % WORDS.size]}", 47.30 + (i % 300) * 0.0005, 8.45 + (i / 300) * 0.0008, osm = i.toLong() + 1)
            }
        // A town every 2 km, so each place has a locality.
        val towns =
            (0 until 64).asSequence().map { i ->
                place(
                    TOWNS[i % TOWNS.size],
                    47.30 + (i % 8) * 0.018,
                    8.45 + (i / 8) * 0.026,
                    osm = 1_000_000L + i,
                    key = "place",
                    value = "town",
                    category = null,
                )
            }
        val stored = measureTime { index.replace("a", 0, places + towns) }
        println("60,064 places stored in $stored")
        index.find("bäckerei")
        val took = measureTime { assertEquals(30, index.find("bäck")?.size) }
        assertTrue(took.inWholeMilliseconds < 100, "search took $took")
        // Two words, one a town's: looked up around the map centre and around the town.
        val inTown = measureTime { assertEquals(30, index.find("bäck ${TOWNS[0]}")?.size) }
        assertTrue(inTown.inWholeMilliseconds < 100, "search in a town took $inTown")
        // A place page's district and town, by a point among them (a tapped place the tiles have no element of).
        index.localityOf(null, GeoPoint(47.35, 8.50))
        val locality = measureTime { assertNotNull(index.localityOf(null, GeoPoint(47.36, 8.52))) }
        println("a locality in $locality")
        assertTrue(locality.inWholeMilliseconds < 50, "a locality took $locality")
    }

    @Test
    fun readsThePlacesOfAnAreaFromMapLibresDatabase() {
        val maplibre = mapLibreDatabase()
        val tiles = MapLibreTiles(maplibre, ::inflateZlibOrGzip)
        val places = tiles.places("area-1")!!.toList()
        // Newest tiles first; each tile once although both styles link it; zoom 13 tiles left out;
        // the release before's tile at the same place left out ("Geschlossen" closed since), the
        // tile of another source kept.
        assertEquals(listOf("Wald", "Café Rosa", "Beck"), places.map { it.name })
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

    @Test
    fun aCopyDuringAWriteOrWithoutRoomDoesNotCount() {
        val database = File(dir, "maplibre.db").apply { writeBytes(ByteArray(10_000) { it.toByte() }) }
        val copy = File(dir, "copy.db")
        // A write that grows the file while it is copied.
        assertFalse(
            copyUnchanged(database, copy, copyFile = { from, to ->
                from.copyTo(to, overwrite = true)
                from.appendBytes(ByteArray(4_096))
            }),
        )
        // A write that keeps the size and the time but raises SQLite's change counter.
        assertFalse(
            copyUnchanged(database, copy, copyFile = { from, to ->
                from.copyTo(to, overwrite = true)
                val time = Files.getLastModifiedTime(from.toPath())
                RandomAccessFile(from, "rw").use {
                    it.seek(24)
                    it.write(byteArrayOf(9, 9, 9, 9))
                }
                Files.setLastModifiedTime(from.toPath(), time)
            }),
        )
        // Room for the copy and as much again for what SQLite may need to open it.
        val size = database.length()
        assertFalse(copyUnchanged(database, copy, usableSpace = { 2 * size - 1 }))
        assertTrue(copyUnchanged(database, copy, usableSpace = { 2 * size }))
    }

    @Test
    fun theCopyIsReadDeletedAndRefusedWhenEmpty() =
        runBlocking {
            mapLibreDatabase().close()
            val copy = File(dir, "copy.db")
            val copies =
                MapLibreDatabaseCopy(
                    database = File(dir, "maplibre.db"),
                    copy = copy,
                    openCopy = { JdbcSqliteDriver("jdbc:sqlite:${it.path}", Properties(), MapLibreLayout) },
                    io = Dispatchers.Unconfined,
                )
            // Left by a process that died during a read: it does not meet the new copy.
            copy.writeBytes(byteArrayOf(1, 2, 3))
            File(dir, "copy.db-journal").writeBytes(byteArrayOf(1))
            assertEquals(listOf("Fremd"), copies.open { tiles -> tiles.places("area-2")!!.map { it.name }.toList() })
            assertEquals(listOf("maplibre.db"), dir.list()!!.sorted())

            // A database MapLibre has not laid out yet is not laid out here either.
            File(dir, "maplibre.db").delete()
            mapLibreDatabase(version = 0).close()
            assertFailsWith<MapLibreTiles.UnknownLayout> { copies.open { it.places("area-1") } }
            assertEquals(listOf("maplibre.db"), dir.list()!!.sorted())
        }

    /**
     * MapLibre's offline database (schema version 6, as offline_schema.sql in MapLibre Native): two
     * styles of area-1 sharing their tiles, one tile of the release before at the same place, one
     * of another source, a damaged one, a tile of area-2.
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
            source: String = "planet/20251119_001001_pt",
            x: Long = 8580,
        ) {
            driver.execute(
                null,
                "INSERT INTO tiles (id, url_template, pixel_ratio, z, x, y, data, compressed, accessed) " +
                    "VALUES (?, ?, 1, ?, ?, 5737, ?, ?, 0)",
                6,
            ) {
                bindLong(0, id)
                bindString(1, "https://tiles.example/$source/{z}/{x}/{y}.pbf")
                bindLong(2, z)
                bindLong(3, x)
                bindBytes(4, data)
                bindLong(5, if (compressed) 1 else 0)
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
        tile(1, 14, cafes("Geschlossen", "Beck"), compressed = false, 1, 2, source = "planet/20251112_001001_pt")
        tile(2, 14, zlib(cafes("Café Rosa", "Beck")), compressed = true, 1, 2)
        tile(3, 13, cafes("Zoom 13"), compressed = false, 1, 2)
        tile(4, 14, cafes("Fremd"), compressed = false, 3, x = 9000)
        tile(5, 14, byteArrayOf(1, 2, 3), compressed = false, 1, x = 8581)
        tile(6, 14, gzip(cafes("Wald")), compressed = false, 1, source = "landcover")
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
        val TOWNS = listOf("Uster", "Wetzikon", "Dübendorf", "Wallisellen", "Kloten", "Bülach", "Horgen", "Thalwil")
    }
}
