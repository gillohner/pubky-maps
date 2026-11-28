package to.pubky.maps.routing

import to.pubky.maps.links.GeoPoint
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * Where the user is on a route while it guides them ([Directions.guiding]): the next step to do
 * ([nextStep], an index in the route's steps; null past the last one) and how far ahead it is
 * ([toNextStep]), how much of the route is left ([metersLeft], [secondsLeft]), how far the user is
 * from its line ([offBy]) and whether that is off the route ([offRoute]) or at its end ([arrived]).
 * The user's position is put [along] the line, on its piece [segment] (from its point [segment] to
 * the next); [onRouteAlong] is where they were last on the route ([along], or the last one before
 * they went off it), where the next position is looked for first; [ahead] the way the line goes
 * from there, for the map's bearing (degrees clockwise from north).
 */
data class RouteProgress(
    val segment: Int,
    val along: Double,
    val offBy: Double,
    val offRoute: Boolean,
    val arrived: Boolean,
    val nextStep: Int?,
    val toNextStep: Double,
    val metersLeft: Double,
    val secondsLeft: Double,
    val ahead: Double,
    val onRouteAlong: Double = along,
)

/** How far from a route's line a position is off the route: about a street's width and a GPS fix's error. */
internal const val OFF_ROUTE_METERS = 50.0

/** How close to a route's end the user has arrived. */
internal const val ARRIVED_METERS = 25.0

/** How far along the line the map looks for the way ahead: a short piece of line has no clear bearing. */
private const val AHEAD_METERS = 20.0

/** Stretches of line about this much farther from the user than the nearest one are as near. */
private const val SAME_DISTANCE_METERS = 5.0

/** Within a stretch of line, places this much farther than its nearest are as near (the two ends of a turn). */
private const val TIE_METERS = 0.5

private const val EARTH_RADIUS_METERS = 6_371_000.0

/**
 * The user's progress at [position] along [route], where they were last on it [previousAlong]
 * metres from its start ([RouteProgress.onRouteAlong]; 0 at the start). The position is put on the
 * nearest point of the line; of stretches of line about as near ([SAME_DISTANCE_METERS]), on the one
 * closest along the line to where the user was: a route that comes back along a street (there and
 * back, a loop) is taken for the part the user is on, and one fix that jumped ahead is undone by the
 * next. Off the route past [OFF_ROUTE_METERS], or the fix's own [accuracyMeters] when it is worse (a
 * fix that may be 80 m off is not taken for a wrong turn); off the route, where the user was stays
 * the place to look from. The time left is the route's time for the share of its length that is
 * left (Valhalla's time is for the whole route).
 */
fun routeProgress(
    route: Route,
    position: GeoPoint,
    previousAlong: Double = 0.0,
    accuracyMeters: Double? = null,
): RouteProgress {
    val points = route.points
    val along = cumulativeMeters(points)
    val offs = (0 until points.size - 1).map { offSegment(position, points[it], points[it + 1]) }

    fun alongOf(i: Int) = along[i] + offs[i].second * (along[i + 1] - along[i])
    val near = offs.minOf { it.first } + SAME_DISTANCE_METERS
    // The nearest place of each stretch of line about as near as the nearest of all (of two as near
    // in it, the one closer to where the user was), and of those the one closest to where they were.
    var best = -1
    var i = 0
    while (i < offs.size) {
        if (offs[i].first > near) {
            i++
            continue
        }
        var nearest = i
        var j = i
        while (j < offs.size && offs[j].first <= near) {
            val closer = offs[j].first < offs[nearest].first - TIE_METERS
            val asNear = offs[j].first <= offs[nearest].first + TIE_METERS
            if (closer || (asNear && abs(alongOf(j) - previousAlong) < abs(alongOf(nearest) - previousAlong))) nearest = j
            j++
        }
        if (best < 0 || abs(alongOf(nearest) - previousAlong) < abs(alongOf(best) - previousAlong)) best = nearest
        i = j
    }
    val bestOff = offs[best].first
    val bestAlong = alongOf(best)
    val total = along.last()
    val left = (total - bestAlong).coerceAtLeast(0.0)
    val next = route.steps.indexOfFirst { it.shapeIndex > best }.takeIf { it >= 0 }
    val toNext = next?.let { (along[route.steps[it].shapeIndex.coerceAtMost(points.size - 1)] - bestAlong).coerceAtLeast(0.0) } ?: left
    val offLimit = maxOf(OFF_ROUTE_METERS, accuracyMeters ?: 0.0)
    val offRoute = bestOff > offLimit
    return RouteProgress(
        segment = best,
        along = bestAlong,
        onRouteAlong = if (offRoute) previousAlong else bestAlong,
        offBy = bestOff,
        offRoute = offRoute,
        arrived = left < ARRIVED_METERS && bestOff <= offLimit,
        nextStep = next,
        toNextStep = toNext,
        metersLeft = left,
        secondsLeft = if (total > 0) route.seconds * left / total else 0.0,
        ahead = bearingAhead(points, along, best, bestAlong),
    )
}

