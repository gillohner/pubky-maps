package to.pubky.maps.config

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import to.pubky.maps.map.StyleFallback
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SettingsRepositoryTest {
    private val fileSystem = FakeFileSystem()
    private val path = "/data/files/datastore/settings.json".toPath()

    @AfterTest
    fun noFileLeftOpen() {
        fileSystem.checkNoOpenFiles()
    }

    /** Runs [block] with a repository on the test file, then closes it (DataStore allows one per file). */
    private suspend fun TestScope.withRepository(
        codec: SettingsCodec = SettingsCodec(),
        block: suspend (SettingsRepository) -> Unit,
    ) {
        val job = Job()
        val repository = DataStoreSettingsRepository(fileSystem, path, CoroutineScope(coroutineContext + job), codec)
        try {
            block(repository)
        } finally {
            job.cancelAndJoin()
        }
    }

    private suspend fun SettingsRepository.loaded(): AppSettings = settings.filterNotNull().first()

    @Test
    fun withoutAFileTheDefaultsApply() =
        runTest {
            withRepository { assertEquals(AppSettings(), it.loaded()) }
            assertFalse(fileSystem.exists(path))
        }

    @Test
    fun editsAreStoredAndReadBackAfterARestart() =
        runTest {
            withRepository { repository ->
                repository.loaded()
                repository.update { it.withOpenIn(OpenInApp.Waze, false) }
                repository.update { it.withStorage(DataKind.Lists, StorageChoice.Public) }
                repository.update { it.withServerMoved(Service.MapStyleDark, "https://tiles.openfreemap.org/styles/dark", 1) }
                repository.update { it.copy(followLocalRules = false) }.join()
                assertFalse(repository.settings.first { it?.followLocalRules == false }!!.followLocalRules)
            }
            assertTrue(fileSystem.exists(path))

            withRepository { repository ->
                val settings = repository.loaded()
                assertFalse(settings.isOpenInEnabled(OpenInApp.Waze))
                assertEquals(StorageChoice.Public, settings.storage(DataKind.Lists))
                assertEquals("VersaTiles Colorful Dark", settings.servers[Service.MapStyleDark].first().name)
                assertFalse(settings.followLocalRules)
            }
        }

    @Test
    fun resetIsStoredToo() =
        runTest {
            withRepository { repository ->
                repository.update { it.withServerRemoved(Service.PkarrRelay, "https://pkarr.pubky.app") }.join()
            }
            withRepository { assertFalse(it.loaded().isDefaultServerList(Service.PkarrRelay)) }
            withRepository { repository -> repository.update { it.withServersReset(Service.PkarrRelay) }.join() }
            withRepository { assertTrue(it.loaded().isDefaultServerList(Service.PkarrRelay)) }
        }

    @Test
    fun anUnreadableFileIsReplacedByTheMostPrivateSettings() =
        runTest {
            fileSystem.createDirectories(path.parent!!)
            fileSystem.write(path) { writeUtf8("{ this is not json") }

            withRepository { repository ->
                assertEquals(AppSettings.afterLostFile(), repository.loaded())
                repository.update { it.withOpenIn(OpenInApp.HereWeGo, false) }.join()
            }
            withRepository { assertFalse(it.loaded().isOpenInEnabled(OpenInApp.HereWeGo)) }
        }

    @Test
    fun aChangeThatNoLongerAppliesIsDroppedWithoutStoppingTheStore() =
        runTest {
            withRepository { repository ->
                repository.update { it.withStorage(DataKind.Tags, StorageChoice.PhoneOnly) }.join()
                repository.update { it.withOpenIn(OpenInApp.OsmAnd, false) }.join()
                assertFalse(
                    repository.settings.first { it?.isOpenInEnabled(OpenInApp.OsmAnd) == false }!!.isOpenInEnabled(OpenInApp.OsmAnd),
                )
                assertEquals(StorageChoice.Public, repository.loaded().storage(DataKind.Tags))
            }
        }

    @Test
    fun anOlderFileIsMigratedWhenRead() =
        runTest {
            fileSystem.createDirectories(path.parent!!)
            fileSystem.write(path) { writeUtf8("""{ "schemaVersion": 1, "open_in": { "comaps": false } }""") }
            val v2 =
                SettingsCodec(
                    schemaVersion = 2,
                    migrations = mapOf(1 to SettingsMigration { JsonObject(it - "open_in" + ("openIn" to it.getValue("open_in"))) }),
                )

            withRepository(v2) { repository ->
                assertFalse(repository.loaded().isOpenInEnabled(OpenInApp.CoMaps))
                repository.update { it.withOpenIn(OpenInApp.Waze, false) }.join()
            }
            val stored = fileSystem.read(path) { readUtf8() }
            assertTrue("\"schemaVersion\": 2" in stored, stored)
            assertFalse("open_in" in stored, stored)
        }

    @Test
    fun theMapUsesTheStoredStyleListWithItsFallbacks() =
        runTest {
            val own = "https://tiles.example.org/style.json"
            withRepository { repository ->
                repository
                    .update {
                        (it.withServerAdded(Service.MapStyleLight, own, "Own") as AppSettings.AddServerResult.Added)
                            .settings
                            .withServerMoved(Service.MapStyleLight, own, -2)
                    }.join()
            }
            withRepository { repository ->
                val styles = repository.loaded().servers.mapStyles(darkTheme = false)
                val fallback = StyleFallback(styles)

                assertEquals(own, fallback.current?.url)
                val next = fallback.failed(own)
                assertEquals("https://tiles.openfreemap.org/styles/liberty", next.current?.url)
                assertEquals("https://tiles.versatiles.org/assets/styles/colorful/style.json", next.failed(next.current!!.url).current?.url)
                assertEquals(
                    ServerDefaults.lists[Service.MapStyleDark].first(),
                    StyleFallback(repository.loaded().servers.mapStyles(darkTheme = true)).current,
                )
            }
        }
}
