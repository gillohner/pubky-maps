package to.pubky.maps.links

import to.pubky.maps.config.AppSettings
import to.pubky.maps.config.OpenInApp
import to.pubky.maps.place.PlaceRef
import to.pubky.maps.place.PlaceSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OpenInTest {
    private val links = FakeMapLinks()
    private val place = LinkTarget(GeoPoint(47.37, 8.54), "Zürich HB")

    private fun installed(
        vararg packages: String,
        geo: Boolean = true,
    ) = object : InstalledApps {
        override fun isInstalled(packageName: String) = packageName in packages

        override fun canOpenGeo() = geo
    }

    @Test
    fun nothingInstalledOpensWebLinksAndHidesAppOnlyEntries() {
        val actions = OpenIn.actions(place, AppSettings(), installed(geo = false), links)
        // Apple Maps is off by default; without a geo: app "Other map apps" is hidden.
        assertEquals(
            listOf(OpenInApp.GoogleMaps, OpenInApp.Waze, OpenInApp.OrganicMaps, OpenInApp.CoMaps, OpenInApp.OsmAnd, OpenInApp.HereWeGo),
            actions.map { it.app },
        )
        assertTrue(actions.all { it.inBrowser && it.packageName == null })
        assertEquals("OrganicMapsWeb:47.37,8.54", actions.first { it.app == OpenInApp.OrganicMaps }.uri)
        assertEquals("CoMapsWeb:47.37,8.54", actions.first { it.app == OpenInApp.CoMaps }.uri)
        assertEquals("OsmAnd:47.37,8.54", actions.first { it.app == OpenInApp.OsmAnd }.uri)
        assertEquals("Google Maps", actions.first().title)
    }

    @Test
    fun installedAppsComeFirstAndOpenInTheirApp() {
        val actions =
            OpenIn.actions(place, AppSettings(), installed("app.organicmaps", "net.osmand", "app.comaps.google"), links)
        assertEquals(
            listOf(
                OpenInApp.OrganicMaps,
                OpenInApp.CoMaps,
                OpenInApp.OsmAnd,
                OpenInApp.GoogleMaps,
                OpenInApp.Waze,
                OpenInApp.HereWeGo,
                OpenInApp.AnyGeoApp,
            ),
            actions.map { it.app },
        )
        val organic = actions[0]
        assertEquals("OrganicMaps:47.37,8.54", organic.uri)
        assertEquals("app.organicmaps", organic.packageName)
        assertEquals(false, organic.inBrowser)
        assertEquals("app.comaps.google", actions[1].packageName)
        // OsmAnd gets a labelled geo: link in its own app.
        assertEquals("Geo:47.37,8.54", actions[2].uri)
        assertEquals("net.osmand", actions[2].packageName)
        val other = actions.last()
        assertEquals("Geo:47.37,8.54", other.uri)
        assertNull(other.packageName)
    }

    @Test
    fun settingsSwitchesDecide() {
        val settings =
            AppSettings()
                .withOpenIn(OpenInApp.AppleMaps, true)
                .withOpenIn(OpenInApp.GoogleMaps, false)
                .withOpenIn(OpenInApp.AnyGeoApp, false)
        val apps = OpenIn.actions(place, settings, installed(), links).map { it.app }
        assertTrue(OpenInApp.AppleMaps in apps)
        assertTrue(OpenInApp.GoogleMaps !in apps)
        assertTrue(OpenInApp.AnyGeoApp !in apps)
        val apple = OpenIn.actions(place, settings, installed(), links).first { it.app == OpenInApp.AppleMaps }
        assertTrue(apple.inBrowser)
    }

    @Test
    fun directionsOnlyWhereTheAppHasThem() {
        val actions =
            OpenIn.actions(place, AppSettings(), installed("com.waze", "app.organicmaps"), links, directions = TravelMode.Walking)
        // Organic Maps, CoMaps, OsmAnd and geo: have no directions link; Waze drives only.
        assertEquals(listOf(OpenInApp.GoogleMaps, OpenInApp.HereWeGo), actions.map { it.app })
        val driving =
            OpenIn.actions(place, AppSettings(), installed("com.waze"), links, directions = TravelMode.Driving)
        assertEquals(OpenInApp.Waze, driving.first().app)
        assertEquals("com.waze", driving.first().packageName)
    }

    @Test
    fun shareTextNamesThePlaceAndAnOpenLink() {
        fun ref(
            name: String?,
            point: GeoPoint?,
            osm: OsmId? = null,
        ) = PlaceRef(uri = "https://maps.app.goo.gl/abc", name = name, point = point, osm = osm, source = PlaceSource.LinkText)

        assertEquals("Zürich HB\nOpenStreetMap:47.37,8.54", OpenIn.shareText(ref("Zürich HB", GeoPoint(47.37, 8.54)), links))
        assertEquals(
            "Zürich HB\nhttps://www.openstreetmap.org/way/4",
            OpenIn.shareText(ref("Zürich HB", GeoPoint(47.37, 8.54), OsmId(OsmType.Way, 4)), links),
        )
        assertEquals("https://maps.app.goo.gl/abc", OpenIn.shareText(ref(null, null), links))
        // A map view or a search near a point is shared as its link, never turned into a pin.
        val view = ref(null, GeoPoint(47.37, 8.54)).copy(uri = "https://www.google.com/maps/@47.37,8.54,15z", pointIsView = true)
        assertEquals("https://www.google.com/maps/@47.37,8.54,15z", OpenIn.shareText(view, links))
        val search = ref(null, GeoPoint(47.37, 8.54)).copy(uri = "geo:47.37,8.54?q=restaurants", query = "restaurants")
        assertEquals("geo:47.37,8.54?q=restaurants", OpenIn.shareText(search, links))
    }
}
