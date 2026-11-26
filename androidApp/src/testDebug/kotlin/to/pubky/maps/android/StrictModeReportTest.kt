package to.pubky.maps.android

import org.junit.Assert.assertEquals
import org.junit.Test

class StrictModeReportTest {
    private fun frame(className: String) = StackTraceElement(className, "call", "File.kt", 1)

    @Test
    fun aMainThreadReadInOurCodeIsOurs() {
        val stack =
            arrayOf(
                frame("android.os.StrictMode\$AndroidBlockGuardPolicy"),
                frame("libcore.io.BlockGuardOs"),
                frame("java.io.FileInputStream"),
                frame("to.pubky.maps.config.SettingsStore"),
                frame("android.app.ActivityThread"),
            )
        assertEquals(
            "ours DiskReadViolation at to.pubky.maps.config.SettingsStore.call(File.kt:1)",
            strictModeReport("DiskReadViolation", stack),
        )
    }

    @Test
    fun aBlockingAndroidXCallIsBlamedOnTheAppCodeThatMadeIt() {
        val stack =
            arrayOf(
                frame("java.io.FileInputStream"),
                frame("androidx.datastore.core.SingleProcessDataStore"),
                frame("kotlinx.coroutines.BuildersKt"),
                frame("to.pubky.maps.android.PubkyMapsApplication"),
            )
        assertEquals(
            "ours DiskReadViolation at to.pubky.maps.android.PubkyMapsApplication.call(File.kt:1)",
            strictModeReport("DiskReadViolation", stack),
        )
    }

    @Test
    fun oursThroughOkioOrTheCoreIsOurs() {
        val okio =
            arrayOf(
                frame("java.io.FileInputStream"),
                frame("okio.JvmSystemFileSystem"),
                frame("to.pubky.maps.search.RecentSearches"),
            )
        assertEquals(
            "ours DiskReadViolation at to.pubky.maps.search.RecentSearches.call(File.kt:1)",
            strictModeReport("DiskReadViolation", okio),
        )
        val core =
            arrayOf(
                frame("java.net.Socket"),
                frame("com.sun.jna.Native"),
                frame("to.pubky.maps.core.UniffiLib"),
            )
        assertEquals("ours NetworkViolation at to.pubky.maps.core.UniffiLib.call(File.kt:1)", strictModeReport("NetworkViolation", core))
    }

    @Test
    fun aLibraryCalledFromOurCodeIsTheLibrarys() {
        val stack =
            arrayOf(
                frame("java.io.RandomAccessFile"),
                frame("org.maplibre.android.storage.FileSource"),
                frame("to.pubky.maps.map.MapRoute"),
            )
        assertEquals(
            "library DiskReadViolation at org.maplibre.android.storage.FileSource.call(File.kt:1)",
            strictModeReport("DiskReadViolation", stack),
        )
    }

    @Test
    fun aStackOfOnlyThePlatformIsNotOurs() {
        val stack = arrayOf(frame("java.io.FileInputStream"), frame("android.app.ActivityThread"))
        assertEquals("library LeakedClosableViolation at the platform", strictModeReport("LeakedClosableViolation", stack))
    }
}
