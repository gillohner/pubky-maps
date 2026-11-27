package to.pubky.maps.map

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.maplibre.compose.location.LocationPermission
import org.maplibre.compose.location.LocationState
import org.maplibre.compose.location.LocationTrackingStatus
import org.maplibre.compose.util.DpPadding
import to.pubky.maps.config.AttributionLink
import to.pubky.maps.config.ServerDefaults
import to.pubky.maps.config.ServerLists
import to.pubky.maps.config.Service
import to.pubky.maps.links.GeoPoint
import to.pubky.maps.place.PlacePageState
import to.pubky.maps.place.RouteStartChoice
import to.pubky.maps.place.title
import to.pubky.maps.routing.DirectionsController
import to.pubky.maps.routing.RouteStart
import to.pubky.maps.routing.RouteStatus

/**
 * What Directions on a place page does: directions to the place from the user's position (from the
 * place back to it when reversed), which it asks for as the my-location button does (the permission
 * only now, location turned back on), and hands to [directions] once it is known. Without one the directions say why.
 * Directions from a chosen start ([DirectionsController.startFor]) need no position: nothing is asked.
 */
@Composable
internal fun rememberStartDirections(
    directions: DirectionsController,
    locationState: LocationState,
): (String, GeoPoint, Boolean) -> Unit {
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
        start@{ uri, to, reversed ->
            val fix = locationState.lastLocation?.position?.let { GeoPoint(it.latitude, it.longitude) }
            val step =
                directionsLocationStep(
                    fromChosenStart = directions.startFor(uri, to) != null,
                    permission = locationState.permission,
                    trackingUnavailable = locationState.status is LocationTrackingStatus.Unavailable,
                    hasFix = fix != null,
                )
            if (step == null) {
                directions.start(uri, to, fix, reversed)
                return@start
            }
            when (step) {
                MyLocationStep.MoveToLastFix -> {
                    directions.start(uri, to, fix, reversed)
                }

                MyLocationStep.CenterOnNextFix -> {
                    directions.start(uri, to, null, reversed)
                }

                MyLocationStep.RetryThenCenter -> {
                    directions.start(uri, to, null, reversed)
                    scope.retryLocationFor(
                        directions = directions,
                        retry = locationState::retry,
                        unavailable = snapshotFlow { locationState.status is LocationTrackingStatus.Unavailable },
                    )
                }

                MyLocationStep.ExplainPermissionOff -> {
                    directions.start(uri, to, null, reversed)
                    directions.locationMissing(permissionOff = true)
                }

                MyLocationStep.RequestPermission -> {
                    directions.start(uri, to, null, reversed)
                    locationState.requestPermission()
                }
            }
        }
    }
}

/**
 * What Directions do about the user's position: nothing from a chosen start (null: no permission
 * asked, no position awaited), else what the my-location button does ([myLocationStep]).
 */
internal fun directionsLocationStep(
    fromChosenStart: Boolean,
    permission: LocationPermission,
    trackingUnavailable: Boolean,
    hasFix: Boolean,
): MyLocationStep? = if (fromChosenStart) null else myLocationStep(permission, trackingUnavailable, hasFix)

/**
 * "Start here" on the page of [page]'s place, for [directions]: chosen while [chosen] is the place,
 * choosing it under the page's title. None without routing or a point to start from, as Directions.
 */
