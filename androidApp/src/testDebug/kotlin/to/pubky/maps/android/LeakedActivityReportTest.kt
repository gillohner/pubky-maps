package to.pubky.maps.android

import android.app.Activity
import android.os.strictmode.Violation
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The report of the violation Android raises for a leaked activity: built by Android's own
 * `InstanceCountViolation` (a hidden class, made by reflection), so the test follows its message and
 * its made-up stack as the platform writes them.
 */
@RunWith(RobolectricTestRunner::class)
class LeakedActivityReportTest {
    private fun instanceCountViolation(
        leaked: Class<*>,
        instances: Long,
        limit: Int,
    ): Violation =
        Class
            .forName("android.os.strictmode.InstanceCountViolation")
            .getConstructor(Class::class.java, Long::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            .newInstance(leaked, instances, limit) as Violation

    @Test
    fun aLeakedMainActivityIsOurs() {
        assertEquals(
            "ours InstanceCountViolation at to.pubky.maps.android.MainActivity",
            strictModeReport(instanceCountViolation(MainActivity::class.java, 2, 1)),
        )
    }

    @Test
    fun aLeakedPlatformActivityIsNotOurs() {
        assertEquals(
            "library InstanceCountViolation at android.app.Activity",
            strictModeReport(instanceCountViolation(Activity::class.java, 2, 1)),
        )
    }
}
