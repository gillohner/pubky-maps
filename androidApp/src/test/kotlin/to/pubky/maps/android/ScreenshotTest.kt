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
import com.github.takahirom.roborazzi.size
import com.github.takahirom.roborazzi.uiMode
import com.mikepenz.aboutlibraries.Libs
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import to.pubky.maps.AppInfo
import to.pubky.maps.config.ServerDefaults
import to.pubky.maps.map.MapScreen
import to.pubky.maps.settings.LicencesScreen
import to.pubky.maps.settings.SettingsScreen
import to.pubky.maps.ui.PubkyMapsTheme

/**
 * Screenshots of every screen on a phone (412x915 dp) and a tablet (1280x800 dp), light and dark.
 * MapLibre cannot draw on the JVM, so the map screen shows a flat placeholder under its controls.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
@OptIn(ExperimentalRoborazziApi::class)
class ScreenshotTest {
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
        content: @Composable () -> Unit,
    ) {
        val theme = if (dark) "dark" else "light"
        captureRoboImage(
            filePath = "screenshots/$name-${device.name.lowercase()}-$theme.png",
            roborazziComposeOptions =
                RoborazziComposeOptions {
                    size(widthDp = device.widthDp, heightDp = device.heightDp)
                    uiMode(if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO)
                },
        ) {
            PubkyMapsTheme { content() }
        }
    }

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
    private fun Map(mapUnavailable: Boolean = false) {
        MapScreen(
            attribution =
                ServerDefaults.mapStyles.light
                    .first()
                    .attribution,
            mapUnavailable = mapUnavailable,
            onRetryMap = {},
            onMyLocation = {},
            onOpenSettings = {},
        ) { MapPlaceholder() }
    }

    @Composable
    private fun Settings() {
        SettingsScreen(
            appInfo = AppInfo(versionName = "0.1.0", versionCode = 1),
            serverConfig = ServerDefaults.config,
            onOpenLicences = {},
            onBack = {},
        )
    }

    @Composable
    private fun Licences() {
        LicencesScreen(libraries = Libs.Builder().withJson(LICENCES_JSON).build(), onBack = {})
    }

    @Test
    fun mapPhoneLight() = capture("map", Device.Phone, dark = false) { Map() }

    @Test
    fun mapPhoneDark() = capture("map", Device.Phone, dark = true) { Map() }

    @Test
    fun mapTabletLight() = capture("map", Device.Tablet, dark = false) { Map() }

    @Test
    fun mapTabletDark() = capture("map", Device.Tablet, dark = true) { Map() }

    @Test
    fun mapUnavailablePhoneLight() = capture("map-unavailable", Device.Phone, dark = false) { Map(mapUnavailable = true) }

    @Test
    fun settingsPhoneLight() = capture("settings", Device.Phone, dark = false) { Settings() }

    @Test
    fun settingsPhoneDark() = capture("settings", Device.Phone, dark = true) { Settings() }

    @Test
    fun settingsTabletLight() = capture("settings", Device.Tablet, dark = false) { Settings() }

    @Test
    fun settingsTabletDark() = capture("settings", Device.Tablet, dark = true) { Settings() }

    @Test
    fun licencesPhoneLight() = capture("licences", Device.Phone, dark = false) { Licences() }

    @Test
    fun licencesPhoneDark() = capture("licences", Device.Phone, dark = true) { Licences() }

    @Test
    fun licencesTabletLight() = capture("licences", Device.Tablet, dark = false) { Licences() }

    @Test
    fun licencesTabletDark() = capture("licences", Device.Tablet, dark = true) { Licences() }

    private companion object {
        /** A fixed licence list, so the screenshots do not change with every dependency update. */
        const val LICENCES_JSON = """
            {
              "libraries": [
                {
                  "uniqueId": "org.maplibre.compose:maplibre-compose",
                  "artifactVersion": "0.20.0",
                  "name": "MapLibre Compose",
                  "developers": [{ "name": "MapLibre" }],
                  "licenses": ["BSD-3-Clause"]
                },
                {
                  "uniqueId": "androidx.compose.ui:ui",
                  "artifactVersion": "1.12.0",
                  "name": "Compose UI",
                  "developers": [{ "name": "The Android Open Source Project" }],
                  "licenses": ["Apache-2.0"]
                },
                {
                  "uniqueId": "com.mikepenz:aboutlibraries-core",
                  "artifactVersion": "15.3.0",
                  "name": "AboutLibraries Core Library",
                  "developers": [{ "name": "Mike Penz" }],
                  "licenses": ["Apache-2.0"]
                }
              ],
              "licenses": {
                "BSD-3-Clause": { "name": "BSD 3-Clause License", "url": "https://spdx.org/licenses/BSD-3-Clause.html", "spdxId": "BSD-3-Clause" },
                "Apache-2.0": { "name": "Apache License 2.0", "url": "https://spdx.org/licenses/Apache-2.0.html", "spdxId": "Apache-2.0" }
              }
            }
        """
    }
}
