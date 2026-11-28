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
import kotlin.time.Instant
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * How many positions in a row off the route ask for a new route: one fix that jumped (a reflection
 * between houses) is not taken for a wrong turn, two are, a second or two after it.
 */
internal const val REROUTE_AFTER_FIXES = 2

/**
 * How long after a new route was asked for while guiding the next one is asked by itself: well within
 * FOSSGIS' one request a second, and a driver who keeps off the new route too gets another within it.
 */
internal val REROUTE_SPACING = 10.seconds

/** Where a route to a place stands. */
sealed interface RouteStatus {
    /** Waiting for the user's position (the permission dialog, the first fix). */
    data object Locating : RouteStatus

    /**
     * The user's position cannot be had: [permissionOff] when only the app's system settings can change
     * that, [locationOff] when the phone's location is off (location tracking stopped), which the
     * phone's location setting turns on again.
     */
    data class NoLocation(
        val permissionOff: Boolean,
        val locationOff: Boolean = false,
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
    /**
     * While the route guides the user: a new route from where they are is asked for
     * ([DirectionsController.guide]); this one stays on the map and guides them until it comes.
     */
    val rerouting: Boolean = false,
    /**
     * While the route guides the user: why the last new route asked for did not come
     * ([RouteStatus.NoRoute], [RouteStatus.Unreachable]); this one still guides them.
     */
    val noNewRoute: RouteStatus? = null,
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
 * each position ([guide]), and a new route from where they are once they are off it, by itself or on
 * request ([routeAgainFrom]), until they end it; the route keeps guiding them while a new one is asked
 * for and when none comes. Every routing server failing names routing in [health] until one answers.
 * A position that has not come within [locatingTimeout] (a permission dialog dismissed, no fix
 * indoors) is missing; one that comes later still starts the route.
 */
class DirectionsController(
    private val routing: Routing,
    private val scope: CoroutineScope,
    private val health: ServiceHealth? = null,
    private val locatingTimeout: Duration = 30.seconds,
    private val timeSource: TimeSource = TimeSource.Monotonic,
) {
    private val state = MutableStateFlow<Directions?>(null)
    val directions: StateFlow<Directions?> = state.asStateFlow()

    private val chosen = MutableStateFlow<RouteStart?>(null)

    /** The place the user chose to start directions from; null for their position. */
    val chosenStart: StateFlow<RouteStart?> = chosen.asStateFlow()

    /** What guidance has said aloud, kept with it for the process: a recreated screen does not say it again. */
    val voicePrompts = VoicePrompts()

    /** The mode of the next directions: the one the user picked last, by car at first. */
    private var lastMode = TravelMode.Car
    private var request: Job? = null

    /** Every tap on a step so far, for [Directions.stepTaps]; never goes back, so a tap is never taken for one before it. */
    private var stepTaps = 0

    /** The accuracy of the last position the guidance had, for the progress on a new route from there. */
    private var accuracy: Double? = null

    /** How many positions in a row were off the route guiding the user, for [REROUTE_AFTER_FIXES]. */
    private var offFixes = 0

    /** Where and when the last new route was asked for while guiding the user, for [mayRerouteFrom]. */
    private var rerouted: Pair<GeoPoint, TimeMark>? = null

    /** When the last position the guidance had was measured, so the same fix sent again is not counted twice. */
    private var measured: Instant? = null

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

    /** The user's position cannot be had for the directions waiting for it ([RouteStatus.NoLocation]). */
    fun locationMissing(
        permissionOff: Boolean,
        locationOff: Boolean = false,
    ) {
        val current = state.value ?: return
        if (current.status != RouteStatus.Locating) return
        locating?.cancel()
        locating = null
        set(current.copy(status = RouteStatus.NoLocation(permissionOff, locationOff)))
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
        measuredAt: Instant? = null,
    ) {
        val current = state.value ?: return
        if (!current.canGuide || current.guiding) return
        offFixes = 0
        rerouted = null
        measured = null
        val started = current.copy(guiding = true, stepsShown = false, shownStep = null, routeFitted = true, progress = null)
        state.value = started
        if (position != null) guide(position, accuracyMeters, measuredAt)
    }

    /**
     * The user's [position] while the route guides them (within [accuracyMeters], when known; measured
     * at [measuredAt], when known: the fix Start began with, sent again by the map, is one position): where
     * they are on it now, looked for from where they were ([routeProgress]). Off the route on
     * [REROUTE_AFTER_FIXES] positions in a row, a new route from there is asked for by itself
     * ([mayRerouteFrom]), as Google Maps and OsmAnd reroute; back on the route before it comes, it is
     * no longer asked for.
     */
    fun guide(
        position: GeoPoint,
        accuracyMeters: Double? = null,
        measuredAt: Instant? = null,
    ) {
        val current = state.value ?: return
        val route = (current.status as? RouteStatus.Found)?.route ?: return
        if (!current.guiding) return
        accuracy = accuracyMeters
        val progress = routeProgress(route, position, current.progress?.onRouteAlong ?: 0.0, accuracyMeters)
        if (!progress.offRoute) {
            offFixes = 0
            if (current.rerouting) {
                request?.cancel()
                request = null
                asking = null
            }
            state.value = current.copy(progress = progress, position = position, rerouting = false, noNewRoute = null)
            return
        }
        val sameFix = measuredAt != null && measuredAt == measured
        measured = measuredAt
        if (!sameFix) offFixes += 1
        val now = current.copy(progress = progress, position = position)
        state.value = now
        if (!now.rerouting && offFixes >= REROUTE_AFTER_FIXES && mayRerouteFrom(position)) reroute(now)
    }

    /**
     * Whether a new route may be asked for by itself from [position]: not within [REROUTE_SPACING] of
     * the last one asked for (also one dropped as the user came back on the route), and not until the
     * user moved [OFF_ROUTE_METERS] from where it was asked, so a user
     * who stays off a new route (it starts on the nearest road, out of reach of a fix in a park; no
     * server answered) is not asked a route for again and again from the same place; Find a new route
     * asks still.
     */
    private fun mayRerouteFrom(position: GeoPoint): Boolean {
        val (at, mark) = rerouted ?: return true
        return mark.elapsedNow() >= REROUTE_SPACING && metersBetween(at, position) >= OFF_ROUTE_METERS
    }

    /**
     * The user, off the route, asked for a new one from their [position] to the same place: it keeps
     * guiding them once found, from where they are then.
     */
    fun routeAgainFrom(position: GeoPoint) {
        val current = state.value ?: return
        if (!current.guiding) return
        reroute(current.copy(position = position))
    }

    /**
     * The route stops guiding the user: the place page shows the directions again, the route left
     * where the map is; a new route still asked for is fitted once found, as any other.
     */
    fun endGuidance() {
        val current = state.value ?: return
        if (!current.guiding) return
        // A new route still asked for comes as any other route: the card waits for it.
        state.value =
            current.copy(
                guiding = false,
                progress = null,
                status = if (current.rerouting) RouteStatus.Finding else current.status,
                routeFitted = current.routeFitted && !current.rerouting,
                rerouting = false,
                noNewRoute = null,
            )
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
        // A step tapped on the route before is not one of the next route's.
        val asked = directions.copy(status = RouteStatus.Finding, routeFitted = false, shownStep = null)
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
                val status = statusOf(answer)
                // A newer request or a close replaced this one meanwhile (a cancel that came too late);
                // the steps opened or closed while it ran are kept.
                val now = state.value
                if (asking === asked && now != null) state.value = now.copy(status = status)
            }
    }

    /**
     * Asks for a new route from the guided user's position in [directions] to the place, the route
     * they are on kept on the map and guiding them meanwhile: once found it guides them from where
     * they are then (the map follows them, so it is not fitted); none found, the route they are on
     * stays and [Directions.noNewRoute] says why. Ended meanwhile, the answer is the card's route.
     */
    private fun reroute(directions: Directions) {
        val position = directions.position ?: return
        request?.cancel()
        rerouted = position to timeSource.markNow()
        val asked = directions.copy(rerouting = true, noNewRoute = null)
        state.value = asked
        asking = asked
        request =
            scope.launch {
                val status = statusOf(routing.route(position, asked.place, asked.mode))
                val now = state.value
                if (asking !== asked || now == null) return@launch
                state.value =
                    when {
                        !now.guiding -> {
                            now.copy(status = status)
                        }

                        status is RouteStatus.Found -> {
                            offFixes = 0
                            val progress = now.position?.let { routeProgress(status.route, it, accuracyMeters = accuracy) }
                            now.copy(status = status, progress = progress, rerouting = false)
                        }

                        else -> {
                            now.copy(rerouting = false, noNewRoute = status)
                        }
                    }
            }
    }

    /** The status of [answer]; every server failing names routing in [health] until one answers. */
    private fun statusOf(answer: RouteAnswer): RouteStatus {
        val status =
            when (answer) {
                is RouteAnswer.Found -> RouteStatus.Found(answer.route, answer.server)
                is RouteAnswer.NoRoute -> RouteStatus.NoRoute(answer.reason)
                RouteAnswer.Unreachable -> RouteStatus.Unreachable
            }
        if (status == RouteStatus.Unreachable) health?.failed(ServicePart.Routing) else health?.recovered(ServicePart.Routing)
        return status
    }
}
