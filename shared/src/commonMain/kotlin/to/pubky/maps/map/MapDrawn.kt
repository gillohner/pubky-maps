package to.pubky.maps.map

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.onStart
import org.maplibre.compose.map.MapEvent

/**
 * Waits until the map has drawn its first whole view: MapLibre is idle (every tile, label and image
 * request of the view settled and drawn, nothing more to draw) while [styleReady] says a style from
 * the server list is in place. Idle, not the first rendered frame, because a frame drawn before its
 * tiles arrived shows an empty map. The two can come in either order: the style turns ready after
 * the engine parsed it, and an idle map stays idle until something changes. Returns true once drawn,
 * false when both flows end first. The map's event stream never ends: a map that leaves the screen
 * before it drew cancels the caller's wait instead, so the app only ever sees true.
 *
 * On Android this moment is the start's "fully drawn" time (`Activity.reportFullyDrawn`), which the
 * emulator smoke test holds to a budget (`scripts/ci/start_time.py`).
 */
internal suspend fun awaitMapDrawn(
    events: Flow<MapEvent>,
    styleReady: Flow<Boolean>,
): Boolean {
    val idle =
        events
            .mapNotNull { event ->
                when (event) {
                    MapEvent.Idle -> true

                    // Work for the engine: it is idle again once that is drawn.
                    MapEvent.StyleLoaded, MapEvent.CameraMoved, is MapEvent.CameraMoveStarted, is MapEvent.FrameRendered -> false

                    else -> null
                }
            }.onStart { emit(false) }
    return combine(idle, styleReady) { isIdle, ready -> isIdle && ready }.firstOrNull { it } != null
}
