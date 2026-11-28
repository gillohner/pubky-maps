package to.pubky.maps.routing

import kotlin.math.roundToInt

/**
 * What a route guiding the user says aloud, and when, as Google Maps', CoMaps' and OsmAnd's voice:
 * at the start the first step ("Drive north."); before each step a warning with its distance ("In 500
 * meters, turn right onto Museumstrasse.") and the step itself just before it ("Turn right onto
 * Museumstrasse. Then Bear right onto Bahnhofquai."); after a step followed by a long stretch, how
 * long it is ("Continue for 2 kilometers."); once on arrival; once each time the user goes off the
 * route. The sentences are the routing server's ([SpokenStep]). Each is said once per route: a new
 * route (found again from where the user is) starts over; a step the server wrote no spoken sentence
 * for is said with its written one. [next] is given every new state of the guidance and answers what
 * to say now, or null; sentences due one after the other are said in turn (the start and a first
 * turn's warning).
 */
class VoicePrompts {
    private var route: Route? = null
    private val said = HashSet<Pair<Int, Prompt>>()
    private var lastNext: Int? = null
    private var started = false
    private var off = false
    private var arrived = false

    private enum class Prompt { Warning, Before }

    fun next(directions: Directions): String? {
        if (!directions.guiding) {
            reset(null)
            return null
        }
        val found = directions.status as? RouteStatus.Found ?: return null
        val progress = directions.progress ?: return null
        if (found.route !== route) reset(found.route)
        val steps = found.route.steps
        if (progress.offRoute) {
            if (off) return null
            off = true
            return "You're off the route."
        }
        off = false
        if (progress.arrived) {
            if (arrived) return null
            arrived = true
            val arrive = steps.lastOrNull()?.takeIf { it.turn == Turn.Arrive }
            val saidBefore = (steps.size - 1 to Prompt.Before) in said
            return if (saidBefore) null else arrive?.spoken?.before ?: arrive?.instruction ?: "You have arrived."
        }
        val n = progress.nextStep ?: return null
        val step = steps.getOrNull(n) ?: return null
        val distance = progress.toNextStep
        val (warnAt, sayAt) = promptDistances(directions.mode, steps.getOrNull(n - 1))
        val passed = lastNext?.takeIf { it < n }
        lastNext = n
        if (!started) {
            started = true
            // A step that comes at once is said instead of the way to start.
            val depart = steps.getOrNull(n - 1)?.takeIf { it.turn == Turn.Depart }
            if (distance > sayAt && depart != null) return depart.spoken?.before ?: depart.instruction
        }
        if (distance <= sayAt) {
            if ((n to Prompt.Before) in said) return null
            said += n to Prompt.Before
            said += n to Prompt.Warning
            return step.spoken?.before ?: step.spoken?.alert ?: step.instruction
        }
        if (passed != null && distance > warnAt) return steps[n - 1].spoken?.after
        // Not just before the step's own sentence, which would follow it at once.
        if (distance <= warnAt && distance > sayAt * WARNING_ROOM && (n to Prompt.Warning) !in said) {
            said += n to Prompt.Warning
            val alert = step.spoken?.alert ?: step.instruction
            return "In ${spokenDistance(distance)}, ${alert.replaceFirstChar { it.lowercase() }}"
        }
        return null
    }

    private fun reset(route: Route?) {
        this.route = route
        said.clear()
        lastNext = null
        started = false
        off = false
        arrived = false
    }
}

/**
 * How far before a step its warning and its own sentence are said, by [mode] and at the pace of the
 * stretch to it ([leading], the step before; its time and length): the sentence about 7 seconds
 * before (a turn's own sentence takes 2 seconds to say), the warning about 30 seconds before, and
 * never closer than a few seconds' walk, ride or drive in town, so a fast road (a motorway at 33 m/s)
 * gets them early enough and a slow stretch not too late.
 */
internal fun promptDistances(
    mode: TravelMode,
    leading: RouteStep?,
): Pair<Double, Double> {
    val speed = leading?.takeIf { it.seconds > 0 }?.let { it.meters / it.seconds } ?: 0.0
    val (warnAtLeast, sayAtLeast) =
        when (mode) {
            TravelMode.Car -> 500.0 to 120.0
            TravelMode.Bike -> 250.0 to 50.0
            TravelMode.Walk -> 100.0 to 25.0
        }
    return maxOf(warnAtLeast, minOf(speed * 30, MAX_WARNING_METERS)) to maxOf(sayAtLeast, speed * 7)
}

/** The warning comes this much farther from the step than its own sentence at least. */
private const val WARNING_ROOM = 1.5

/** A warning further ahead than this is forgotten before the step. */
private const val MAX_WARNING_METERS = 2_000.0

/**
 * A distance as said aloud in the routing server's words ("meters"): to 50 m below 1 km, to 0.1 km
 * up to 10 km, whole kilometres beyond.
 */
internal fun spokenDistance(meters: Double): String {
    val fifties = (meters / 50).roundToInt() * 50
    return when {
        fifties < 1_000 -> {
            "${maxOf(fifties, 50)} meters"
        }

        meters < 9_950 -> {
            val tenths = (meters / 100).roundToInt()
            val km = if (tenths % 10 == 0) "${tenths / 10}" else "${tenths / 10}.${tenths % 10}"
            if (km == "1") "1 kilometer" else "$km kilometers"
        }

        else -> {
            "${(meters / 1_000).roundToInt()} kilometers"
        }
    }
}
