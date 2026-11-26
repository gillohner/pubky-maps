package to.pubky.maps.android

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RoborazziComposeOptions
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.fontScale
import com.github.takahirom.roborazzi.size
import com.github.takahirom.roborazzi.uiMode
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import to.pubky.maps.config.AttributionLink
import to.pubky.maps.config.ServerDefaults
import to.pubky.maps.links.GeoPoint
import to.pubky.maps.links.OsmId
import to.pubky.maps.links.OsmType
import to.pubky.maps.map.MapScreen
import to.pubky.maps.map.MapSearch
import to.pubky.maps.offline.OfflinePlace
import to.pubky.maps.place.PlaceCard
import to.pubky.maps.place.PlaceRef
import to.pubky.maps.place.PlaceSource
import to.pubky.maps.search.PlaceKind
import to.pubky.maps.search.Prominence
import to.pubky.maps.search.RecentSearch
import to.pubky.maps.search.SearchActions
import to.pubky.maps.search.SearchArea
import to.pubky.maps.search.SearchMode
import to.pubky.maps.search.SearchRanking
import to.pubky.maps.search.SearchResult
import to.pubky.maps.search.SearchState
import to.pubky.maps.search.SourceState
import to.pubky.maps.search.distanceText
import to.pubky.maps.ui.PubkyMapsTheme

