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
import to.pubky.maps.map.AreaPickerUi
import to.pubky.maps.map.MapScreen
import to.pubky.maps.map.MapSearch
import to.pubky.maps.offline.OfflineMap
import to.pubky.maps.offline.OfflineMapStatus
import to.pubky.maps.offline.OfflineMapsScreen
import to.pubky.maps.offline.OfflineMapsState
import to.pubky.maps.search.SearchActions
import to.pubky.maps.search.SearchState
import to.pubky.maps.ui.PubkyMapsTheme

/**
 * Screenshots of offline maps (phone 412x915 dp, tablet 1280x800 dp, light and dark): the list with
 * an area in each state, the empty list, and the area picker on the map with an area that fits and one
 * that is too large. The map is a flat placeholder: MapLibre cannot draw on the JVM.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
@OptIn(ExperimentalRoborazziApi::class)
class OfflineMapsScreenshotTest {
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

    private val maps =
        OfflineMapsState.Ready(
            listOf(
                OfflineMap("d", "Bern", 4, OfflineMapStatus.Downloading(42), 6_300_000),
                OfflineMap("c", "Zürich", 3, OfflineMapStatus.Ready, 18_400_000),
                OfflineMap("b", "Lugano", 2, OfflineMapStatus.Paused(64), 9_100_000),
                // An area the map labels no town in keeps the name of its centre.
                OfflineMap("a", "Map around 46.20° N, 6.14° E", 1, OfflineMapStatus.TooLarge, 50_200_000),
            ),
        )

    @Composable
    private fun List(state: OfflineMapsState = maps) =
        OfflineMapsScreen(state, onDownloadArea = {}, onResume = {}, onDelete = {}, onBack = {})

    @Composable
    private fun MapPlaceholder() {
        val dark = isSystemInDarkTheme()
        Box(
            modifier = Modifier.fillMaxSize().background(if (dark) Color(0xFF2B2D31) else Color(0xFFF2EFE9)),
            contentAlignment = Alignment.Center,
        ) {
            Text("Map", color = if (dark) Color(0xFF8A8D93) else Color(0xFF9A968E))
        }
    }

    @Composable
    private fun Picker(fits: Boolean) {
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
            search = MapSearch(SearchState(), recents = emptyList(), userLocation = null, actions = SearchActions()),
            areaPicker = AreaPickerUi(fits = fits),
        ) { MapPlaceholder() }
    }

    @Test
    fun offlineMapsPhoneLight() = capture("offline-maps", Device.Phone, dark = false) { List() }

    @Test
    fun offlineMapsPhoneDark() = capture("offline-maps", Device.Phone, dark = true) { List() }

    @Test
    fun offlineMapsTabletLight() = capture("offline-maps", Device.Tablet, dark = false) { List() }

    @Test
    fun offlineMapsTabletDark() = capture("offline-maps", Device.Tablet, dark = true) { List() }

    @Test
    fun offlineMapsLargeFontPhoneLight() = capture("offline-maps", Device.Phone, dark = false, largeFont = true) { List() }

    @Test
    fun offlineMapsEmptyPhoneLight() =
        capture("offline-maps-empty", Device.Phone, dark = false) { List(OfflineMapsState.Ready(emptyList())) }

    /** An update that stopped half way (paused, or the app was closed) beside a ready area. */
    @Test
    fun offlineMapsUpdatePausedPhoneLight() =
        capture("offline-maps-update-paused", Device.Phone, dark = false) {
            List(
                OfflineMapsState.Ready(
                    listOf(
                        OfflineMap("b", "Zürich", 2, OfflineMapStatus.Paused(100, update = true), 18_400_000),
                        OfflineMap("a", "Bern", 1, OfflineMapStatus.Ready, 6_300_000),
                    ),
                ),
            )
        }

    @Test
    fun areaPickerPhoneLight() = capture("area-picker", Device.Phone, dark = false) { Picker(fits = true) }

    @Test
    fun areaPickerPhoneDark() = capture("area-picker", Device.Phone, dark = true) { Picker(fits = true) }

    @Test
    fun areaPickerTabletLight() = capture("area-picker", Device.Tablet, dark = false) { Picker(fits = true) }

    @Test
    fun areaPickerTabletDark() = capture("area-picker", Device.Tablet, dark = true) { Picker(fits = true) }

    @Test
    fun areaPickerTooLargePhoneLight() = capture("area-picker-too-large", Device.Phone, dark = false) { Picker(fits = false) }

    @Test
    fun areaPickerLargeFontPhoneLight() = capture("area-picker", Device.Phone, dark = false, largeFont = true) { Picker(fits = true) }
}
