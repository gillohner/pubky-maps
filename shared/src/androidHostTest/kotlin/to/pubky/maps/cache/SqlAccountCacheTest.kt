package to.pubky.maps.cache

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import to.pubky.maps.cache.db.CacheDatabase
import java.io.File
import java.nio.file.Files
import java.util.Properties
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The SQLite cache on this machine's SQLite (the app uses Android's). */
class SqlAccountCacheTest {
    private val dir: File = Files.createTempDirectory("cache").toFile()
    private val file = File(dir, "account-cache.db")

    private fun open(): Pair<JdbcSqliteDriver, SqlAccountCache> {
        val created = file.exists()
        val driver = JdbcSqliteDriver("jdbc:sqlite:${file.path}", Properties())
        if (!created) CacheDatabase.Schema.create(driver)
        return driver to SqlAccountCache(driver)
    }

    @AfterTest
    fun cleanUp() {
        dir.deleteRecursively()
    }

    private val account =
        CachedAccount(
            publicKey = "8pinxxgqs41n4aididenw5apqp1urfmzdztr8jt4abrkdn435ewo",
            name = "Ada",
            refreshedAt = 1_000,
            tags =
                listOf(
                    CachedTag("/pub/social/v1/tags/a.json", "geo:47.1,8.5", "bench", 10),
                    CachedTag("/pub/social/v1/tags/b.json", "https://www.openstreetmap.org/node/1", "coffee", 30),
                ),
            bookmarks = listOf(CachedBookmark("/priv/social/v1/bookmarks/x.json", "geo:1,2")),
            otherTags =
                mapOf(
                    "/pub/social/v1/tags/c.json" to "pubky://8pinxxgqs41n4aididenw5apqp1urfmzdztr8jt4abrkdn435ewo/pub/pubky.app/posts/1",
                    "/pub/social/v1/tags/d.json" to
                        "pubky://8pinxxgqs41n4aididenw5apqp1urfmzdztr8jt4abrkdn435ewo/pub/pubky.app/profile.json",
                ),
        )

    @Test
    fun whatWasStoredIsReadAfterTheAppRestartsNewestTagFirst() {
        val (driver, cache) = open()
        cache.replace(account)
        driver.close()

        val (again, reopened) = open()
        val read = reopened.read()!!
        again.close()

        assertEquals(account.copy(tags = account.tags.reversed()), read)
    }

    @Test
    fun anotherAccountStartsEmptyAndTheSameOneKeepsItsData() {
        val (driver, cache) = open()
        cache.replace(account)

        cache.begin(account.publicKey)
        assertEquals(2, cache.read()!!.tags.size)

        cache.begin("o1gg96ewuojmopcjbz8895478wdtxtzzuxnfjjz8o8e77csa1ngo")
        assertEquals(
            CachedAccount("o1gg96ewuojmopcjbz8895478wdtxtzzuxnfjjz8o8e77csa1ngo", null, null, emptyList(), emptyList()),
            cache.read(),
        )
        // The switch deleted the rows: the new account's own are written even when they are the same.
        cache.replace(account.copy(publicKey = "o1gg96ewuojmopcjbz8895478wdtxtzzuxnfjjz8o8e77csa1ngo"))
        assertEquals(account.otherTags, cache.read()!!.otherTags)
        driver.close()
    }

    @Test
    fun clearDeletesEverything() {
        val (driver, cache) = open()
        cache.replace(account)

        cache.clear()

        assertNull(cache.read())
        cache.begin(account.publicKey)
        assertEquals(emptyMap(), cache.read()!!.otherTags, "the other tag files are deleted with the account")
        driver.close()
    }

    @Test
    fun aWriteThatKeepsTheOtherTagsDoesNotWriteThemAgain() {
        val (driver, cache) = open()
        cache.replace(account)
        // Rows the cache did not write: a replace that writes the other tags drops them.
        driver.execute(null, "INSERT INTO other_tag VALUES ('/pub/social/v1/tags/e.json', 'x')", 0)

        cache.replace(account.copy(tags = account.tags.take(1)))
        assertEquals(3, cache.read()!!.otherTags.size, "a tag write keeps the rows")
        assertEquals(1, cache.read()!!.tags.size)

        cache.replace(account.copy(otherTags = account.otherTags.filterKeys { it.endsWith("c.json") }))
        assertEquals(setOf("/pub/social/v1/tags/c.json"), cache.read()!!.otherTags.keys, "a refresh's new ones replace them")
        driver.close()
    }

    @Test
    fun aVersionFourCacheKeepsItsTagsAndGainsOtherTagFiles() {
        val driver = JdbcSqliteDriver("jdbc:sqlite:${File(dir, "v4.db").path}", Properties())
        // The schema before the other tag files were kept: every table but other_tag.
        CacheDatabase.Schema.create(driver)
        driver.execute(null, "DROP TABLE other_tag", 0)
        driver.execute(null, "INSERT INTO account VALUES ('${account.publicKey}', 'Ada', 7)", 0)
        driver.execute(null, "INSERT INTO tag VALUES ('/pub/social/v1/tags/a.json', 'geo:47.1,8.5', 'bench', 10)", 0)
        assertEquals(5L, CacheDatabase.Schema.version)

        CacheDatabase.Schema.migrate(driver, 4, CacheDatabase.Schema.version)

        val cache = SqlAccountCache(driver)
        assertEquals(listOf("bench"), cache.read()!!.tags.map { it.label })
        assertEquals(emptyMap(), cache.read()!!.otherTags)
        cache.replace(account)
        assertEquals(account.otherTags, cache.read()!!.otherTags)
        driver.close()
    }
}
