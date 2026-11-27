package to.pubky.maps.routing

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import to.pubky.maps.links.GeoPoint

/** How the user travels: by car, by bike or on foot, with Valhalla's costing model for each. */
enum class TravelMode(
    val costing: String,
) {
    Car("auto"),
    Bike("bicycle"),
    Walk("pedestrian"),
}

/** A route: its line from start to end, how long it takes and how long it is, and its [steps] in order. */
data class Route(
    val points: List<GeoPoint>,
    val seconds: Double,
    val meters: Double,
    val steps: List<RouteStep> = emptyList(),
)

/**
 * One step of a route: what to do there ([instruction], the routing server's sentence, "Turn right
 * onto Museumstrasse."), which way it goes ([turn], for its icon) and how far the route then goes
 * until the next step ([meters], 0 at the end).
 */
data class RouteStep(
    val instruction: String,
    val turn: Turn,
    val meters: Double,
)

/** Which way a route step goes. */
enum class Turn {
    Depart,
    Straight,
    SlightLeft,
    Left,
    SharpLeft,
    UTurnLeft,
    SlightRight,
    Right,
    SharpRight,
    UTurnRight,

    /** Keep left at a fork, or take a ramp or an exit on the left. */
    KeepLeft,
    KeepRight,
    Merge,
    Roundabout,
    Ferry,
    Arrive,
}

/** What Valhalla's maneuver [type] (its `Maneuver.Type`) means for the step's icon. */
fun valhallaTurn(type: Int): Turn =
    when (type) {
        1, 2, 3 -> Turn.Depart

        4, 5, 6 -> Turn.Arrive

        9 -> Turn.SlightRight

        10 -> Turn.Right

        11 -> Turn.SharpRight

        12 -> Turn.UTurnRight

        13 -> Turn.UTurnLeft

        14 -> Turn.SharpLeft

        15 -> Turn.Left

        16 -> Turn.SlightLeft

        18, 20, 23 -> Turn.KeepRight

        19, 21, 24 -> Turn.KeepLeft

        25, 37, 38 -> Turn.Merge

        26, 27 -> Turn.Roundabout

        28, 29 -> Turn.Ferry

        // Continue, becomes, straight ramps, stay straight, elevators, steps, buildings, and types newer than this list.
        else -> Turn.Straight
    }

/** Why a routing server found no route, as Valhalla's error codes say. */
enum class NoRouteReason {
    /** Longer than the server routes for this mode (Valhalla 150-158, e.g. 100 km on foot on FOSSGIS). */
    TooFar,

    /** No road or path the mode can use near the start or the end (Valhalla 171-172). */
    NoRoadNear,

    /** The two ends are not connected for this mode (Valhalla 170, 442/443, and any other refusal). */
    NotConnected,
}

/**
 * Valhalla's route request from [from] to [to] for [mode]: the line and its turn instructions, in
 * English as every other text of the app.
 */
fun valhallaRouteBody(
    from: GeoPoint,
    to: GeoPoint,
    mode: TravelMode,
): String =
    buildJsonObject {
        putJsonArray("locations") {
            for (point in listOf(from, to)) {
                addJsonObject {
                    put("lat", point.lat)
                    put("lon", point.lon)
                }
            }
        }
        put("costing", mode.costing)
        put("units", "kilometers")
        put("directions_type", "instructions")
        put("language", "en-US")
        put("shape_format", "polyline6")
    }.toString()

/**
 * Reads Valhalla's route answer: every leg's line and steps in order (a step without a sentence is
 * left out), the trip's time and length.
 */
fun parseValhallaRoute(json: String): Route {
    val trip = ValhallaJson.decodeFromString(ValhallaRouteResponse.serializer(), json).trip
    val points = ArrayList<GeoPoint>()
    val steps = ArrayList<RouteStep>()
    for (leg in trip.legs) {
        val line = decodePolyline6(leg.shape)
        // A leg starts where the one before it ended.
        points += if (points.isNotEmpty() && line.firstOrNull() == points.last()) line.drop(1) else line
        for (maneuver in leg.maneuvers) {
            if (maneuver.instruction.isBlank()) continue
            steps += RouteStep(maneuver.instruction.trim(), valhallaTurn(maneuver.type), maneuver.length * 1_000)
        }
    }
    require(points.size >= 2) { "A route needs two points" }
    return Route(points = points, seconds = trip.summary.time, meters = trip.summary.length * 1_000, steps = steps)
}

/** Valhalla's error code of a refused request, or null when [json] is not one. */
fun valhallaErrorCode(json: String): Int? =
    runCatching { ValhallaJson.decodeFromString(ValhallaError.serializer(), json).errorCode }.getOrNull()

/** What Valhalla's error [code] means for the user. */
fun noRouteReason(code: Int): NoRouteReason =
    when (code) {
        in 150..158 -> NoRouteReason.TooFar
        in 171..172 -> NoRouteReason.NoRoadNear
        else -> NoRouteReason.NotConnected
    }

/**
 * Decodes an encoded polyline with 6 decimals (Valhalla's `polyline6`): latitude then longitude, each
 * the difference to the point before. A truncated string ends at its last whole point.
 */
fun decodePolyline6(encoded: String): List<GeoPoint> {
    val points = ArrayList<GeoPoint>()
    var index = 0
    var lat = 0L
    var lon = 0L

    fun next(): Long? {
        var result = 0L
        var shift = 0
        while (index < encoded.length) {
            val b = encoded[index++].code - 63
            result = result or ((b and 0x1f).toLong() shl shift)
            shift += 5
            if (b < 0x20) return if (result and 1L != 0L) (result shr 1).inv() else result shr 1
        }
        return null
    }
    while (index < encoded.length) {
        val dLat = next() ?: break
        val dLon = next() ?: break
        lat += dLat
        lon += dLon
        points += GeoPoint(lat / 1e6, lon / 1e6)
    }
    return points
}

private val ValhallaJson = Json { ignoreUnknownKeys = true }

@Serializable
private data class ValhallaRouteResponse(
    val trip: ValhallaTrip,
)

@Serializable
private data class ValhallaTrip(
    val legs: List<ValhallaLeg> = emptyList(),
    val summary: ValhallaSummary,
)

@Serializable
private data class ValhallaLeg(
    val shape: String = "",
    val maneuvers: List<ValhallaManeuver> = emptyList(),
)

@Serializable
private data class ValhallaManeuver(
    val type: Int = 0,
    val instruction: String = "",
    /** Kilometres, as the request's units ask. */
    val length: Double = 0.0,
)

@Serializable
private data class ValhallaSummary(
    val time: Double,
    val length: Double,
)

@Serializable
private data class ValhallaError(
    @SerialName("error_code") val errorCode: Int,
)
