package to.pubky.maps.write

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import to.pubky.maps.cache.CachedAccount
import to.pubky.maps.cache.SqlAccountCache
import to.pubky.maps.cache.db.CacheDatabase
import to.pubky.maps.links.GeoPoint
import to.pubky.maps.write.db.PhoneDataDatabase
import java.io.File
import java.nio.file.Files
import java.util.Properties
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val USER = "8pinxxgqs41n4aididenw5apqp1urfmzdztr8jt4abrkdn435ewo"

/** The outbox, lists and phone-only data on this machine's SQLite (the app uses Android's). */
class SqlWriteStoreTest {
    private val dir: File = Files.createTempDirectory("writes").toFile()

    @AfterTest
    fun cleanUp() {
        dir.deleteRecursively()
    }

    private fun accountDb(): JdbcSqliteDriver {
        val driver = JdbcSqliteDriver("jdbc:sqlite:${File(dir, "account-cache.db").path}", Properties())
        CacheDatabase.Schema.create(driver)
        return driver
    }

    private val tag = WriteOp.Tag("https://www.openstreetmap.org/node/1", "coffee")
    private val photo =
        WriteOp.Photo(
            "geo:47.1,8.5",
            "/data/x.photo",
            "A bench",
            5,
            "/pub/social/v1/files/F.jpg",
            "/pub/social/v1/posts/I/I.json",
            "pubky://x",
            "{}",
        )
    private val list = OwnList("pubky://$USER/pub/social/v1/posts/0034A0000000", Kept.Public, "Coffee", listOf("geo:1,2"))
    private val privateList = OwnList("pubky://$USER/priv/social/v1/posts/0034A0000001", Kept.Private, "Secret", emptyList())

    @Test
    fun theOutboxKeepsWritesInOrderAndForgetsSentOnes() {
        val store = SqlWriteStore(accountDb())
        val first = store.enqueue(tag)
        val second = store.enqueue(photo)
        assertTrue(second.seq > first.seq)
        assertEquals(listOf(first, second), store.queue())

        store.remove(first.seq)

        assertEquals(listOf(second), store.queue())
    }

    @Test
    fun listsAreReplacedPerRoot() {
        val store = SqlWriteStore(accountDb())
        store.replaceLists(private = false, listOf(list))
        store.replaceLists(private = true, listOf(privateList))
        store.replaceLists(private = false, emptyList())

        assertEquals(listOf(privateList), store.lists())

        store.putList(privateList.copy(items = listOf("geo:3,4")))
        assertEquals(listOf("geo:3,4"), store.lists().single().items)
    }

    @Test
    fun knownNonListsAndNamesAreKept() {
        val store = SqlWriteStore(accountDb())
        store.addOtherPosts(private = false, listOf("A", "B"))
        store.addOtherPosts(private = true, listOf("C"))
        store.putLabel("geo:1,2", "Bench")
        store.putPoint("https://osm.org/node/1", GeoPoint(47.37, 8.54))
        store.putPoint("https://osm.org/node/1", GeoPoint(47.38, 8.55))

        assertEquals(setOf("A", "B"), store.otherPosts(false).toSet())
        assertEquals(listOf("C"), store.otherPosts(true))
        assertEquals(mapOf("geo:1,2" to "Bench"), store.labels())
        assertEquals(mapOf("https://osm.org/node/1" to GeoPoint(47.38, 8.55)), store.points())
    }

    @Test
    fun aRefreshKeepsTheOutboxAndSignOutDeletesIt() {
        val driver = accountDb()
        val store = SqlWriteStore(driver)
        val cache = SqlAccountCache(driver)
        cache.begin(USER)
        store.enqueue(tag)
        store.putList(list)
        store.putLabel("geo:1,2", "Bench")
        store.putPoint("geo:1,2", GeoPoint(1.0, 2.0))

        cache.replace(CachedAccount(USER, "Ada", 1, emptyList(), emptyList()))
        assertEquals(1, store.queue().size, "a refresh of the cache keeps what waits to be sent")
        assertEquals(1, store.lists().size)

        cache.clear()
        assertEquals(emptyList(), store.queue())
        assertEquals(emptyList(), store.lists())
        assertEquals(emptyMap(), store.labels())
        assertEquals(emptyMap(), store.points(), "where the user's saved places are goes with the account")
    }

    @Test
    fun anotherAccountStartsWithAnEmptyOutbox() {
        val driver = accountDb()
        val store = SqlWriteStore(driver)
        val cache = SqlAccountCache(driver)
        cache.begin(USER)
        store.enqueue(tag)

        cache.begin("o1gg96ewuojmopcjbz8895478wdtxtzzuxnfjjz8o8e77csa1ngo")

        assertEquals(emptyList(), store.queue())
    }

