package to.pubky.maps.map

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.maplibre.compose.camera.CameraMoveReason
import org.maplibre.compose.camera.CameraUpdate
import org.maplibre.compose.location.LocationMeasurement
import org.maplibre.compose.location.LocationState
import org.maplibre.compose.map.MapState
import org.maplibre.compose.util.DpPadding
import org.maplibre.spatialk.geojson.Position
import org.maplibre.spatialk.units.extensions.inMeters
import to.pubky.maps.config.AttributionLink
import to.pubky.maps.links.GeoPoint
import to.pubky.maps.routing.Directions
import to.pubky.maps.routing.DirectionsController
import to.pubky.maps.routing.GuidanceUi
import to.pubky.maps.routing.KeepScreenOn
import to.pubky.maps.ui.SystemBack
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds

/** How often the arrival time is worked out again when no new position came. */
private const val ARRIVAL_TICK_MS = 15_000L

/** The zoom the map follows the user at while a route guides them: a few streets ahead, as Google Maps' navigation. */
internal const val GUIDANCE_ZOOM = 17.0

/**
 * Where the user is put on the map while it follows them, as a share of its height kept above them:
 * low on the screen, so most of the map is the way ahead.
 */
private const val GUIDANCE_ROOM_AHEAD = 0.4f

/**
 * A route guiding the user ([Directions.guiding]) on the live map: each position goes to
 * [directions] ([DirectionsController.guide]); the map follows the user at [GUIDANCE_ZOOM], turned to
 * the way the route goes ahead of them and with them low on the screen, until they move it
 * (Re-centre follows again); the screen stays on; Back ends the guidance, which turns the map north
 * and takes the room ahead off again. Null while no route guides the user.
 */
@Composable
internal fun rememberGuidance(
    directions: DirectionsController,
    open: Directions?,
    placeName: String,
    locationState: LocationState,
    mapState: MapState,
    credits: List<AttributionLink>,
): GuidanceUi? {
    val guiding = open?.guiding == true
    var following by remember { mutableStateOf(true) }
    var guided by remember { mutableStateOf(false) }
    LaunchedEffect(guiding) {
        if (guiding) {
            following = true
            guided = true
        } else if (guided) {
            guided = false
            // The room ahead at once: an animation cut short (a gesture, a place a link opened) would
            // leave some of it, and it would move every later fit and centring off the map's middle.
            mapState.setCameraPosition(mapState.cameraPosition.copy(padding = DpPadding()))
            mapState.animateCamera(CameraUpdate(bearing = 0.0))
        }
    }
    if (guiding) {
        KeepScreenOn()
        SystemBack { directions.endGuidance() }
    }
    LaunchedEffect(directions, guiding) {
        if (!guiding) return@LaunchedEffect
        snapshotFlow { locationState.lastLocation }
            .filterNotNull()
            .collect { directions.guide(it.point, it.accuracyMeters) }
    }
    // The first value is the reason of the move that is already over.
    LaunchedEffect(guiding) {
        if (!guiding) return@LaunchedEffect
        snapshotFlow { mapState.cameraMoveReason }.drop(1).collect { if (it == CameraMoveReason.Gesture) following = false }
    }
    LaunchedEffect(directions, guiding, following) {
        if (!guiding || !following) return@LaunchedEffect
        directions.directions
            .map { d -> d?.position?.let { it to (d.progress?.ahead ?: mapState.cameraPosition.bearing) } }
            .filterNotNull()
            .distinctUntilChanged()
            .collectLatest { (at, ahead) ->
                val height = mapState.viewport?.size?.height
                mapState.animateCamera(
                    CameraUpdate(
                        center = Position(longitude = at.lon, latitude = at.lat),
                        zoom = GUIDANCE_ZOOM,
                        bearing = ahead,
                        padding =
                            DpPadding(
                                top =
                                    height?.times(GUIDANCE_ROOM_AHEAD) ?: mapState.cameraPosition.padding.calculateTopPadding(),
                            ),
                    ),
                )
            }
    }
    // Also while the user stands still (at a light): the arrival moves on with the clock.
    val now by produceState(Clock.System.now(), guiding) {
        while (guiding) {
            delay(ARRIVAL_TICK_MS)
            value = Clock.System.now()
        }
    }
    if (open == null || !open.guiding) return null
    val secondsLeft = open.progress?.secondsLeft
    val arrival = secondsLeft?.let { arrivalText(now.plus(it.seconds), TimeZone.currentSystemDefault()) }
    return GuidanceUi(
        directions = open,
        placeName = placeName,
        arrival = arrival,
        following = following,
        onRecenter = { following = true },
        onRouteAgain = { locationState.lastLocation?.let { directions.routeAgainFrom(it.point) } },
        onEnd = directions::endGuidance,
        credits = credits,
    )
}

/** "14:32": the clock time at [at] in [zone], as the app's other times read. */
internal fun arrivalText(
    at: kotlin.time.Instant,
    zone: TimeZone,
): String {
    val time = at.toLocalDateTime(zone).time
    return "${time.hour.toString().padStart(2, '0')}:${time.minute.toString().padStart(2, '0')}"
}

private val LocationMeasurement.point: GeoPoint get() = GeoPoint(position.latitude, position.longitude)

private val LocationMeasurement.accuracyMeters: Double? get() = horizontalAccuracy?.inMeters