internal fun routeStartChoice(
    directions: DirectionsController?,
    chosen: RouteStart?,
    page: PlacePageState,
): RouteStartChoice? {
    if (directions == null) return null
    val point = page.place.point ?: page.card?.point ?: return null
    val isStart = chosen?.isPlace(page.place.uri, point) == true
    return RouteStartChoice(
        chosen = isStart,
        onToggle = {
            if (isStart) directions.clearStart() else directions.chooseStart(RouteStart(page.place.uri, point, page.title))
        },
    )
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

/** Room around a fitted route, past the map's edges of [room]; less in a room under 4 x as tall or wide. */
private val ROUTE_MARGIN = 32.dp

/** The least map, across and down, that a route is fitted into: less is no map in view. */
private val ROUTE_MIN_ROOM = 24.dp

/** The least map, across and down, a phone sheet at its top stop leaves for a route; less is the page covering the map. */
private val ROUTE_ROOM_AT_TOP = ROUTE_MARGIN * 2 + 96.dp

/** The map a place page leaves in view ([room]), and whether the phone sheet is at its top stop ([sheetAtTop]). */
internal data class PageRoom(
    val room: DpRect,
    val sheetAtTop: Boolean,
) {
    /**
     * Whether a found route is fitted into [room] now. Not while the page covers the map: the sheet at
     * its top leaves no map, with the route's steps open, or a strip ([ROUTE_ROOM_AT_TOP]) when the page
     * is short; nor while there is about no map in view ([ROUTE_MIN_ROOM]), as while the sheet is
     * dragged up to its top. A lower stop's room is taken however small: at the largest font, or on a
     * short phone, the lowest stop leaves about 120 dp of map or less, and the route is fitted there
     * between smaller margins ([routeFitPadding]).
     */
    val holdsRoute: Boolean
        get() {
            val least = if (sheetAtTop) ROUTE_ROOM_AT_TOP else ROUTE_MIN_ROOM
            return room.right - room.left >= least && room.bottom - room.top >= least
        }
}

/**
 * The room a found route is fitted into, from [rooms] (the map its place page leaves in view): the
 * first room that holds the route ([PageRoom.holdsRoute]) and stays the same for [settleMs]. The room
 * is reported on every frame of the sheet's move, so a route found while the page covers the map
 * waits for where the sheet rests with the map in view. Null when the page reports no room within
 * [OPEN_ROOM_WAIT_MS]: a page laid out anew (a rotation, a return from Settings) reports its room in
 * its first frames, and the route is not fitted behind it meanwhile.
 */
internal suspend fun routeRoom(
    rooms: Flow<PageRoom?>,
    settleMs: Long = ROOM_SETTLE_MS,
): DpRect? {
    withTimeoutOrNull(OPEN_ROOM_WAIT_MS) { rooms.filterNotNull().first() } ?: return null
    return settled(rooms, settleMs).first { it.holdsRoute }.room
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
    // The room's part on the map, and a margin inside it that leaves at least half of it to the route.
    val left = room.left.coerceAtLeast(0.dp)
    val top = room.top.coerceAtLeast(0.dp)
    val right = (size.width - room.right).coerceAtLeast(0.dp)
    val bottom = (size.height - room.bottom).coerceAtLeast(0.dp)
    val across = (size.width - left - right).coerceAtLeast(0.dp)
    val down = (size.height - top - bottom).coerceAtLeast(0.dp)
    val marginAcross = ROUTE_MARGIN.coerceAtMost(across / 4)
    val marginDown = ROUTE_MARGIN.coerceAtMost(down / 4)
    return DpPadding(
        left = left + marginAcross,
        top = top + marginDown,
        right = right + marginAcross,
        bottom = bottom + marginDown,
    )
}

/**
 * Restarts location for the directions open now (just started, waiting for the user's position) and
 * says their position is missing when location is still unavailable after [LocationRetryGrace]
 * ([retryRecovers]). Only for those directions, also once the user changed their mode or swapped
 * them: closed (and maybe opened again) meanwhile, they are others.
 */
internal fun CoroutineScope.retryLocationFor(
    directions: DirectionsController,
    retry: () -> Unit,
    unavailable: Flow<Boolean>,
) {
    // Read now: the retry runs later, after a tap that may have opened other directions.
    val opening = directions.directions.value?.opening
    launch {
        if (retryRecovers(retry, unavailable)) return@launch
        if (opening != null && directions.directions.value?.opening == opening) directions.locationMissing(permissionOff = false)
    }
}
