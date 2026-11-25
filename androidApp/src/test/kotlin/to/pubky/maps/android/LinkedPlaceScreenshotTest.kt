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
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import to.pubky.maps.config.ServerDefaults
import to.pubky.maps.links.GeoPoint
import to.pubky.maps.links.LinkProvider
import to.pubky.maps.map.MapScreen
import to.pubky.maps.place.OpeningLinkCard
import to.pubky.maps.place.PlaceCard
import to.pubky.maps.place.PlaceRef
import to.pubky.maps.place.PlaceSource
import to.pubky.maps.ui.PubkyMapsTheme

/**
 * Screenshots of the map with a place opened from a link (phone 412x915 dp, tablet 1280x800 dp, light
 * and dark). The map is a flat placeholder: MapLibre cannot draw on the JVM.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
@OptIn(ExperimentalRoborazziApi::class)
class LinkedPlaceScreenshotTest {
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

    private val zurichHb =
        PlaceRef(
            uri = "https://maps.app.goo.gl/AbCdEf123",
            name = "Zürich Hauptbahnhof",
            point = GeoPoint(47.37786, 8.54032),
            osm = null,
            source = PlaceSource.LinkText,
        )

    @Composable
    private fun MapWith(card: (@Composable () -> Unit)?) {
        val dark = isSystemInDarkTheme()
        MapScreen(
            attribution =
                ServerDefaults.lists
                    .mapStyles(darkTheme = false)
                    .first()
                    .attribution,
            mapUnavailable = false,
            onRetryMap = {},
            onMyLocation = {},
            onOpenSettings = {},
            placeCard = card,
        ) {
            Box(
                modifier = Modifier.fillMaxSize().background(if (dark) Color(0xFF2B2D31) else Color(0xFFF2EFE9)),
                contentAlignment = Alignment.Center,
            ) {
                Text("Map", color = if (dark) Color(0xFF8A8D93) else Color(0xFF9A968E))
            }
        }
    }

    @Composable
    private fun Linked(
        place: PlaceRef = zurichHb,
        provider: LinkProvider = LinkProvider.Google,
    ) = MapWith { PlaceCard(place = place, provider = provider, onShare = {}, onClose = {}) }

    @Test
    fun linkedPlacePhoneLight() = capture("map-linked-place", Device.Phone, dark = false) { Linked() }

    @Test
    fun linkedPlacePhoneDark() = capture("map-linked-place", Device.Phone, dark = true) { Linked() }

    @Test
    fun linkedPlaceTabletLight() = capture("map-linked-place", Device.Tablet, dark = false) { Linked() }

    @Test
    fun linkedPlaceTabletDark() = capture("map-linked-place", Device.Tablet, dark = true) { Linked() }

    @Test
    fun linkedPlaceLargeFontPhoneLight() = capture("map-linked-place", Device.Phone, dark = false, largeFont = true) { Linked() }

    /** A geo: pin without a name, read offline: the coordinates are the title. */
    @Test
    fun linkedPinOfflinePhoneLight() =
        capture("map-linked-pin-offline", Device.Phone, dark = false) {
            Linked(
                place =
                    PlaceRef(
                        uri = "geo:47.37,8.54",
                        name = null,
                        point = GeoPoint(47.37, 8.54),
                        osm = null,
                        source = PlaceSource.LinkText,
                        offline = true,
                    ),
                provider = LinkProvider.Geo,
            )
        }

    /** An Android search link: a query, no point. */
    @Test
    fun linkedSearchPhoneDark() =
        capture("map-linked-search", Device.Phone, dark = true) {
            Linked(
                place =
                    PlaceRef(
                        uri = "geo:0,0?q=Bahnhofstrasse%201%20Z%C3%BCrich",
                        name = null,
                        point = null,
                        osm = null,
                        source = PlaceSource.LinkText,
                        query = "Bahnhofstrasse 1 Zürich",
                    ),
                provider = LinkProvider.Geo,
            )
        }

    /** A Google short link that could not be followed (offline): the name from the shared text only. */
    @Test
    fun linkedShortLinkOfflinePhoneLight() =
        capture("map-linked-short-link-offline", Device.Phone, dark = false) {
            Linked(
                place =
                    PlaceRef(
                        uri = "https://maps.app.goo.gl/AbCdEf123",
                        name = "Zürich Hauptbahnhof",
                        point = null,
                        osm = null,
                        source = PlaceSource.LinkText,
                        offline = true,
                        shortLinkUnfollowed = true,
                    ),
            )
        }

    @Test
    fun openingLinkPhoneLight() = capture("map-opening-link", Device.Phone, dark = false) { MapWith { OpeningLinkCard() } }
}
