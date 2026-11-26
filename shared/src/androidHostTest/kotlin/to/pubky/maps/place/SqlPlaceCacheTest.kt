package to.pubky.maps.place

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import to.pubky.maps.links.OsmId
import to.pubky.maps.links.OsmType
import to.pubky.maps.place.db.PlaceCacheDatabase
import java.io.File
import java.nio.file.Files
import java.util.Properties
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Clock
import kotlin.time.Instant

/** The place cache on this machine's SQLite (the app uses Android's), as the place page writes it. */
class SqlPlaceCacheTest {
    private val dir: File = Files.createTempDirectory("place-cache").toFile()
    private val driver =
        JdbcSqliteDriver("jdbc:sqlite:${File(dir, "place-cache.db").path}", Properties()).also { PlaceCacheDatabase.Schema.create(it) }

    @AfterTest
    fun cleanUp() {
        driver.close()
        dir.deleteRecursively()
    }

    private val schober =
        PlaceRef(
            uri = FakePlaceData.SCHOBER,
            name = "Conditorei schober",
            point = null,
            osm = OsmId(OsmType.Node, 270803095),
            source = PlaceSource.Pinakes,
        )

    @Test
    fun aPageJustOpenedWithKeptTagsIsTheNewestCopyNotTheFirstToGo() =
        runTest {
            // Room for one place: another one, saved before the Schober is opened.
            val cache = SqlPlaceCache(driver, kept = 1)
            cache.write(PlaceSnapshot(uri = "geo:47.1,8.5", savedAt = 2_000))
            val pages =
                PlacePageController(
                    data = FakePlaceData().apply { nexusDown = true },
                    cache = cache,
                    scope = this,
                    kept = KeptSchober(fetchedAt = 1_000),
                    clock =
                        object : Clock {
                            override fun now() = Instant.fromEpochMilliseconds(3_000)
                        },
                    io = StandardTestDispatcher(testScheduler),
                )
            pages.open(schober)
            advanceUntilIdle()
            // Its tags are the area's of 1,000; the copy itself is of 3,000, so the older place goes.
            val saved = assertNotNull(cache.read(FakePlaceData.SCHOBER))
            assertEquals(listOf("kept"), saved.social?.tags?.map { it.label })
            assertEquals(3_000, saved.savedAt)
            assertEquals(1_000, saved.socialAt)
            assertNull(cache.read("geo:47.1,8.5"))
        }
}
