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
    fun oursThroughTheAppsIoLayersIsOurs() {
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
        // A cache query run on the main thread: SQLite, AndroidX's SQLite, then SQLDelight's driver.
        val database =
            arrayOf(
                frame("libcore.io.BlockGuardOs"),
                frame("android.database.sqlite.SQLiteConnection"),
                frame("androidx.sqlite.db.framework.FrameworkSQLiteDatabase"),
                frame("app.cash.sqldelight.driver.android.AndroidSqliteDriver"),
                frame("app.cash.sqldelight.Query"),
                frame("to.pubky.maps.place.AndroidPlacesKt"),
            )
        assertEquals(
            "ours DiskReadViolation at to.pubky.maps.place.AndroidPlacesKt.call(File.kt:1)",
            strictModeReport("DiskReadViolation", database),
        )
        // The Rust core's first call loads its library through JNA, which looks for the file on disk.
        // (The core's own files and sockets are native calls; StrictMode never sees them.)
        val core =
            arrayOf(
                frame("java.io.File"),
                frame("com.sun.jna.NativeLibrary"),
                frame("com.sun.jna.Native"),
                frame("to.pubky.maps.core.UniffiLib"),
            )
        assertEquals("ours DiskReadViolation at to.pubky.maps.core.UniffiLib.call(File.kt:1)", strictModeReport("DiskReadViolation", core))
    }

    @Test
    fun aLookupOfTheAppsPrivateFolderIsNotHeldAgainstUs() {
        val stack =
            arrayOf(
                frame("libcore.io.BlockGuardOs"),
                frame("java.io.File"),
                StackTraceElement("android.app.ContextImpl", "ensurePrivateDirExists", "ContextImpl.java", 819),
                StackTraceElement("android.app.ContextImpl", "getNoBackupFilesDir", "ContextImpl.java", 883),
                frame("android.content.ContextWrapper"),
                frame("to.pubky.maps.android.AccountKt"),
            )
        assertEquals(
            "app-folder DiskReadViolation at to.pubky.maps.android.AccountKt.call(File.kt:1)",
            strictModeReport("DiskReadViolation", stack),
        )
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

    /**
     * MapLibre's runtime made on the main thread (a stack the CI emulator logged): its native library
     * load, where JavaCPP reads its properties from the APK, asked for by [caller].
     */
    private fun mapRuntimeStart(vararg caller: StackTraceElement) =
        arrayOf(
            frame("java.util.zip.ZipFile\$ZipFileInputStream"),
            frame("java.util.Properties"),
            StackTraceElement("org.bytedeco.javacpp.Loader", "loadProperties", "Loader.java", 210),
            StackTraceElement("org.bytedeco.javacpp.Loader", "load", "Loader.java", 1210),
            StackTraceElement("org.maplibre.nativeffi.internal.javacpp.MaplibreNativeC", "<clinit>", "MaplibreNativeC.java", 10),
            StackTraceElement("org.maplibre.nativeffi.MaplibreAndroid", "initialize", "MaplibreAndroid.kt", 16),
            StackTraceElement("org.maplibre.compose.map.MapRuntime_nativeKt", "createMapRuntime", "MapRuntime.native.kt", 100),
            StackTraceElement("org.maplibre.compose.map.DefaultMapRuntime", "getInstance", "DefaultMapRuntime.kt", 41),
            *caller,
            frame("kotlinx.coroutines.flow.internal.ChannelFlowTransformLatest"),
            frame("android.app.ActivityThread"),
        )

    @Test
    fun mapLibresRuntimeMadeOnTheMainThreadIsOursAtTheCodeThatAskedForIt() {
        val stack =
            mapRuntimeStart(
                StackTraceElement(
                    "to.pubky.maps.offline.AndroidOfflineMapsKt",
                    "androidOfflineMaps\$lambda\$0",
                    "AndroidOfflineMaps.kt",
                    51,
                ),
                frame("to.pubky.maps.offline.MapLibrePackStore"),
            )
        assertEquals(
            "ours DiskReadViolation at to.pubky.maps.offline.AndroidOfflineMapsKt.androidOfflineMaps\$lambda\$0(AndroidOfflineMaps.kt:51)",
            strictModeReport("DiskReadViolation", stack),
        )
    }

    @Test
    fun mapLibresRuntimeMadeOnTheMainThreadByMapLibreItselfIsOursToo() {
        // The map's composable asked first: the start in the background came too late.
        val stack = mapRuntimeStart(frame("org.maplibre.compose.map.MaplibreMapKt"), frame("androidx.compose.runtime.Composer"))
        assertEquals(
            "ours DiskReadViolation at org.maplibre.compose.map.DefaultMapRuntime.getInstance(DefaultMapRuntime.kt:41)",
            strictModeReport("DiskReadViolation", stack),
        )
    }

    @Test
    fun aLeakOpenedWhileMapLibreMakesItsRuntimeIsTheLibrarys() {
        // A leak's stack is where the resource was opened: here the start in the background.
        val stack =
            arrayOf(
                frame("java.io.FileInputStream"),
                StackTraceElement("org.bytedeco.javacpp.Loader", "loadProperties", "Loader.java", 210),
                StackTraceElement("org.maplibre.compose.map.DefaultMapRuntime", "getInstance", "DefaultMapRuntime.kt", 41),
                frame("to.pubky.maps.offline.AndroidOfflineMapsKt"),
            )
        assertEquals(
            "library LeakedClosableViolation at org.bytedeco.javacpp.Loader.loadProperties(Loader.java:210)",
            strictModeReport("LeakedClosableViolation", stack),
        )
    }

    /** StrictMode's made-up stack of an instance count violation. */
    private val instanceCountStack = arrayOf(StackTraceElement("android.os.StrictMode", "setClassInstanceLimit", "StrictMode.java", 1))

    @Test
    fun aLeakedActivityOfOursIsOurs() {
        assertEquals(
            "ours InstanceCountViolation at to.pubky.maps.android.MainActivity",
            strictModeReport(
                "InstanceCountViolation",
                instanceCountStack,
                "class to.pubky.maps.android.MainActivity; instances=2; limit=1",
            ),
        )
    }

    @Test
    fun aLeakedActivityOfALibraryIsTheLibrarys() {
        assertEquals(
            "library InstanceCountViolation at com.example.SignInActivity",
            strictModeReport("InstanceCountViolation", instanceCountStack, "class com.example.SignInActivity; instances=3; limit=2"),
        )
    }

    @Test
    fun aMessageThatNamesNoLeakedClassLeavesTheStackToDecide() {
        val stack = arrayOf(frame("java.io.FileInputStream"), frame("to.pubky.maps.search.RecentSearches"))
        assertEquals(
            "ours DiskReadViolation at to.pubky.maps.search.RecentSearches.call(File.kt:1)",
            strictModeReport("DiskReadViolation", stack, "class to.pubky.maps.Other read a file"),
        )
    }

    @Test
    fun aStackOfOnlyThePlatformIsNotOurs() {
        val stack = arrayOf(frame("java.io.FileInputStream"), frame("android.app.ActivityThread"))
        assertEquals("library LeakedClosableViolation at the platform", strictModeReport("LeakedClosableViolation", stack))
    }
}
