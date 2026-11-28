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
import to.pubky.maps.config.ServerDefaults
import to.pubky.maps.links.GeoPoint
import to.pubky.maps.map.MapScreen
import to.pubky.maps.map.routeCredits
import to.pubky.maps.routing.Directions
import to.pubky.maps.routing.GuidanceUi
import to.pubky.maps.routing.Route
import to.pubky.maps.routing.RouteProgress
import to.pubky.maps.routing.RouteStatus
import to.pubky.maps.routing.RouteStep
import to.pubky.maps.routing.TravelMode
import to.pubky.maps.routing.Turn
import to.pubky.maps.status.ServicePart

/**
 * Screenshots of the map while a route guides the user (phone 412x915 dp, tablet 1280x800 dp, light
 * and dark): the next step's banner on top, the time left and End at the bottom; off the route with a
 * new route to ask for and Re-centre after the user moved the map; arrived; at the largest font; the
 * voice's switch on, off, and missing where the phone cannot speak.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalRoborazziApi::class)
class GuidanceScreenshotTest {
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
            ScreenshotTheme { content() }
        }
    }

    private val hb = GeoPoint(47.3779, 8.5403)
    private val kunsthaus = GeoPoint(47.3702, 8.5486)

    // FOSSGIS' route by car from Zürich HB to the Kunsthaus, its steps where they are on the line.
    private val route =
        Route(
            listOf(hb, kunsthaus),
            seconds = 289.373,
            meters = 1401.0,
            steps =
                listOf(
                    RouteStep("Drive north.", Turn.Depart, 35.0, hb, 0),
                    RouteStep("Turn right onto Museumstrasse.", Turn.Right, 52.0, hb, 0),
                    RouteStep("Bear right onto Bahnhofquai.", Turn.SlightRight, 30.0, hb, 0),
                    RouteStep("Keep left to stay on Bahnhofquai.", Turn.KeepLeft, 406.0, hb, 0),
                    RouteStep("Turn left onto Rudolf-Brun-Brücke.", Turn.Left, 329.0, hb, 1),
                    RouteStep("Your destination is on the right.", Turn.Arrive, 0.0, kunsthaus, 1),
                ),
        )

    /** On Bahnhofquai, 150 m before the bridge. */
    private val onTheWay =
        RouteProgress(
            segment = 0,
            along = 530.0,
            offBy = 3.0,
            offRoute = false,
            arrived = false,
            nextStep = 4,
            toNextStep = 150.0,
            metersLeft = 870.0,
            secondsLeft = 180.0,
            ahead = 180.0,
        )

    private fun guidance(
        progress: RouteProgress?,
        following: Boolean = true,
        status: RouteStatus = RouteStatus.Found(route, "https://valhalla1.openstreetmap.de"),
        // On a phone that can speak, the voice's switch is over the bar.
        voice: Boolean? = true,
    ) = GuidanceUi(
        directions = Directions("osm:relation/3768127", kunsthaus, TravelMode.Car, hb, status, guiding = true, progress = progress),
        placeName = "Kunsthaus Zürich",
        arrival = "12:03",
        following = following,
        onRecenter = {},
        onRouteAgain = {},
        onEnd = {},
        credits = routeCredits(ServerDefaults.lists, (status as? RouteStatus.Found)?.server),
        voice = voice,
    )

    /** 120 m off the route, the map following the user, the directions [changed]. */
    private fun offRoute(changed: (Directions) -> Directions) =
        guidance(onTheWay.copy(offRoute = true, offBy = 120.0)).let { it.copy(directions = changed(it.directions)) }

    @Composable
    private fun Guiding(
        ui: GuidanceUi,
        missing: Set<ServicePart> = emptySet(),
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
            missing = missing,
            guidance = ui,
        ) {
            Box(Modifier.fillMaxSize().background(placeholderMapColor(dark)), contentAlignment = Alignment.Center) {
                Text("Map", color = if (dark) Color(0xFF8A8D93) else Color(0xFF9A968E))
            }
        }
    }

    @Test
    fun nextStepPhoneLight() = capture("guidance", Device.Phone, dark = false) { Guiding(guidance(onTheWay)) }

    @Test
    fun nextStepPhoneDark() = capture("guidance", Device.Phone, dark = true) { Guiding(guidance(onTheWay)) }

    @Test
    fun nextStepTabletLight() = capture("guidance", Device.Tablet, dark = false) { Guiding(guidance(onTheWay)) }

    @Test
    fun nextStepTabletDark() = capture("guidance", Device.Tablet, dark = true) { Guiding(guidance(onTheWay)) }

    @Test
    fun nextStepLargeFontPhoneLight() = capture("guidance", Device.Phone, dark = false, largeFont = true) { Guiding(guidance(onTheWay)) }

    /** The voice switched off: the speaker crossed out, in the control colour instead of the app's. */
    @Test
    fun voiceOffPhoneLight() = capture("guidance-voice-off", Device.Phone, dark = false) { Guiding(guidance(onTheWay, voice = false)) }

    @Test
    fun voiceOffPhoneDark() = capture("guidance-voice-off", Device.Phone, dark = true) { Guiding(guidance(onTheWay, voice = false)) }

    /** A phone without a speech engine or an English voice: no voice switch. */
    @Test
    fun noVoicePhoneLight() = capture("guidance-no-voice", Device.Phone, dark = false) { Guiding(guidance(onTheWay, voice = null)) }

    /** Off the route, the map moved by the user: Find a new route in the banner, Re-centre over the bar. */
    @Test
    fun offRoutePhoneLight() =
        capture("guidance-off-route", Device.Phone, dark = false) {
            Guiding(guidance(onTheWay.copy(offRoute = true, offBy = 120.0), following = false))
        }

    @Test
    fun offRoutePhoneDark() =
        capture("guidance-off-route", Device.Phone, dark = true) {
            Guiding(guidance(onTheWay.copy(offRoute = true, offBy = 120.0), following = false))
        }

    @Test
    fun offRouteTabletLight() =
        capture("guidance-off-route", Device.Tablet, dark = false) {
            Guiding(guidance(onTheWay.copy(offRoute = true, offBy = 120.0), following = false))
        }

    /** No new route came: why, with Find a new route again, the route still guiding; routing named as missing. */
    @Test
    fun newRouteUnreachablePhoneDark() =
        capture("guidance-unreachable", Device.Phone, dark = true) {
            Guiding(offRoute { it.copy(noNewRoute = RouteStatus.Unreachable) }, missing = setOf(ServicePart.Routing))
        }

    /** Off the route, a new route asked for by itself: the route on the map and what is left of it stay meanwhile. */
    @Test
    fun reroutingPhoneLight() =
        capture("guidance-rerouting", Device.Phone, dark = false) {
            Guiding(offRoute { it.copy(rerouting = true) })
        }

    @Test
    fun reroutingPhoneDark() =
        capture("guidance-rerouting", Device.Phone, dark = true) {
            Guiding(offRoute { it.copy(rerouting = true) })
        }

    @Test
    fun reroutingTabletLight() =
        capture("guidance-rerouting", Device.Tablet, dark = false) {
            Guiding(offRoute { it.copy(rerouting = true) })
        }

    @Test
    fun reroutingTabletDark() =
        capture("guidance-rerouting", Device.Tablet, dark = true) {
            Guiding(offRoute { it.copy(rerouting = true) })
        }

    @Test
    fun arrivedPhoneLight() =
        capture("guidance-arrived", Device.Phone, dark = false) {
            Guiding(guidance(onTheWay.copy(arrived = true, nextStep = 5, toNextStep = 10.0, metersLeft = 10.0, secondsLeft = 3.0)))
        }

    @Test
    fun arrivedPhoneDark() =
        capture("guidance-arrived", Device.Phone, dark = true) {
            Guiding(guidance(onTheWay.copy(arrived = true, nextStep = 5, toNextStep = 10.0, metersLeft = 10.0, secondsLeft = 3.0)))
        }
}
