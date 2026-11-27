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

/**
 * Directions between the place [placeUri] at [place] and the user's [position] once known, by [mode]:
 * from the user to the place, or from the place back to the user when [reversed] (the user swapped
 * start and destination); [stepsShown] when the user opened the route's list of steps, [shownStep] the
 * step of the list the user tapped to see on the map.
 */
data class Directions(
    val placeUri: String,
    val place: GeoPoint,
    val mode: TravelMode,
    val position: GeoPoint?,
    val status: RouteStatus,
    /** The route starts at the place and ends at the user's position. */
    val reversed: Boolean = false,
    /**
     * Which opening of directions these are ([DirectionsController.start] counts them): the same for
     * the directions' other modes and way round, another once they are started again.
     */
    val opening: Int = 0,
    /** The map has fitted the found route ([DirectionsController.routeFitted]); false again for each new request. */
    val routeFitted: Boolean = false,
    val stepsShown: Boolean = false,
    /** The index in the found route's steps of the one the user tapped; null for none. */
    val shownStep: Int? = null,
    /** Counts the taps on steps ([DirectionsController.showStep]); the same step tapped again counts again. */
    val stepTaps: Int = 0,
    /** The tap the map has moved for ([DirectionsController.mapMovedToStep]). */
    val mapMovedForTap: Int = 0,
) {
    /** The found route the map still has to fit, once per route. */
    val routeToFit: RouteStatus.Found? get() = (status as? RouteStatus.Found)?.takeIf { !routeFitted }

    /** The steps the place page lists: the found route's, while the user has them open. */
    val shownSteps: List<RouteStep> get() = if (stepsShown) (status as? RouteStatus.Found)?.route?.steps.orEmpty() else emptyList()

    /** The step the map marks: the one the user tapped, while the list is open. */
    val markedStep: RouteStep? get() = shownStep?.let { shownSteps.getOrNull(it) }

    /**
     * The tap the map still has to move to [markedStep] for, once per tap: coming back from Settings or
     * a recreated activity keeps the user's pan, a tap on the same step again moves the map again.
     */
    val stepTapToMoveTo: Int? get() = stepTaps.takeIf { markedStep != null && it != mapMovedForTap }
}

