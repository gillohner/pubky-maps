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
    fun ringReturnsGoToTheAccountAndMapLinksToTheMap() {
        val ring = Intent(Intent.ACTION_VIEW, Uri.parse("pubkymaps://auth/success?nonce=abc"))
        assertEquals(IncomingIntent.RingReturn("pubkymaps://auth/success?nonce=abc"), ring.incoming())
        val link = Intent(Intent.ACTION_VIEW, Uri.parse("geo:47.37,8.54"))
        assertEquals(IncomingIntent.MapLink("geo:47.37,8.54"), link.incoming())
        // Reopened from Recents, the task replays the intent that started it: already handled.
        assertNull(Intent(link).addFlags(Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY).incoming())
        assertNull(Intent(ring).addFlags(Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY).incoming())
        assertNull(Intent(Intent.ACTION_MAIN).incoming())
    }

    /** True when the app's manifest takes [intent] (with the app as its only possible target). */
    private fun appTakes(intent: Intent): Boolean =
        context.packageManager
            .queryIntentActivities(intent.setPackage(context.packageName), 0)
            .any { it.activityInfo.name == "to.pubky.maps.android.MainActivity" }

    private fun view(url: String) =
        Intent(Intent.ACTION_VIEW, Uri.parse(url))
            .addCategory(Intent.CATEGORY_DEFAULT)
            .addCategory(Intent.CATEGORY_BROWSABLE)

    @Test
    fun theManifestTakesEachMapAppsLinks() {
        for (url in listOf(
            "geo:47.37,8.54?q=Test",
            "om://map?v=1&ll=47.37,8.54&n=Test",
            "cm://map?v=1&ll=47.37,8.54",
            "ge0://B4srhdHVVt/Berlin",
            "https://www.google.com/maps/place/Z%C3%BCrich+HB/@47.37,8.53,17z",
            "https://google.com/maps?q=47.37,8.54",
            "https://maps.google.com/?q=28.118860,98.008069",
            "http://maps.google.com/maps?q=loc:47.37,8.54",
            "https://maps.app.goo.gl/AbCdEf123",
            "https://goo.gl/maps/Cji0V",
            "https://maps.apple.com/place?coordinate=47.37,8.54&name=Test",
            "https://maps.apple.com/?ll=47.37,8.54&q=Test",
            "https://maps.apple/p/AbC.dEf",
            "https://waze.com/ul?ll=47.37,8.54",
            "https://www.waze.com/ul/hu0qjd2eyk",
            "https://www.waze.com/live-map?h=u0qjd2eyk",
            "https://www.waze.com/de/live-map/directions?to=ll.47.37%2C8.54",
            "https://www.openstreetmap.org/node/240109189",
            "https://osm.org/way/50637691",
            "https://www.openstreetmap.org/relation/146656",
            "https://www.openstreetmap.org/?mlat=52.5&mlon=13.4",
            "https://osm.org/go/0GAjIv8h?m",
            "https://osmand.net/map?pin=52.51,13.39",
            "https://osmand.net/go?lat=52.5&lon=13.4&z=17",
            "https://omaps.app/B4srhdHVVt/Berlin",
            "https://ge0.me/B4srhdHVVt/Berlin",
            "https://comaps.at/B4srhdHVVt/Berlin",
            "https://share.here.com/l/52.51,13.37,Name?z=16",
            "https://share.here.com/r/mylocation/47.37,8.54,Office?m=d",
            "https://www.here.com/location?map=52.51,13.37,16,normal&msg=Name",
        )) {
            assertTrue(url, appTakes(view(url)))
        }
        val shared = Intent(Intent.ACTION_SEND).setType("text/plain").addCategory(Intent.CATEGORY_DEFAULT)
        assertTrue("shared text", appTakes(shared))
        // Pages of those sites that are not maps stay with the browser.
        for (url in listOf(
            "https://www.google.com/",
            "https://www.google.com/search?q=pizza",
            "https://www.openstreetmap.org/user/someone",
            "https://www.openstreetmap.org/changeset/1",
            "https://www.waze.com/",
            "https://osmand.net/docs",
            "https://example.com/maps",
        )) {
            assertFalse(url, appTakes(view(url)))
        }
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