/**
 * Screenshots of search (phone 412x915 dp, tablet 1280x800 dp, light and dark): the empty bar with
 * recent searches, typing with results, the results on the map, a picked place, typed coordinates and
 * search offline. The map is a flat placeholder: MapLibre cannot draw on the JVM, so the pins are not
 * in these pictures (the emulator test sees them).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalRoborazziApi::class)
class SearchScreenshotTest {
    private enum class Device(
        val widthDp: Int,
        val heightDp: Int,
    ) {
        Phone(412, 915),
        Tablet(1280, 800),
    }

    private fun capture(
        name: String,
        device: Device,
        dark: Boolean,
        largeFont: Boolean = false,
        content: @Composable () -> Unit,
    ) {
        val theme = if (dark) "dark" else "light"
        val variant = if (largeFont) "-large-font" else ""
        captureRoboImage(
            filePath = "screenshots/$name$variant-${device.name.lowercase()}-$theme.png",
            roborazziComposeOptions =
                RoborazziComposeOptions {
                    size(widthDp = device.widthDp, heightDp = device.heightDp)
                    uiMode(if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO)
                    if (largeFont) fontScale(2f)
                },
        ) {
            PubkyMapsTheme { content() }
        }
    }

    /** The user stands near Zürich's main station. */
    private val here = GeoPoint(47.3779, 8.5403)

    private fun place(
        name: String?,
        lat: Double,
        lon: Double,
        osm: OsmId?,
        source: PlaceSource,
        category: String?,
        address: String?,
    ) = PlaceRef(
        uri = osm?.url ?: "geo:$lat,$lon",
        name = name,
        point = GeoPoint(lat, lon),
        osm = osm,
        source = source,
        zoom = 17.0,
        address = address,
        category = category,
    )

    private val kunsthaus =
        SearchResult(
            place(
                "Kunsthaus",
                47.3702241,
                8.5479797,
                OsmId(OsmType.Relation, 3768127),
                PlaceSource.Pinakes,
                "Museum",
                "Heimplatz 1, 8001 Zürich",
            ),
            photonRank = 0,
            pinakesRank = 0,
            collections = 2,
            prominence = Prominence.Landmark,
            kind = PlaceKind.Culture,
        )

    private val results =
        listOf(
            kunsthaus,
            SearchResult(
                place(
                    "Kunsthaus",
                    47.3698,
                    8.5483,
                    OsmId(OsmType.Node, 4878436674),
                    PlaceSource.Photon,
                    "Tram stop",
                    "Heimplatz, 8001 Zürich",
                ),
                photonRank = 1,
                prominence = Prominence.Minor,
                kind = PlaceKind.Transit,
            ),
            SearchResult(
                place(
                    "Kunsthaus",
                    47.3699,
                    8.5481,
                    OsmId(OsmType.Node, 1608392108),
                    PlaceSource.Photon,
                    "Bus stop",
                    "Heimplatz, 8001 Zürich",
                ),
                photonRank = 2,
                prominence = Prominence.Minor,
                kind = PlaceKind.Transit,
            ),
            SearchResult(
                place(
                    "Kunsthaus Zürich Shop",
                    47.3701,
                    8.5476,
                    OsmId(OsmType.Node, 7),
                    PlaceSource.Photon,
                    "Bookshop",
                    "Heimplatz 1, 8001 Zürich",
                ),
                photonRank = 3,
                kind = PlaceKind.Shop,
            ),
            SearchResult(
                place("Kunsthaus Aarau", 47.3904, 8.0457, OsmId(OsmType.Way, 8), PlaceSource.Photon, "Museum", "Aargauerplatz, 5000 Aarau"),
                photonRank = 4,
                prominence = Prominence.Landmark,
                kind = PlaceKind.Culture,
            ),
        )

    private val recents =
        listOf(
            RecentSearch(
                "conditorei schober",
                place(
                    "Conditorei schober",
                    47.3708689,
                    8.5418241,
                    OsmId(OsmType.Node, 270803095),
                    PlaceSource.Pinakes,
                    "Café",
                    "Napfgasse 4, 8001 Zürich",
                ),
            ),
            RecentSearch("zoo"),
            RecentSearch(
                "uetliberg",
                place("Uetliberg", 47.3494459, 8.4919649, OsmId(OsmType.Node, 29971007), PlaceSource.Photon, "Peak", "8143 Stallikon"),
            ),
            RecentSearch("47.37, 8.54"),
            RecentSearch("bahnhofstrasse 1"),
        )

    @Composable
    private fun Map(
        state: SearchState,
        credits: List<AttributionLink> = ServerDefaults.lists.searchAttribution(),
        card: (@Composable () -> Unit)? = null,
    ) {
        val dark = isSystemInDarkTheme()
        MapScreen(
            attribution =
                ServerDefaults.lists
                    .mapStyles(darkTheme = dark)
                    .first()
                    .attribution,
            mapUnavailable = false,
            onRetryMap = {},
            onMyLocation = {},
            onOpenSettings = {},
            placeCard = card,
            search =
                MapSearch(
                    state,
                    recents,
                    userLocation = here,
                    actions = SearchActions(),
                    credits = credits,
                ),
        ) {
            Box(
                modifier = Modifier.fillMaxSize().background(if (dark) Color(0xFF2B2D31) else Color(0xFFF2EFE9)),
                contentAlignment = Alignment.Center,
            ) {
                Text("Map", color = if (dark) Color(0xFF8A8D93) else Color(0xFF9A968E))
            }
        }
    }

    private val emptyBar = SearchState(mode = SearchMode.Editing)

    private val typing =
        SearchState(
            mode = SearchMode.Editing,
            query = "kunsthaus",
            resultsQuery = "kunsthaus",
            results = results,
            photon = SourceState.Answered,
            pinakes = SourceState.Answered,
        )

    private val onMap = typing.copy(mode = SearchMode.Results)

    private val coordinates =
        PlaceRef(
            uri = "geo:47.37,8.54",
            name = null,
            point = GeoPoint(47.37, 8.54),
            osm = null,
            source = PlaceSource.Coordinates,
            zoom = 16.0,
            category = "Coordinates",
        )

    @Composable
    private fun Picked(
        place: PlaceRef,
        query: String,
    ) {
        val picked = SearchResult(place)
        Map(SearchState(mode = SearchMode.Selected, query = query, resultsQuery = query, results = listOf(picked), selected = picked)) {
            PlaceCard(place = place, provider = null, onShare = {}, onClose = {}, distance = distanceText(here, place.point))
        }
    }

    @Test
    fun recentPhoneLight() = capture("search-recent", Device.Phone, dark = false) { Map(emptyBar) }

    @Test
    fun recentPhoneDark() = capture("search-recent", Device.Phone, dark = true) { Map(emptyBar) }

    @Test
    fun recentTabletLight() = capture("search-recent", Device.Tablet, dark = false) { Map(emptyBar) }

    @Test
    fun recentTabletDark() = capture("search-recent", Device.Tablet, dark = true) { Map(emptyBar) }

    @Test
    fun typingPhoneLight() = capture("search-typing", Device.Phone, dark = false) { Map(typing) }

    @Test
    fun typingPhoneDark() = capture("search-typing", Device.Phone, dark = true) { Map(typing) }

    @Test
    fun typingTabletLight() = capture("search-typing", Device.Tablet, dark = false) { Map(typing) }

    @Test
    fun typingTabletDark() = capture("search-typing", Device.Tablet, dark = true) { Map(typing) }

    @Test
    fun typingLargeFontPhoneLight() = capture("search-typing", Device.Phone, dark = false, largeFont = true) { Map(typing) }

    @Test
    fun resultsPhoneLight() = capture("search-results", Device.Phone, dark = false) { Map(onMap) }

    @Test
    fun resultsPhoneDark() = capture("search-results", Device.Phone, dark = true) { Map(onMap) }

    @Test
    fun resultsTabletLight() = capture("search-results", Device.Tablet, dark = false) { Map(onMap) }

    @Test
    fun resultsTabletDark() = capture("search-results", Device.Tablet, dark = true) { Map(onMap) }

    @Test
    fun placePhoneLight() = capture("search-place", Device.Phone, dark = false) { Picked(kunsthaus.place, "kunsthaus") }

    @Test
    fun placePhoneDark() = capture("search-place", Device.Phone, dark = true) { Picked(kunsthaus.place, "kunsthaus") }

    @Test
    fun placeTabletLight() = capture("search-place", Device.Tablet, dark = false) { Picked(kunsthaus.place, "kunsthaus") }

    @Test
    fun placeTabletDark() = capture("search-place", Device.Tablet, dark = true) { Picked(kunsthaus.place, "kunsthaus") }

    @Test
    fun coordinatesPhoneLight() = capture("search-coordinates", Device.Phone, dark = false) { Picked(coordinates, "47.37, 8.54") }

    @Test
    fun coordinatesPhoneDark() = capture("search-coordinates", Device.Phone, dark = true) { Picked(coordinates, "47.37, 8.54") }

    @Test
    fun coordinatesTabletLight() = capture("search-coordinates", Device.Tablet, dark = false) { Picked(coordinates, "47.37, 8.54") }

    @Test
    fun coordinatesTabletDark() = capture("search-coordinates", Device.Tablet, dark = true) { Picked(coordinates, "47.37, 8.54") }

    /** Typed coordinates before Search is pressed: one row, no network. */
    @Test
    fun coordinatesTypingPhoneLight() =
        capture("search-coordinates-typing", Device.Phone, dark = false) {
            val point = SearchResult(coordinates)
            Map(SearchState(mode = SearchMode.Editing, query = "47.37, 8.54", resultsQuery = "47.37, 8.54", results = listOf(point)))
        }

    @Test
    fun offlinePhoneLight() =
        capture("search-offline", Device.Phone, dark = false) {
            Map(
                SearchState(
                    mode = SearchMode.Editing,
                    query = "kunsthaus",
                    resultsQuery = "kunsthaus",
                    photon = SourceState.Unreachable,
                    pinakes = SourceState.Unreachable,
                ),
            )
        }

    @Test
    fun offlineTabletDark() =
        capture("search-offline", Device.Tablet, dark = true) {
            Map(
                SearchState(
                    mode = SearchMode.Editing,
                    query = "kunsthaus",
                    resultsQuery = "kunsthaus",
                    photon = SourceState.Unreachable,
                    pinakes = SourceState.Unreachable,
                ),
            )
        }

    /** No connection: the places come from an offline area around Zürich's main station. */
    private val fromOfflineMaps: SearchState
        get() {
            val offline =
                listOf(
                    OfflinePlace(
                        "Kunsthaus Zürich",
                        OsmId(OsmType.Relation, 3768127),
                        "tourism",
                        "museum",
                        "Museum",
                        GeoPoint(47.3702, 8.5480),
                        locality = "Hottingen, Zürich",
                    ),
                    OfflinePlace(
                        "Kunsthausstrasse",
                        null,
                        "highway",
                        "minor",
                        "Street",
                        GeoPoint(47.3697, 8.5486),
                        locality = "Hottingen, Zürich",
                    ),
                    OfflinePlace(
                        "Kunsthaus Bar",
                        OsmId(OsmType.Node, 4_012_345_678),
                        "amenity",
                        "bar",
                        "Bar",
                        GeoPoint(47.3705, 8.5476),
                        locality = "Altstadt, Zürich",
                    ),
                )
            val area = SearchArea(here, zoom = 15.0, halfDiagonalMeters = 1_500.0)
            return SearchState(
                mode = SearchMode.Editing,
                query = "kunsthaus",
                resultsQuery = "kunsthaus",
                results = SearchRanking.rank("kunsthaus", area, emptyList(), emptyList(), offline),
                photon = SourceState.Unreachable,
                pinakes = SourceState.Unreachable,
                offlineMaps = SourceState.Answered,
            )
        }

    @Composable
    private fun OfflineMapsResults() = Map(fromOfflineMaps, credits = ServerDefaults.lists.mapAttribution())

    @Test
    fun offlineMapsPhoneLight() = capture("search-offline-maps", Device.Phone, dark = false) { OfflineMapsResults() }

    @Test
    fun offlineMapsPhoneDark() = capture("search-offline-maps", Device.Phone, dark = true) { OfflineMapsResults() }

    @Test
    fun offlineMapsTabletLight() = capture("search-offline-maps", Device.Tablet, dark = false) { OfflineMapsResults() }

    @Test
    fun offlineMapsTabletDark() = capture("search-offline-maps", Device.Tablet, dark = true) { OfflineMapsResults() }

    @Test
    fun pinakesMissingPhoneLight() =
        capture("search-pinakes-missing", Device.Phone, dark = false) {
            Map(typing.copy(results = results.drop(1), pinakes = SourceState.Unreachable))
        }
}
