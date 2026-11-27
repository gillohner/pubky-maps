package to.pubky.maps.android

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.StrictMode
import android.os.strictmode.Violation
import android.util.Log
import androidx.annotation.RequiresApi
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** Reports from one background thread, so logging never slows the thread that was caught. */
private val reporter: ExecutorService by lazy {
    Executors.newSingleThreadExecutor { task -> Thread(task, "StrictMode reports").apply { isDaemon = true } }
}

/** The log tag of StrictMode's reports; scripts/ci/strict_mode.py reads it from the emulator tests' logs. */
internal const val STRICT_MODE_TAG = "PubkyMapsStrictMode"

/**
 * The packages of Android, Java, Kotlin and AndroidX, and the I/O layers the app calls directly:
 * SQLDelight (its database), okio (its files) and JNA (the Rust core). A
 * violation's stack is read from the top and these frames are skipped: the first frame after them is
 * the code that asked for the slow call. So a blocking DataStore read, a database query, an okio file
 * read, a `runBlocking` or the core's library load on the main thread is blamed on the app code that
 * made it. StrictMode sees only Java I/O: the Rust core's own files and sockets are native calls it
 * never sees (the core's calls are suspend functions, which keeps them off the main thread).
 */
private val platformPackages =
    listOf(
        "android.",
        "androidx.",
        "app.cash.sqldelight.",
        "com.android.",
        "com.sun.jna.",
        "dalvik.",
        "java.",
        "javax.",
        "jdk.",
        "kotlin.",
        "kotlinx.",
        "libcore.",
        "okio.",
        "sun.",
    )

/** The first frame of [stack] that is not in the platform: the code a violation is blamed on. */
internal fun blamedFrame(stack: Array<StackTraceElement>): StackTraceElement? =
    stack.firstOrNull { frame -> platformPackages.none { frame.className.startsWith(it) } }

/**
 * Context's lookup of the app's private folders (`filesDir`, `noBackupFilesDir`, ...): a check that
 * the folder exists, and on the first start its mkdir. It takes microseconds and every Android app
 * makes it on the main thread, so it is listed but not held against our code.
 */
private fun isPrivateFolderLookup(frame: StackTraceElement): Boolean =
    frame.className == "android.app.ContextImpl" && frame.methodName in setOf("ensurePrivateDirExists", "getDataDir")

/**
 * MapLibre making its process runtime, which loads its native library from the APK. The app makes it
 * in the background as its map screen is created (`startMapRuntime`), so the main thread reading disk
 * for it means that start came too late or was lost: it is held against our code wherever the call
 * came from. Only for the main thread's own kinds: a leak's stack is where the resource was opened,
 * which for a library's leak is the background start.
 */
private fun isMapRuntimeStart(frame: StackTraceElement): Boolean =
    frame.className == "org.maplibre.compose.map.DefaultMapRuntime" && frame.methodName == "getInstance"

/** The kinds of violation the main thread's policy raises for disk work there. */
private val mainThreadDiskKinds = setOf("DiskReadViolation", "DiskWriteViolation", "UnbufferedIoViolation")

/**
 * The class named by an instance count violation's message ("class to.pubky.maps.android.MainActivity;
 * instances=2; limit=1"). StrictMode raises it for a leaked activity: when an activity is destroyed
 * and, after a garbage collection, more instances of its class are in memory than the app still runs
 * plus one. Its stack is one made-up frame (`StrictMode.setClassInstanceLimit`), so the leak is blamed
 * on the class that leaked.
 */
private val leakedClass = Regex("""^class ([\w.$]+); instances=""")

/**
 * One log line for a violation: "ours" when the app's own code made the call, "app-folder" when
 * that call was only a lookup of the app's private folders, "library" when a library made it
 * (MapLibre, Ktor, ...); then the kind and the blamed frame. A leaked activity ([message] names its
 * class) is "ours" when the activity is one of ours, and is reported at its class. Disk work on the
 * main thread for MapLibre making its runtime is "ours", at the app code that asked for it, else at
 * MapLibre's call.
 */
internal fun strictModeReport(
    kind: String,
    stack: Array<StackTraceElement>,
    message: String? = null,
): String {
    val leaked = message?.let { leakedClass.find(it) }?.groupValues?.get(1)
    if (leaked != null) {
        return "${if (leaked.startsWith("to.pubky.maps.")) "ours" else "library"} $kind at $leaked"
    }
    val runtimeStart = if (kind in mainThreadDiskKinds) stack.indexOfFirst(::isMapRuntimeStart) else -1
    if (runtimeStart >= 0) {
        val asked = stack.drop(runtimeStart).firstOrNull { it.className.startsWith("to.pubky.maps.") }
        return "ours $kind at ${asked ?: stack[runtimeStart]}"
    }
    val frame = blamedFrame(stack)
    val owner =
        when {
            frame?.className?.startsWith("to.pubky.maps.") != true -> "library"
            stack.any(::isPrivateFolderLookup) -> "app-folder"
            else -> "ours"
        }
    return "$owner $kind at ${frame ?: "the platform"}"
}

/** [strictModeReport] of a violation StrictMode raised. */
@RequiresApi(Build.VERSION_CODES.P)
internal fun strictModeReport(violation: Violation): String =
    strictModeReport(violation.javaClass.simpleName, violation.stackTrace, violation.message)

/**
 * Debug builds only: StrictMode reports disk access, unbuffered I/O, custom slow calls and resource
 * mismatches on the main thread, and leaked resources and content URIs shared without permission, to
 * the log (a leak is seen only when the garbage collector runs). The emulator tests read the log and
 * fail on a report about our code (an ANR waiting to happen on a slow phone). Release builds do not
 * have it.
 *
 * Both policies start from the ones the platform set before any provider runs, and keep its two death
 * penalties: network on the main thread and a file:// URI handed to another app crash the debug app
 * as they crash the release app, whichever code made the call. Our own additions only report.
 */
class StrictModeProvider : ContentProvider() {
    override fun onCreate(): Boolean {
        val thread =
            StrictMode.ThreadPolicy
                .Builder(StrictMode.getThreadPolicy())
                .detectDiskReads()
                .detectDiskWrites()
                .detectNetwork()
                .penaltyDeathOnNetwork()
                .detectCustomSlowCalls()
                .detectResourceMismatches()
                .detectUnbufferedIo()
        val vm =
            StrictMode.VmPolicy
                .Builder(StrictMode.getVmPolicy())
                .detectLeakedClosableObjects()
                .detectLeakedSqlLiteObjects()
                .detectLeakedRegistrationObjects()
                .detectActivityLeaks()
                .detectFileUriExposure()
                .penaltyDeathOnFileUriExposure()
                .detectContentUriWithoutPermission()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            thread.penaltyListener(reporter, ::report)
            vm.penaltyListener(reporter, ::report)
        } else {
            // Before Android 9 StrictMode logs under its own tag, which the emulator tests do not read.
            thread.penaltyLog()
            vm.penaltyLog()
        }
        StrictMode.setThreadPolicy(thread.build())
        StrictMode.setVmPolicy(vm.build())
        return true
    }

    @RequiresApi(Build.VERSION_CODES.P)
    private fun report(violation: Violation) {
        Log.w(STRICT_MODE_TAG, strictModeReport(violation), violation)
    }

    // The provider holds no data; it exists only to run onCreate before the app.
    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(
        uri: Uri,
        values: ContentValues?,
    ): Uri? = null

    override fun delete(
        uri: Uri,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0
}
