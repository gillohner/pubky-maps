package to.pubky.maps.links

import to.pubky.maps.config.AppSettings
import to.pubky.maps.config.OpenInApp
import to.pubky.maps.place.PlaceRef
import to.pubky.maps.settings.title

/** Which map apps are on the phone. */
interface InstalledApps {
    /** True when the app with [packageName] is installed (and visible to Pubky Maps). */
    fun isInstalled(packageName: String): Boolean

    /** True when some app opens `geo:` links. */
    fun canOpenGeo(): Boolean
}

/** One entry of a place's "Open in" list. */
data class OpenInAction(
    val app: OpenInApp,
    val title: String,
    val uri: String,
    /** The app that opens [uri]; null lets the system pick (a browser, or a chooser for `geo:`). */
    val packageName: String?,
    /** True when [uri] opens a web page because the app is not installed or has no Android app. */
    val inBrowser: Boolean,
)

/** The "Open in" list: the apps switched on in settings, each with the link it opens. */
object OpenIn {
    /** The Android packages of each app, the usual one first (CoMaps ships per store). */
    fun packages(app: OpenInApp): List<String> =
        when (app) {
            OpenInApp.GoogleMaps -> listOf("com.google.android.apps.maps")
            OpenInApp.AppleMaps -> emptyList()
            OpenInApp.Waze -> listOf("com.waze")
            OpenInApp.OrganicMaps -> listOf("app.organicmaps")
            OpenInApp.CoMaps -> listOf("app.comaps.fdroid", "app.comaps.google", "app.comaps")
            OpenInApp.OsmAnd -> listOf("net.osmand.plus", "net.osmand")
            OpenInApp.HereWeGo -> listOf("com.here.app.maps")
            OpenInApp.AnyGeoApp -> emptyList()
        }

    /**
     * The actions for [place], or for directions to it when [directions] is set. Apps that are
     * installed come first, then the ones that open in a browser, then "Other map apps"; within each
     * group the settings order. An app without a link for the target (directions in Organic Maps) is
     * left out, and so is a not-installed app that has no web link.
     */
    fun actions(
        place: LinkTarget,
        settings: AppSettings,
        installed: InstalledApps,
        links: MapLinks,
        directions: TravelMode? = null,
    ): List<OpenInAction> {
        fun link(format: LinkFormat): String? =
            if (directions == null) links.placeLink(format, place) else links.directionsLink(format, place, directions)

        val actions =
            OpenInApp.entries.filter(settings::isOpenInEnabled).mapNotNull { app ->
                val pkg = packages(app).firstOrNull(installed::isInstalled)
                val (format, inBrowser) =
                    when (app) {
                        OpenInApp.GoogleMaps -> LinkFormat.GoogleMaps to (pkg == null)

                        OpenInApp.AppleMaps -> LinkFormat.AppleMaps to true

                        OpenInApp.Waze -> LinkFormat.Waze to (pkg == null)

                        OpenInApp.OrganicMaps -> (if (pkg != null) LinkFormat.OrganicMaps else LinkFormat.OrganicMapsWeb) to (pkg == null)

                        OpenInApp.CoMaps -> (if (pkg != null) LinkFormat.CoMaps else LinkFormat.CoMapsWeb) to (pkg == null)

                        // OsmAnd reads a labelled geo: link; its web map is the fallback.
                        OpenInApp.OsmAnd -> (if (pkg != null) LinkFormat.Geo else LinkFormat.OsmAnd) to (pkg == null)

                        OpenInApp.HereWeGo -> LinkFormat.HereWeGo to (pkg == null)

                        OpenInApp.AnyGeoApp -> (if (installed.canOpenGeo()) LinkFormat.Geo else null) to false
                    }
                val uri = format?.let(::link) ?: return@mapNotNull null
                OpenInAction(app = app, title = app.title, uri = uri, packageName = pkg, inBrowser = inBrowser)
            }
        return actions.sortedBy {
            when {
                it.app == OpenInApp.AnyGeoApp -> 2
                it.inBrowser -> 1
                else -> 0
            }
        }
    }

    /**
     * What sharing a place sends: its name and a link anyone can open: the OpenStreetMap element when
     * the place is one, an openstreetmap.org pin for a pinned point, else the link the place came from
     * (a map view or a search is shared as the link, never turned into a pin).
     */
    fun shareText(
        place: PlaceRef,
        links: MapLinks,
    ): String {
        val point = place.point?.takeIf { place.pinned }
        val link =
            place.osm?.url
                ?: point?.let { links.placeLink(LinkFormat.OpenStreetMap, LinkTarget(it, place.name, zoom = place.zoom)) }
                ?: place.uri
        val name = place.name?.trim().orEmpty()
        return if (name.isEmpty()) link else "$name\n$link"
    }
}
