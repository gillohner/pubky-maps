package to.pubky.maps.android

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.isActive
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import to.pubky.maps.offline.configureMapRuntime
import to.pubky.maps.offline.startMapRuntime
import java.io.File

/**
 * MapLibre's old database in the cache folder is deleted, and its runtime made, but not on the thread
 * that asks for it (the main thread at app start, where StrictMode reports the listing and the
 * deletes, and the runtime's native library load).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class MapRuntimeStartTest {
    private val context: Application = ApplicationProvider.getApplicationContext()

    @Test
    fun theOldMapCacheIsDeletedLaterInTheGivenScope() {
        val names = listOf("maplibre-cache.db", "maplibre-cache.db-journal", "maplibre-cache.db-wal", "photos")
        names.forEach { File(context.cacheDir, it).writeText("x") }
        val scope = TestScope()

        configureMapRuntime(context, "test", scope, downloading = { false }, fetchedNewData = {}, keepsCopy = { false })
        assertEquals("nothing deleted while MapLibre is set up", names.sorted(), cacheFiles())

        scope.advanceUntilIdle()
        assertEquals("only MapLibre's files", listOf("photos"), cacheFiles())
    }

    @Test
    fun theRuntimeIsMadeLaterInTheGivenScope() {
        val scope = TestScope()
        var made = 0

        startMapRuntime(scope) { made++ }
        assertEquals("not made by the caller", 0, made)

        scope.advanceUntilIdle()
        assertEquals(1, made)
    }

    @Test
    fun aRuntimeThatCannotBeMadeLeavesTheScopeRunning() {
        val scope = TestScope()

        startMapRuntime(scope) { throw UnsatisfiedLinkError("no maplibre-native-c") }
        scope.advanceUntilIdle()

        // A failure that left the scope would cancel it (the app's scope has no handler: it would crash).
        assertTrue("the scope still runs", scope.isActive)
    }

    private fun cacheFiles(): List<String> {
        val names = context.cacheDir.list() ?: return emptyList()
        return names.sorted()
    }
}
