package to.pubky.maps.map

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.maplibre.compose.location.LocationPermission
import org.maplibre.compose.location.LocationState
import org.maplibre.compose.location.LocationTrackingStatus
import org.maplibre.compose.util.DpPadding
import to.pubky.maps.config.AttributionLink
import to.pubky.maps.config.ServerDefaults
import to.pubky.maps.config.ServerLists
import to.pubky.maps.config.Service
import to.pubky.maps.links.GeoPoint
import to.pubky.maps.routing.DirectionsController
import to.pubky.maps.routing.RouteStatus

/**
 * What Directions on a place page does: directions to the place from the user's position, which it
 * asks for as the my-location button does (the permission only now, location turned back on), and
 * hands to [directions] once it is known. Without one the directions say why.
 */
@Composable
internal fun rememberStartDirections(
    directions: DirectionsController,
    locationState: LocationState,
): (String, GeoPoint) -> Unit {
    val scope = rememberCoroutineScope()

    fun waiting() = directions.directions.value?.status == RouteStatus.Locating
    LaunchedEffect(directions, locationState) {
        snapshotFlow { locationState.lastLocation?.position }
            .filterNotNull()
            .collect { directions.located(GeoPoint(it.latitude, it.longitude)) }
    }
    // Only changes count: the permission is not granted yet while its dialog shows (also when the
    // activity is recreated under the dialog). A dialog dismissed without an answer changes nothing:
    // the controller's timeout ends the wait then.
    LaunchedEffect(directions, locationState) {
        snapshotFlow { locationState.permission }
            .drop(1)
            .collect { permission ->
                if (permission is LocationPermission.NotGranted && waiting()) {
                    directions.locationMissing(permissionOff = permission.canRequest == false)
                }
            }
    }
    LaunchedEffect(directions, locationState) {
        snapshotFlow { locationState.status is LocationTrackingStatus.Unavailable }
            .drop(1)
            .collect { unavailable -> if (unavailable && waiting()) directions.locationMissing(permissionOff = false) }
    }
    return remember(directions, locationState) {
        { uri, to ->
            val fix = locationState.lastLocation?.position?.let { GeoPoint(it.latitude, it.longitude) }
            val step =
                myLocationStep(
                    permission = locationState.permission,
                    trackingUnavailable = locationState.status is LocationTrackingStatus.Unavailable,
                    hasFix = fix != null,
                )
            when (step) {
                MyLocationStep.MoveToLastFix -> {
                    directions.start(uri, to, fix)
                }

                MyLocationStep.CenterOnNextFix -> {
                    directions.start(uri, to, null)
                }

                MyLocationStep.RetryThenCenter -> {
                    directions.start(uri, to, null)
                    val started = directions.directions.value
                    scope.launch {
                        val recovered =
                            retryRecovers(
                                retry = locationState::retry,
                                unavailable = snapshotFlow { locationState.status is LocationTrackingStatus.Unavailable },
                            )
                        // Only for these directions: closed and opened again meanwhile, they are others.
                        if (!recovered && directions.directions.value === started) directions.locationMissing(permissionOff = false)
                    }
                }

                MyLocationStep.ExplainPermissionOff -> {
                    directions.start(uri, to, null)
                    directions.locationMissing(permissionOff = true)
                }

                MyLocationStep.RequestPermission -> {
                    directions.start(uri, to, null)
                    locationState.requestPermission()
                }
            }
        }
    }
}

/**
 * The credits under a route: those of the server in the list that found it (the first one's while
 * none has), and the link to report a wrong road in OpenStreetMap that FOSSGIS' terms ask for.
 */
fun routeCredits(
    servers: ServerLists,
    server: String?,
): List<AttributionLink> {
    val list = servers[Service.Valhalla]
    val entry = list.firstOrNull { it.url == server } ?: list.first()
    val credits = entry.attribution.ifEmpty { listOf(ServerDefaults.openStreetMap) }
    return credits + ServerDefaults.fixTheMap
}

/** Room around a fitted route, past the map's edges of [room]. */
private val ROUTE_MARGIN = 32.dp

/** The least map, past [ROUTE_MARGIN] on each side, that a route is fitted into. */
private val ROUTE_MIN_ROOM = 96.dp

/**
 * Whether [room] leaves enough map to fit a route into: not when the place page covers about all of
 * it (the phone sheet at its top, as with the route's steps open), where the route would be framed in
 * a strip of no height.
 */
internal fun roomHoldsRoute(room: DpRect): Boolean =
    room.right - room.left >= ROUTE_MARGIN * 2 + ROUTE_MIN_ROOM && room.bottom - room.top >= ROUTE_MARGIN * 2 + ROUTE_MIN_ROOM

/**
 * The room a found route is fitted into, from [rooms] (the map its place page leaves in view): null at
 * once when the page has reported none yet, as before its first layout; else the first room that
 * holds the route ([roomHoldsRoute]) and stays the same for [settleMs]. The room is reported on every
 * frame of the sheet's move, so a route found with the sheet at its top waits for where the sheet
 * stops, not for the first frame of its way down that is tall enough.
 */
internal suspend fun routeRoom(
    rooms: Flow<DpRect?>,
    settleMs: Long = ROOM_SETTLE_MS,
): DpRect? {
    if (rooms.first() == null) return null
    return settled(rooms, settleMs).first { roomHoldsRoute(it) }
}

/**
 * The padding that fits a route into [room], the map a place page leaves in view on a map of [size]:
 * a margin inside it. Without a room yet, the top and the bottom half are left as for search results.
 */
internal fun routeFitPadding(
    room: DpRect?,
    size: DpSize?,
): DpPadding {
    if (room == null || size == null) {
        return DpPadding(left = ROUTE_MARGIN, top = 136.dp, right = ROUTE_MARGIN, bottom = (size?.height ?: 800.dp) / 2)
    }
    return DpPadding(
        left = room.left.coerceAtLeast(0.dp) + ROUTE_MARGIN,
        top = room.top.coerceAtLeast(0.dp) + ROUTE_MARGIN,
        right = (size.width - room.right).coerceAtLeast(0.dp) + ROUTE_MARGIN,
        bottom = (size.height - room.bottom).coerceAtLeast(0.dp) + ROUTE_MARGIN,
    )
}
