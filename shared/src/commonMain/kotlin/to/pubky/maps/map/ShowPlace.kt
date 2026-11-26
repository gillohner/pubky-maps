package to.pubky.maps.map

import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.DpSize
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import to.pubky.maps.links.GeoPoint
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.tan

/**
 * How long the map left in view must keep its size before a picked or linked place moves into it.
 * Shorter than [ROOM_SETTLE_MS]: the map waits for it before it moves at all. A new page opens at
 * its stop in its first frame; a page already open at another height animates there, a new room
 * every frame.
 */
internal const val OPEN_ROOM_SETTLE_MS = 100L

/** How long a picked or linked place waits for its page's room; then it goes to the whole map's middle. */
internal const val OPEN_ROOM_WAIT_MS = 600L

/** The width of the world in dp at zoom 0: MapLibre draws Web Mercator tiles of 512 dp. */
private const val WORLD_DP_AT_ZOOM_0 = 512.0

/** Web Mercator's northern and southern edge. */
private const val MAX_MERCATOR_LAT = 85.05112878

/**
 * The map that a picked or linked place's page leaves in view once the page has opened ([rooms],
 * see [settled]); null when none comes within [OPEN_ROOM_WAIT_MS] (no page shows for it). The room
 * of a page that is still loading: at half a loading page is shorter than the page it becomes (its
 * stop is no taller than its content), so the place lands lower in the room the loaded page leaves,
 * in view (on a 412x915 dp phone ~80 dp above the controls) but not in its middle. Waiting for the
 * page would hold the move for as long as the network takes.
 */
internal suspend fun openedRoom(rooms: Flow<DpRect?>): DpRect? =
    withTimeoutOrNull(OPEN_ROOM_WAIT_MS) { settled(rooms, OPEN_ROOM_SETTLE_MS).first() }

/**
 * Where the camera's centre goes, at [zoom] and turned by [bearing] degrees, so that [point] shows
 * in the middle of [room] on a map of [size]: the place a search picked or a link opened lands in
 * the map its sheet or panel leaves in view, as in Google Maps, not on the controls riding on the
 * sheet. [point] itself when [room] or [size] is unknown or [room] is too small to show it (the page
 * takes the whole screen). A tilted map is taken as flat, so there the place lands near the middle.
 */
internal fun centreShowing(
    point: GeoPoint,
    room: DpRect?,
    size: DpSize?,
    zoom: Double,
    bearing: Double,
): GeoPoint {
    if (room == null || size == null) return point
    if (room.right - room.left < KeepInViewMargin * 2 || room.bottom - room.top < KeepInViewMargin * 2) return point
    // Where the place goes, from the map's middle, in dp on the screen (y down).
    val dx = ((room.left + room.right) / 2 - size.width / 2).value.toDouble()
    val dy = ((room.top + room.bottom) / 2 - size.height / 2).value.toDouble()
    // The same on the world: the screen turned back by the bearing (the screen's top faces it).
    val turn = bearing * PI / 180
    val wx = dx * cos(turn) - dy * sin(turn)
    val wy = dx * sin(turn) + dy * cos(turn)
    val world = WORLD_DP_AT_ZOOM_0 * 2.0.pow(zoom)
    val x = (point.lon + 180) / 360 * world - wx
    val lat = point.lat.coerceIn(-MAX_MERCATOR_LAT, MAX_MERCATOR_LAT) * PI / 180
    val y = (0.5 - ln(tan(PI / 4 + lat / 2)) / (2 * PI)) * world - wy
    val lon = (x / world * 360 - 180).let { ((it + 180).mod(360.0)) - 180 }
    val centreLat = (2 * atan(exp((0.5 - y / world) * 2 * PI)) - PI / 2) * 180 / PI
    return GeoPoint(centreLat.coerceIn(-MAX_MERCATOR_LAT, MAX_MERCATOR_LAT), lon)
}
