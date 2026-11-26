package to.pubky.maps.map

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.launch
import to.pubky.maps.links.GeoPoint
import kotlin.math.abs

/**
 * How close to the edge of the uncovered map a place still counts as in view: its pin (a 24 dp dot)
 * and a finger's width around it, so it is not left under the edge of the sheet or the credits.
 */
internal val KeepInViewMargin = 32.dp

/** How long the uncovered map must keep its size before a place is moved into it (the sheet has opened). */
internal const val ROOM_SETTLE_MS = 250L

/**
 * How far the camera's centre moves (in dp on the screen) so that a place at [point] on the screen
 * shows in the middle of [room], the map that the place's sheet or panel, the search bar and the
 * controls leave in view; null when the place is already in [room], at least [margin] from its
 * edges, or [room] is too small to show it (the sheet takes the whole screen).
 */
internal fun panIntoView(
    point: DpOffset,
    room: DpRect,
    margin: Dp = KeepInViewMargin,
): DpOffset? {
    if (room.right - room.left < margin * 2 || room.bottom - room.top < margin * 2) return null
    val inView =
        point.x >= room.left + margin &&
            point.x <= room.right - margin &&
            point.y >= room.top + margin &&
            point.y <= room.bottom - margin
    if (inView) return null
    return DpOffset(point.x - (room.left + room.right) / 2, point.y - (room.top + room.bottom) / 2)
}

/**
 * Where on the screen the camera's centre goes so that a tapped place at [point] shows in [room] of
 * a map of [size]: null when it is in view already or its point on the screen is unknown.
 */
internal fun keepInViewCentre(
    point: DpOffset?,
    room: DpRect,
    size: DpSize,
): DpOffset? {
    val shift = point?.let { panIntoView(it, room) } ?: return null
    return DpOffset(size.width / 2 + shift.x, size.height / 2 + shift.y)
}

/**
 * What a tapped place was kept in view for: the place and the map's size. The same again (the sheet
 * dragged, a return from Settings) moves nothing; a rotation or a fold changes the size, and a place
 * the new layout hides is moved again.
 */
internal fun keptInViewKey(
    uri: String,
    size: DpSize,
): String = "$uri@${size.width.value}x${size.height.value}"

/**
 * The values of [rooms] that stay the same for [settleMs], skipping nulls: while the sheet
 * animates to its height or takes its header's measure, the room changes every frame.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun <T : Any> settled(
    rooms: Flow<T?>,
    settleMs: Long = ROOM_SETTLE_MS,
): Flow<T> =
    rooms.transformLatest { room ->
        if (room == null) return@transformLatest
        delay(settleMs)
        emit(room)
    }

/**
 * Whether [other] is the same view: a camera restored after Settings or a recreated activity comes
 * back from the native map through a projection and a logarithm, a few last bits off.
 */
internal fun MapView.sameAs(other: MapView): Boolean =
    abs(lat - other.lat) < 1e-7 &&
        abs(lon - other.lon) < 1e-7 &&
        abs(zoom - other.zoom) < 1e-6 &&
        abs(bearing - other.bearing) < 1e-6 &&
        abs(pitch - other.pitch) < 1e-6

/** The map a tapped place is kept in view on: MapLibre's camera in [MapRoute], a fake in tests. */
internal interface KeepInViewMap {
    /** The map's size on the screen; null before it is laid out. */
    val size: DpSize?

    /** Whether the camera is moving (our own pan, a fling): the room is judged once it rests. */
    val moving: Flow<Boolean>

    /**
     * Where the camera is: a change that [keepTappedInView] did not make is the user's (My location, a
     * cluster's zoom). Not the place's point on the screen, which a rotation or a fold moves too.
     */
    val camera: MapView

    /** Where [point] is on the screen; null when it cannot be projected. */
    fun screenPoint(point: GeoPoint): DpOffset?

    /** Moves the camera so that what is now at [centre] on the screen is in the middle of the map. */
    suspend fun centreOn(centre: DpOffset)
}

/**
 * Keeps the place [uri] tapped at [point] in view while its sheet or panel opens: each time the map
 * left in view ([rooms], see [settled]) has settled with the camera at rest, a place it hides moves
 * to its middle, once per place and map size ([kept] and [onKept], see [keptInViewKey]). Nothing
 * moves once [userMoved] the map or the sheet (a drag, also one held still, a tap on its handle) or
 * the camera rests somewhere this did not move it (a tap on My location or on a cluster): where the
 * user put them is theirs, as in Google Maps. The tap counts as kept then, also after a return from
 * Settings.
 *
 * The sheet's lowest height is its header, which grows when the place's card or a part's failure
 * arrives, mostly after the first room has settled. So a room that shows the place counts as final
 * only once [pageDone] (every part of the page answered or failed): until then a later, smaller room
 * is checked again. Runs until cancelled (the place closes or another is tapped).
 */
internal suspend fun keepTappedInView(
    uri: String,
    point: GeoPoint,
    rooms: Flow<DpRect?>,
    pageDone: Flow<Boolean>,
    userMoved: Flow<Boolean>,
    map: KeepInViewMap,
    kept: () -> String?,
    onKept: (String) -> Unit,
): Unit =
    coroutineScope {
        var moved = false

        fun userMovedIt() {
            moved = true
            map.size?.let { onKept(keptInViewKey(uri, it)) }
        }
        launch {
            userMoved.first { it }
            userMovedIt()
        }
        // Where the camera rests as this left it: at the tap (taken at the first rest when it was
        // still moving then), then after each of our own pans.
        var ours: MapView? = if (map.moving.first()) null else map.camera
        val resting =
            combine(rooms, pageDone, map.moving) { room, done, moving -> room?.takeUnless { moving }?.let { it to done } }
                .distinctUntilChanged()
        settled(resting).collect { (room, done) ->
            val rested = ours ?: map.camera.also { ours = it }
            if (!moved && !map.camera.sameAs(rested)) userMovedIt()
            val size = map.size ?: return@collect
            val key = keptInViewKey(uri, size)
            if (moved || kept() == key) return@collect
            val centre = keepInViewCentre(map.screenPoint(point), room, size)
            if (centre != null) {
                onKept(key)
                map.centreOn(centre)
                map.moving.first { !it }
                ours = map.camera
            } else if (done) {
                onKept(key)
            }
        }
    }
