package to.pubky.maps.routing

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import to.pubky.maps.links.GeoPoint
import to.pubky.maps.status.ServiceHealth
import to.pubky.maps.status.ServicePart
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Where a route to a place stands. */
sealed interface RouteStatus {
    /** Waiting for the user's position (the permission dialog, the first fix). */
    data object Locating : RouteStatus

    /** The user's position cannot be had; [permissionOff] when only the system settings can change that. */
    data class NoLocation(
        val permissionOff: Boolean,
    ) : RouteStatus

    /** Asking the routing servers. */
    data object Finding : RouteStatus

    /** [server] found [route]. */
    data class Found(
        val route: Route,
        val server: String,
    ) : RouteStatus

    data class NoRoute(
        val reason: NoRouteReason,
    ) : RouteStatus

    /** No routing server answered. */
    data object Unreachable : RouteStatus
}

/** Directions to the place [placeUri] at [to], by [mode], from the user's position [from] once known. */
data class Directions(
    val placeUri: String,
    val to: GeoPoint,
    val mode: TravelMode,
    val from: GeoPoint?,
    val status: RouteStatus,
)

/**
 * Directions from the user's position to an open place, for the life of the process: one route at a
 * time, asked again when the mode changes (a newer request replaces one still running), the last mode
 * kept for the next place. Every routing server failing names routing in [health] until one answers.
 * A position that has not come within [locatingTimeout] (a permission dialog dismissed, no fix
 * indoors) is missing; one that comes later still starts the route.
 */
class DirectionsController(
    private val routing: Routing,
    private val scope: CoroutineScope,
    private val health: ServiceHealth? = null,
    private val locatingTimeout: Duration = 30.seconds,
) {
    private val state = MutableStateFlow<Directions?>(null)
    val directions: StateFlow<Directions?> = state.asStateFlow()

    /** The mode of the next directions: the one the user picked last, by car at first. */
    private var lastMode = TravelMode.Car
    private var request: Job? = null
    private var locating: Job? = null

    /**
     * Opens directions to [placeUri] at [to]: from [from] at once when the position is known, else
     * [RouteStatus.Locating] until [located] or [locationMissing].
     */
    fun start(
        placeUri: String,
        to: GeoPoint,
        from: GeoPoint?,
    ) {
        val directions = Directions(placeUri, to, lastMode, from, RouteStatus.Locating)
        if (from != null) {
            ask(directions)
            return
        }
        set(directions)
        locating?.cancel()
        locating =
            scope.launch {
                delay(locatingTimeout)
                locationMissing(permissionOff = false)
            }
    }

    /**
     * The user's position arrived: it starts directions that wait for it, also once they said it was
     * missing (location access turned on in Settings, a first fix after a long wait).
     */
    fun located(from: GeoPoint) {
        val current = state.value ?: return
        if (current.from != null || (current.status != RouteStatus.Locating && current.status !is RouteStatus.NoLocation)) return
        ask(current.copy(from = from))
    }

    /** The user's position cannot be had for the directions waiting for it. */
    fun locationMissing(permissionOff: Boolean) {
        val current = state.value ?: return
        if (current.status != RouteStatus.Locating) return
        locating?.cancel()
        locating = null
        set(current.copy(status = RouteStatus.NoLocation(permissionOff)))
    }

    /** Asks for the route by [mode] from the same start. */
    fun selectMode(mode: TravelMode) {
        lastMode = mode
        val current = state.value ?: return
        if (current.mode == mode) return
        val changed = current.copy(mode = mode)
        if (changed.from != null) ask(changed) else set(changed)
    }

    /** Asks again after a failure, from the same start. */
    fun retry() {
        val current = state.value ?: return
        if (current.from != null) ask(current)
    }

    /** Closes the directions (the place closed, or the user closed them). */
    fun close() {
        request?.cancel()
        request = null
        locating?.cancel()
        locating = null
        state.value = null
    }

    private fun set(directions: Directions) {
        request?.cancel()
        request = null
        state.value = directions
    }

    private fun ask(directions: Directions) {
        val from = directions.from ?: return
        request?.cancel()
        locating?.cancel()
        locating = null
        val asked = directions.copy(status = RouteStatus.Finding)
        state.value = asked
        request =
            scope.launch {
                val status =
                    when (val answer = routing.route(from, asked.to, asked.mode)) {
                        is RouteAnswer.Found -> RouteStatus.Found(answer.route, answer.server)
                        is RouteAnswer.NoRoute -> RouteStatus.NoRoute(answer.reason)
                        RouteAnswer.Unreachable -> RouteStatus.Unreachable
                    }
                if (status == RouteStatus.Unreachable) health?.failed(ServicePart.Routing) else health?.recovered(ServicePart.Routing)
                // A newer request or a close replaced this one meanwhile (a cancel that came too late).
                if (state.value === asked) state.value = asked.copy(status = status)
            }
    }
}
