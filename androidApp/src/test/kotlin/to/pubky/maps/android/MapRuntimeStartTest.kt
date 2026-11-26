package to.pubky.maps.android

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import to.pubky.maps.offline.configureMapRuntime
import java.io.File

/**
 * MapLibre's old database in the cache folder is deleted, but not on the thread that sets MapLibre up
 * (the main thread at app start, where StrictMode reports the listing and the deletes).
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

        configureMapRuntime(context, "test", scope) { false }
        assertEquals("nothing deleted while MapLibre is set up", names.sorted(), cacheFiles())

        scope.advanceUntilIdle()
        assertEquals("only MapLibre's files", listOf("photos"), cacheFiles())
    }

    private fun cacheFiles(): List<String> = context.cacheDir.list().orEmpty().sorted()
}