/** The distance from the start of [points] to each of them, along the line. */
internal fun cumulativeMeters(points: List<GeoPoint>): DoubleArray {
    val along = DoubleArray(points.size)
    for (i in 1 until points.size) along[i] = along[i - 1] + metersBetween(points[i - 1], points[i])
    return along
}

/** The distance between [a] and [b] on a plane around them: exact enough within a city block. */
internal fun metersBetween(
    a: GeoPoint,
    b: GeoPoint,
): Double {
    val (x, y) = planar(b, a)
    return sqrt(x * x + y * y)
}

/** How far [p] is from the segment [a]-[b], and where on it the nearest point is (0 at [a], 1 at [b]). */
private fun offSegment(
    p: GeoPoint,
    a: GeoPoint,
    b: GeoPoint,
): Pair<Double, Double> {
    val (bx, by) = planar(b, a)
    val (px, py) = planar(p, a)
    val length2 = bx * bx + by * by
    val t = if (length2 == 0.0) 0.0 else ((px * bx + py * by) / length2).coerceIn(0.0, 1.0)
    val dx = px - t * bx
    val dy = py - t * by
    return sqrt(dx * dx + dy * dy) to t
}

/** [p] in metres east and north of [origin]. */
private fun planar(
    p: GeoPoint,
    origin: GeoPoint,
): Pair<Double, Double> {
    val radians = PI / 180
    val x = (p.lon - origin.lon) * radians * EARTH_RADIUS_METERS * cos(origin.lat * radians)
    val y = (p.lat - origin.lat) * radians * EARTH_RADIUS_METERS
    return x to y
}

/** The bearing from the user's point on the line ([at], on [segment]) to the line [AHEAD_METERS] further on. */
private fun bearingAhead(
    points: List<GeoPoint>,
    along: DoubleArray,
    segment: Int,
    at: Double,
): Double {
    val here = pointAt(points, along, segment, at)
    var i = segment
    while (i < points.size - 2 && along[i + 1] < at + AHEAD_METERS) i++
    val there = pointAt(points, along, i, minOf(at + AHEAD_METERS, along[i + 1]))
    val (x, y) =
        if (here == there) {
            planar(points[segment + 1], points[segment])
        } else {
            planar(there, here)
        }
    if (x == 0.0 && y == 0.0) return 0.0
    return (atan2(x, y) * 180 / PI + 360) % 360
}

/** The point [at] metres along the line, on its [segment]. */
private fun pointAt(
    points: List<GeoPoint>,
    along: DoubleArray,
    segment: Int,
    at: Double,
): GeoPoint {
    val a = points[segment]
    val b = points[segment + 1]
    val length = along[segment + 1] - along[segment]
    val t = if (length == 0.0) 0.0 else ((at - along[segment]) / length).coerceIn(0.0, 1.0)
    return GeoPoint(a.lat + t * (b.lat - a.lat), a.lon + t * (b.lon - a.lon))
}
