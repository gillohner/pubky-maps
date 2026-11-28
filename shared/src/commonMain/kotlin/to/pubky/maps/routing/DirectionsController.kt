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
import kotlin.math.abs
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
 * A place the user chose to start directions from ("Start here" on its page, as CoMaps' "Route from"
 * and OsmAnd's "Directions from"): the place [placeUri] at [point], named [name] in the route card.
 */
data class RouteStart(
    val placeUri: String,
    val point: GeoPoint,
    val name: String,
) {
    /**
     * Whether the place [uri] at [point] is this start: the same link, or the same point within
     * about 5 m (the same place reached through another link: its Pinakes card, an OSM URL).
     */
    fun isPlace(
        uri: String,
        point: GeoPoint?,
    ): Boolean =
        uri == placeUri ||
            (point != null && abs(point.lat - this.point.lat) < SAME_POINT && abs(point.lon - this.point.lon) < SAME_POINT)

    private companion object {
        /** About 5 m of latitude, less of longitude: two points of one place. */
        const val SAME_POINT = 0.00005
    }
}

/**
 * Directions between the place [placeUri] at [place] and the user's [position] once known, or the
 * place the user chose to start from ([from]), by [mode]: from the start to the place, or from the
 * place back to the start when [reversed] (the user swapped start and destination); [stepsShown] when the user opened the route's list of steps, [shownStep] the
 * step of the list the user tapped to see on the map.
 */
data class Directions(
    val placeUri: String,
    val place: GeoPoint,
    val mode: TravelMode,
    val position: GeoPoint?,
    val status: RouteStatus,
    /** The route starts at the place and ends at the user's position (at [from]). */
    val reversed: Boolean = false,
    /** The place the user chose to start from instead of their position; null for their position. */
    val from: RouteStart? = null,
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
    /** The route guides the user from their position ([DirectionsController.startGuidance]) until they end it. */
    val guiding: Boolean = false,
    /** Where the user is on the route while it guides them, from their last position; null before one is known on this route. */
    val progress: RouteProgress? = null,
) {
    /**
     * Whether the route can guide the user ([DirectionsController.startGuidance]): a found route
     * from their own position to the place, not one from a chosen start or back to them.
     */
    val canGuide: Boolean get() = status is RouteStatus.Found && from == null && !reversed && position != null

    /** The route's other end than the place: [from], else the user's position once known. */
    val origin: GeoPoint? get() = from?.point ?: position

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
 * the user again. A place the user chose to start from ([chooseStart]) serves every other place's
 * directions instead of the user's position until the user clears it: kept for the process, as the
 * mode, so the start of a day out (a hotel, a station) serves the places looked at from there. The list of steps the user opened stays open for the routes by other modes or the
 * other way to the same place; a step tapped in it is marked on the map until the list closes or another route
 * comes. A route from the user's position can guide them ([startGuidance]): their place on it from
 * each position ([guide]), a new route from where they are on request ([routeAgainFrom]) until they
 * end it. Every routing server failing names routing in [health] until one answers.
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

    private val chosen = MutableStateFlow<RouteStart?>(null)

    /** The place the user chose to start directions from; null for their position. */
    val chosenStart: StateFlow<RouteStart?> = chosen.asStateFlow()

    /** The mode of the next directions: the one the user picked last, by car at first. */
    private var lastMode = TravelMode.Car
    private var request: Job? = null

    /** Every tap on a step so far, for [Directions.stepTaps]; never goes back, so a tap is never taken for one before it. */
    private var stepTaps = 0

    /** The accuracy of the last position the guidance had, for the progress on a new route from there. */
    private var accuracy: Double? = null

    /** Every [start] so far, for [Directions.opening]. */
    private var openings = 0

    /** The directions [request] asks the route for, as they were asked. */
    private var asking: Directions? = null
    private var locating: Job? = null

    /**
     * The start the directions to [placeUri] at [place] take instead of the user's position: the
     * chosen one, but not for the place itself (from a hotel to the hotel is the way there from the user).
     */
    fun startFor(
        placeUri: String,
        place: GeoPoint,
    ): RouteStart? = chosen.value?.takeIf { !it.isPlace(placeUri, place) }

    /**
     * Directions to the other places start from [start] from now on. Open directions are not changed:
     * they are those of the page where the user chose it, to the start itself.
     */
    fun chooseStart(start: RouteStart) {
        chosen.value = start
    }

    /** Directions start from the user's position again. Open directions are started again by the caller. */
    fun clearStart() {
        chosen.value = null
    }

    /**
     * The user asked for the open directions from their position instead of their chosen start: the
     * start they came from is cleared, one chosen since on this page is kept (the caller starts the
     * directions again, from the user for this page's own start).
     */
    fun leaveStart() {
        val from = state.value?.from ?: return
        if (chosen.value == from) chosen.value = null
    }

    /**
     * The page of the chosen start [placeUri] is now called [name] (its card came after Start here
     * took the link's coordinates or text): later directions say the name.
     */
    fun renameStart(
        placeUri: String,
        name: String,
    ) {
        val start = chosen.value ?: return
        if (start.placeUri == placeUri && start.name != name) chosen.value = start.copy(name = name)
    }

    /**
     * Opens directions to [placeUri] at [place]: from the chosen start ([startFor]) or the user's known
     * [position] at once, else [RouteStatus.Locating] until [located] or [locationMissing]. [reversed]
     * routes from the place back to the start: Try again for a missing position keeps the way round
     * the user swapped the directions to, the page's Directions starts them to the place.
     */
    fun start(
        placeUri: String,
        place: GeoPoint,
        position: GeoPoint?,
        reversed: Boolean = false,
    ) {
        openings += 1
        val directions =
            Directions(
                placeUri,
                place,
                lastMode,
                position,
                RouteStatus.Locating,
                reversed = reversed,
                from = startFor(placeUri, place),
                opening = openings,
            )
        if (directions.origin != null) {
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
        if (current.origin != null || (current.status != RouteStatus.Locating && current.status !is RouteStatus.NoLocation)) return
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
        if (changed.origin != null) ask(changed) else set(changed)
    }

    /**
     * Swaps the start and the destination: the route from the place back to the user's position (to
     * the chosen start), or to the place again. Asked at once from a known position; one still awaited serves the swapped
     * directions when it comes. The open list of steps stays open for the swapped route.
     */
    fun swap() {
        val current = state.value ?: return
        val changed = current.copy(reversed = !current.reversed)
        if (changed.origin != null) ask(changed) else set(changed)
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

    /**
     * The route guides the user from here on: where they are on it ([Directions.progress]) from their
     * [position] now and each one [guide] gets, as Google Maps' Start. Only a route that [Directions.canGuide];
     * the list of steps closes, the map follows the user instead.
     */
    fun startGuidance(
        position: GeoPoint?,
        accuracyMeters: Double? = null,
    ) {
        val current = state.value ?: return
        if (!current.canGuide || current.guiding) return
        val started = current.copy(guiding = true, stepsShown = false, shownStep = null, routeFitted = true, progress = null)
        state.value = started
        if (position != null) guide(position, accuracyMeters)
    }

    /**
     * The user's [position] while the route guides them (within [accuracyMeters], when known): where
     * they are on it now, looked for from where they were ([routeProgress]). None while a new route
     * is asked for.
     */
    fun guide(
        position: GeoPoint,
        accuracyMeters: Double? = null,
    ) {
        val current = state.value ?: return
        val route = (current.status as? RouteStatus.Found)?.route ?: return
        if (!current.guiding) return
        accuracy = accuracyMeters
        val progress = routeProgress(route, position, current.progress?.onRouteAlong ?: 0.0, accuracyMeters)
        state.value = current.copy(progress = progress, position = position)
    }

    /**
     * The user, off the route, asked for a new one from their [position] to the same place: it keeps
     * guiding them once found, from where they are then.
     */
    fun routeAgainFrom(position: GeoPoint) {
        val current = state.value ?: return
        if (!current.guiding) return
        ask(current.copy(position = position))
    }

    /**
     * The route stops guiding the user: the place page shows the directions again, the route left
     * where the map is; a new route still asked for is fitted once found, as any other.
     */
    fun endGuidance() {
        val current = state.value ?: return
        if (!current.guiding) return
        val finding = current.status == RouteStatus.Finding
        state.value = current.copy(guiding = false, progress = null, routeFitted = current.routeFitted && !finding)
    }

    /** Asks again after a failure, between the same points. */
    fun retry() {
        val current = state.value ?: return
        if (current.origin != null) ask(current)
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
        val origin = directions.origin ?: return
        request?.cancel()
        locating?.cancel()
        locating = null
        // A step tapped on the route before is not one of the next route's. While it guides the user
        // the map follows them: a new route is not fitted.
        val asked =
            directions.copy(status = RouteStatus.Finding, routeFitted = directions.guiding, shownStep = null, progress = null)
        state.value = asked
        asking = asked
        request =
            scope.launch {
                val answer =
                    if (asked.reversed) {
                        routing.route(asked.place, origin, asked.mode)
                    } else {
                        routing.route(origin, asked.place, asked.mode)
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
                if (asking === asked && now != null) {
                    // Guidance goes on along the new route from where the user is (none found: it says why).
                    val route = (status as? RouteStatus.Found)?.route
                    val progress =
                        if (now.guiding &&
                            route != null
                        ) {
                            now.position?.let { routeProgress(route, it, accuracyMeters = accuracy) }
                        } else {
                            null
                        }
                    state.value = now.copy(status = status, progress = progress)
                }
            }
    }
}
