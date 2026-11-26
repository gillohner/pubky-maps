package to.pubky.maps.android

import android.net.Uri
import android.os.FileUriExposedException
import android.os.NetworkOnMainThreadException
import android.os.StrictMode
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.lang.reflect.InvocationTargetException

/**
 * The debug build's StrictMode keeps the platform's two death penalties: network on the main thread
 * and a file:// URI handed to another app crash the debug app as they crash the release app. Both
 * checks start from no policy at all, so they hold whatever the platform set before the provider ran.
 * The platform's checks are hidden methods; they are called by reflection.
 */
@RunWith(RobolectricTestRunner::class)
class StrictModeProviderTest {
    private lateinit var threadBefore: StrictMode.ThreadPolicy
    private lateinit var vmBefore: StrictMode.VmPolicy

    @Before
    fun startWithoutPolicies() {
        threadBefore = StrictMode.getThreadPolicy()
        vmBefore = StrictMode.getVmPolicy()
        StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.LAX)
        StrictMode.setVmPolicy(StrictMode.VmPolicy.LAX)
        StrictModeProvider().onCreate()
    }

    @After
    fun restorePolicies() {
        StrictMode.setThreadPolicy(threadBefore)
        StrictMode.setVmPolicy(vmBefore)
    }

    /** Calls [type]'s method [name] on [target] (null for a static method) and throws what the call threw. */
    private fun callHidden(
        type: Class<*>,
        target: Any?,
        name: String,
        vararg args: Pair<Class<*>, Any?>,
    ) {
        val method = type.getMethod(name, *args.map { it.first }.toTypedArray())
        try {
            method.invoke(target, *args.map { it.second }.toTypedArray())
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }
    }

    @Test
    fun networkOnTheMainThreadStillCrashes() {
        // What libcore asks before every socket call on this thread.
        val policy = Class.forName("dalvik.system.BlockGuard").getMethod("getThreadPolicy").invoke(null)
        assertThrows(NetworkOnMainThreadException::class.java) {
            callHidden(Class.forName("dalvik.system.BlockGuard\$Policy"), policy, "onNetwork")
        }
    }

    @Test
    fun aFileUriHandedToAnotherAppStillCrashes() {
        // What an Intent carrying a file:// URI asks when it leaves the app.
        assertThrows(FileUriExposedException::class.java) {
            callHidden(
                StrictMode::class.java,
                null,
                "onFileUriExposed",
                Uri::class.java to Uri.parse("file:///data/user/0/to.pubky.maps/files/place.gpx"),
                String::class.java to "Intent.getData()",
            )
        }
    }
}
