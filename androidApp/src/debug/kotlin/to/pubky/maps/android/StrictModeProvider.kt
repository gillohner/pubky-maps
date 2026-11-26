package to.pubky.maps.android

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.StrictMode
import android.os.strictmode.Violation
import android.util.Log
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** Reports from one background thread, so logging never slows the thread that was caught. */
private val reporter: ExecutorService by lazy {
    Executors.newSingleThreadExecutor { task -> Thread(task, "StrictMode reports").apply { isDaemon = true } }
}

/** The log tag of StrictMode's reports; scripts/ci/strict_mode.py reads it from the emulator tests' logs. */
internal const val STRICT_MODE_TAG = "PubkyMapsStrictMode"

/**
 * The packages of Android, Java, Kotlin and AndroidX. A violation's stack is read from the top and
 * these frames are skipped: the first frame after them is the code that asked for the slow call.
 * AndroidX and kotlinx are skipped too, so a blocking DataStore read or a `runBlocking` on the main
 * thread is blamed on the app code that made it.
 */
private val platformPackages =
    listOf("android.", "androidx.", "com.android.", "dalvik.", "java.", "javax.", "jdk.", "kotlin.", "kotlinx.", "libcore.", "sun.")

/** The first frame of [stack] that is not in the platform: the code a violation is blamed on. */
internal fun blamedFrame(stack: Array<StackTraceElement>): StackTraceElement? =
    stack.firstOrNull { frame -> platformPackages.none { frame.className.startsWith(it) } }

/**
 * One log line for a violation: "ours" when the app's own code made the call, "library" when a
 * library did (MapLibre, Ktor, ...), then the kind and the blamed frame.
 */
internal fun strictModeReport(
    kind: String,
    stack: Array<StackTraceElement>,
): String {
    val frame = blamedFrame(stack)
    val owner = if (frame?.className?.startsWith("to.pubky.maps.") == true) "ours" else "library"
    return "$owner $kind at ${frame ?: "the platform"}"
}

/**
 * Debug builds only: StrictMode reports disk and network access on the main thread and leaked
 * resources to the log. It never stops the app; the emulator tests read the log and fail on a
 * report about our code (an ANR waiting to happen on a slow phone). Release builds do not have it.
 */
class StrictModeProvider : ContentProvider() {
    override fun onCreate(): Boolean {
        val thread =
            StrictMode.ThreadPolicy
                .Builder()
                .detectDiskReads()
                .detectDiskWrites()
                .detectNetwork()
                .detectCustomSlowCalls()
                .detectResourceMismatches()
                .detectUnbufferedIo()
        val vm =
            StrictMode.VmPolicy
                .Builder()
                .detectLeakedClosableObjects()
                .detectLeakedSqlLiteObjects()
                .detectLeakedRegistrationObjects()
                .detectActivityLeaks()
                .detectFileUriExposure()
                .detectContentUriWithoutPermission()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            thread.penaltyListener(reporter, ::report)
            vm.penaltyListener(reporter, ::report)
        } else {
            thread.penaltyLog()
            vm.penaltyLog()
        }
        StrictMode.setThreadPolicy(thread.build())
        StrictMode.setVmPolicy(vm.build())
        return true
    }

    private fun report(violation: Violation) {
        Log.w(STRICT_MODE_TAG, strictModeReport(violation.javaClass.simpleName, violation.stackTrace), violation)
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