/**
 * Directions from the user's position to an open place, or back from it once the user swaps them, for
 * the life of the process: one route at a time, asked again when the mode or the direction changes (a
 * newer request replaces one still running), the last mode kept for the next place, which starts from
 * the user again. The list of steps the user opened stays open for the routes by other modes or the
 * other way to the same place; a step tapped in it is marked on the map until the list closes or another route
 * comes. Every routing server failing names routing in [health] until one answers.
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

    /** Every tap on a step so far, for [Directions.stepTaps]; never goes back, so a tap is never taken for one before it. */
    private var stepTaps = 0

    /** Every [start] so far, for [Directions.opening]. */
    private var openings = 0

    /** The directions [request] asks the route for, as they were asked. */
    private var asking: Directions? = null
    private var locating: Job? = null

    /**
     * Opens directions to [placeUri] at [place]: from the user's [position] at once when it is known,
     * else [RouteStatus.Locating] until [located] or [locationMissing]. [reversed] routes from the
     * place back to the user: Try again for a missing position keeps the way round the user swapped
     * the directions to, the page's Directions starts them to the place.
     */
    fun start(
        placeUri: String,
        place: GeoPoint,
        position: GeoPoint?,
        reversed: Boolean = false,
    ) {
        openings += 1
        val directions = Directions(placeUri, place, lastMode, position, RouteStatus.Locating, reversed = reversed, opening = openings)
        if (position != null) {
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
    fun located(position: GeoPoint) {
        val current = state.value ?: return
        if (current.position != null || (current.status != RouteStatus.Locating && current.status !is RouteStatus.NoLocation)) return
        ask(current.copy(position = position))
    }

    /** The user's position cannot be had for the directions waiting for it. */
    fun locationMissing(permissionOff: Boolean) {
        val current = state.value ?: return
        if (current.status != RouteStatus.Locating) return
        locating?.cancel()
        locating = null
        set(current.copy(status = RouteStatus.NoLocation(permissionOff)))
    }

    /** Asks for the route by [mode] between the same points. */
    fun selectMode(mode: TravelMode) {
        lastMode = mode
        val current = state.value ?: return
        if (current.mode == mode) return
        val changed = current.copy(mode = mode)
        if (changed.position != null) ask(changed) else set(changed)
    }

    /**
     * Swaps the start and the destination: the route from the place back to the user's position, or
     * to the place again. Asked at once from a known position; one still awaited serves the swapped
     * directions when it comes. The open list of steps stays open for the swapped route.
     */
    fun swap() {
        val current = state.value ?: return
        val changed = current.copy(reversed = !current.reversed)
        if (changed.position != null) ask(changed) else set(changed)
    }

    /** Opens or closes the list of the route's steps; closing it takes the tapped step off the map. */
    fun showSteps(shown: Boolean) {
        val current = state.value ?: return
        if (current.stepsShown != shown) state.value = current.copy(stepsShown = shown, shownStep = current.shownStep.takeIf { shown })
    }

    /**
     * The user tapped the step at [index] of the open list: the map shows where it is. A route still
     * to be fitted (found with the sheet at its top) is not fitted after it, over the step.
     */
    fun showStep(index: Int) {
        val current = state.value ?: return
        if (index !in current.shownSteps.indices) return
        stepTaps += 1
        state.value = current.copy(shownStep = index, stepTaps = stepTaps, routeFitted = true)
    }

    /** The map has moved to the step of [tap] (or started to: a move cut short counts too). */
    fun mapMovedToStep(tap: Int) {
        val current = state.value ?: return
        if (tap > current.mapMovedForTap) state.value = current.copy(mapMovedForTap = tap)
    }

    /** Asks again after a failure, between the same points. */
    fun retry() {
        val current = state.value ?: return
        if (current.position != null) ask(current)
    }

    /**
     * The map has fitted [route] (or started to: a fit cut short counts too), so coming back to the
     * map does not fit it again over the user's pan. A route asked for since is still to be fitted.
     */
    fun routeFitted(route: Route) {
        val current = state.value ?: return
        if ((current.status as? RouteStatus.Found)?.route == route) state.value = current.copy(routeFitted = true)
    }

    /** Closes the directions (the place closed, or the user closed them). */
    fun close() {
        request?.cancel()
        request = null
        asking = null
        locating?.cancel()
        locating = null
        state.value = null
    }

    private fun set(directions: Directions) {
        request?.cancel()
        request = null
        asking = null
        state.value = directions
    }

    private fun ask(directions: Directions) {
        val position = directions.position ?: return
        request?.cancel()
        locating?.cancel()
        locating = null
        // A step tapped on the route before is not one of the next route's.
        val asked = directions.copy(status = RouteStatus.Finding, routeFitted = false, shownStep = null)
        state.value = asked
        asking = asked
        request =
            scope.launch {
                val answer =
                    if (asked.reversed) {
                        routing.route(asked.place, position, asked.mode)
                    } else {
                        routing.route(position, asked.place, asked.mode)
                    }
                val status =
                    when (answer) {
                        is RouteAnswer.Found -> RouteStatus.Found(answer.route, answer.server)
                        is RouteAnswer.NoRoute -> RouteStatus.NoRoute(answer.reason)
                        RouteAnswer.Unreachable -> RouteStatus.Unreachable
                    }
                if (status == RouteStatus.Unreachable) health?.failed(ServicePart.Routing) else health?.recovered(ServicePart.Routing)
                // A newer request or a close replaced this one meanwhile (a cancel that came too late);
                // the steps opened or closed while it ran are kept.
                val now = state.value
                if (asking === asked && now != null) state.value = now.copy(status = status)
            }
    }
}
