package to.pubky.maps.android.links

import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInfo
import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AndroidLinksTest {
    private val context = RuntimeEnvironment.getApplication()

    @Test
    fun viewIntentsGiveTheirUri() {
        val geo = Intent(Intent.ACTION_VIEW, Uri.parse("geo:47.37,8.54?q=Test"))
        assertEquals("geo:47.37,8.54?q=Test", geo.mapLinkText())
        val google = Intent(Intent.ACTION_VIEW, Uri.parse("https://maps.app.goo.gl/AbCdEf123"))
        assertEquals("https://maps.app.goo.gl/AbCdEf123", google.mapLinkText())
    }

    @Test
    fun sendIntentsGiveTheirTextWithTheSubject() {
        val send =
            Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, "https://maps.apple/p/AbC.dEf")
                .putExtra(Intent.EXTRA_SUBJECT, "Café Sprüngli")
        assertEquals("Café Sprüngli\nhttps://maps.apple/p/AbC.dEf", send.mapLinkText())
    }

    @Test
    fun otherIntentsGiveNothing() {
        assertNull(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).mapLinkText())
        assertNull(Intent(Intent.ACTION_SEND).setType("text/plain").mapLinkText())
    }

    @Test
    fun installedAppsAndGeoHandlers() {
        val apps = AndroidInstalledApps(context)
        assertFalse(apps.isInstalled("app.organicmaps"))
        assertFalse(apps.canOpenGeo())
        val pm = shadowOf(context.packageManager)
        pm.installPackage(PackageInfo().apply { packageName = "app.organicmaps" })
        assertTrue(apps.isInstalled("app.organicmaps"))
        val map = ComponentName("app.organicmaps", "app.organicmaps.MwmActivity")
        pm.addActivityIfNotPresent(map)
        pm.addIntentFilterForActivity(
            map,
            IntentFilter(Intent.ACTION_VIEW).apply {
                addCategory(Intent.CATEGORY_DEFAULT)
                addDataScheme("geo")
            },
        )
        assertTrue(apps.canOpenGeo())
    }
}