    @Test
    fun aVersionOneCacheIsMigrated() {
        val driver = JdbcSqliteDriver("jdbc:sqlite:${File(dir, "old.db").path}", Properties())
        // The schema the first released cache had.
        listOf(
            "CREATE TABLE account (public_key TEXT NOT NULL PRIMARY KEY, name TEXT, refreshed_at INTEGER)",
            "CREATE TABLE tag (path TEXT NOT NULL PRIMARY KEY, place TEXT NOT NULL, label TEXT NOT NULL, created_at INTEGER NOT NULL)",
            "CREATE TABLE bookmark (path TEXT NOT NULL PRIMARY KEY, place TEXT NOT NULL)",
            "INSERT INTO account VALUES ('$USER', 'Ada', 7)",
        ).forEach { driver.execute(null, it, 0) }
        assertEquals(3L, CacheDatabase.Schema.version)

        CacheDatabase.Schema.migrate(driver, 1, CacheDatabase.Schema.version)

        assertEquals("Ada", SqlAccountCache(driver).read()?.name)
        val store = SqlWriteStore(driver)
        store.enqueue(tag)
        assertEquals(tag, store.queue().single().op)
        store.putPoint("geo:1,2", GeoPoint(1.0, 2.0))
        assertEquals(GeoPoint(1.0, 2.0), store.points()["geo:1,2"])
    }

    @Test
    fun aVersionTwoCacheKeepsItsOutboxAndNamesAndGainsPoints() {
        val driver = JdbcSqliteDriver("jdbc:sqlite:${File(dir, "v2.db").path}", Properties())
        // The schema before saved places had points: version 1's tables plus 1.sqm.
        listOf(
            "CREATE TABLE account (public_key TEXT NOT NULL PRIMARY KEY, name TEXT, refreshed_at INTEGER)",
            "CREATE TABLE tag (path TEXT NOT NULL PRIMARY KEY, place TEXT NOT NULL, label TEXT NOT NULL, created_at INTEGER NOT NULL)",
            "CREATE TABLE bookmark (path TEXT NOT NULL PRIMARY KEY, place TEXT NOT NULL)",
            "CREATE TABLE outbox (seq INTEGER PRIMARY KEY AUTOINCREMENT, op TEXT NOT NULL)",
            "CREATE TABLE own_list (uri TEXT NOT NULL PRIMARY KEY, private INTEGER NOT NULL, list TEXT NOT NULL)",
            "CREATE TABLE other_post (private INTEGER NOT NULL, id TEXT NOT NULL, PRIMARY KEY (private, id))",
            "CREATE TABLE place_label (place TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL)",
            "INSERT INTO place_label VALUES ('geo:1,2', 'Bench')",
        ).forEach { driver.execute(null, it, 0) }
        // The outbox is the same in both versions: a write waiting before the update.
        SqlWriteStore(driver).enqueue(tag)

        CacheDatabase.Schema.migrate(driver, 2, CacheDatabase.Schema.version)

        val store = SqlWriteStore(driver)
        assertEquals(tag, store.queue().single().op)
        assertEquals(mapOf("geo:1,2" to "Bench"), store.labels())
        assertEquals(emptyMap(), store.points())
        store.putPoint("geo:1,2", GeoPoint(1.0, 2.0))
        assertEquals(mapOf("geo:1,2" to GeoPoint(1.0, 2.0)), store.points())
    }

    @Test
    fun phoneOnlyDataIsKeptInItsOwnDatabase() {
        val driver = JdbcSqliteDriver("jdbc:sqlite:${File(dir, "phone-data.db").path}", Properties())
        PhoneDataDatabase.Schema.create(driver)
        val phone = SqlPhoneStore(driver)
        val phoneList = OwnList("phone:1", Kept.Phone, "Picnic spots", listOf("geo:1,2"), names = mapOf("geo:1,2" to "Lake"))

        phone.save(PhoneSaved("geo:1,2", "Lake", 10))
        phone.save(PhoneSaved("geo:3,4", null, 20))
        phone.save(PhoneSaved("https://www.openstreetmap.org/node/1", "Schober", 30, GeoPoint(47.37159, 8.54531)))
        phone.putList(phoneList)
        phone.unsave("geo:1,2")

        val read = SqlPhoneStore(driver).read()
        assertEquals(
            listOf(
                PhoneSaved("https://www.openstreetmap.org/node/1", "Schober", 30, GeoPoint(47.37159, 8.54531)),
                PhoneSaved("geo:3,4", null, 20),
            ),
            read.saved,
        )
        assertEquals(listOf(phoneList), read.lists)
    }

    @Test
    fun aVersionOnePhoneStoreKeepsItsSavedPlacesAndGainsPoints() {
        val driver = JdbcSqliteDriver("jdbc:sqlite:${File(dir, "old-phone.db").path}", Properties())
        // The schema the first released phone store had.
        listOf(
            "CREATE TABLE saved_place (place TEXT NOT NULL PRIMARY KEY, name TEXT, saved_at INTEGER NOT NULL)",
            "CREATE TABLE phone_list (uri TEXT NOT NULL PRIMARY KEY, list TEXT NOT NULL)",
            "CREATE TABLE flag (name TEXT NOT NULL PRIMARY KEY)",
            "INSERT INTO saved_place VALUES ('https://www.openstreetmap.org/node/1', 'Schober', 10)",
        ).forEach { driver.execute(null, it, 0) }
        assertEquals(2L, PhoneDataDatabase.Schema.version)

        PhoneDataDatabase.Schema.migrate(driver, 1, PhoneDataDatabase.Schema.version)

        val phone = SqlPhoneStore(driver)
        assertEquals(listOf(PhoneSaved("https://www.openstreetmap.org/node/1", "Schober", 10)), phone.read().saved)
        phone.save(PhoneSaved("geo:3,4", null, 20, GeoPoint(3.0, 4.0)))
        assertEquals(
            GeoPoint(3.0, 4.0),
            phone
                .read()
                .saved
                .first()
                .point,
        )
    }
}
