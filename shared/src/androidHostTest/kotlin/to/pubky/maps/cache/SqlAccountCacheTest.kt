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
        driver.close()
    }

    @Test
    fun clearDeletesEverything() {
        val (driver, cache) = open()
        cache.replace(account)

        cache.clear()

        assertNull(cache.read())
        driver.close()
    }
}
