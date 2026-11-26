package to.pubky.maps.map

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.transformLatest

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
 * a map of [size]: null when it is in view already, when its point on the screen is unknown, or when
 * [userMoved] the map since the place opened (where the user put the map is theirs).
 */
internal fun keepInViewCentre(
    point: DpOffset?,
    room: DpRect,
    size: DpSize,
    userMoved: Boolean,
): DpOffset? {
    if (userMoved || point == null) return null
    val shift = panIntoView(point, room) ?: return null
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
 * The values of [rooms] that stay the same for [ROOM_SETTLE_MS], skipping nulls: while the sheet
 * animates to its height or takes its header's measure, the room changes every frame.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun <T : Any> settled(rooms: Flow<T?>): Flow<T> =
    rooms.transformLatest { room ->
        if (room == null) return@transformLatest
        delay(ROOM_SETTLE_MS)
        emit(room)
    }
