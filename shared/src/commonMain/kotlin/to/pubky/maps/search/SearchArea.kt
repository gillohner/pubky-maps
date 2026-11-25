package to.pubky.maps.search

import to.pubky.maps.links.GeoPoint
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The part of the map a search is about: the map's [centre] and [zoom], and [halfDiagonalMeters], the
 * distance from the centre to a corner of the visible map.
 */
data class SearchArea(
    val centre: GeoPoint,
    val zoom: Double,
    val halfDiagonalMeters: Double,
) {
    /**
     * How far around the centre Pinakes looks: three screens' worth, at least 10 km (a city) and at
     * most Pinakes' own limit of 200 km. Photon has no radius; it only prefers results near the centre.
     */
    val pinakesRadiusMeters: Int
        get() = (halfDiagonalMeters * PINAKES_SCREENS).coerceIn(MIN_PINAKES_RADIUS, MAX_PINAKES_RADIUS).roundToInt()

    /** The distance at which a result counts half as near as one at the centre (see [SearchRanking]). */
    val proximityScaleMeters: Double
        get() = halfDiagonalMeters.coerceIn(MIN_PROXIMITY_SCALE, MAX_PROXIMITY_SCALE)

    private companion object {
        const val PINAKES_SCREENS = 3.0
        const val MIN_PINAKES_RADIUS = 10_000.0
        const val MAX_PINAKES_RADIUS = 200_000.0
        const val MIN_PROXIMITY_SCALE = 1_000.0
        const val MAX_PROXIMITY_SCALE = 1_000_000.0
    }
}

private const val EARTH_RADIUS_METERS = 6_371_008.8

/** The great-circle distance between two points, in metres. */
fun distanceMeters(
    a: GeoPoint,
    b: GeoPoint,
): Double {
    val lat1 = a.lat.toRadians()
    val lat2 = b.lat.toRadians()
    val dLat = lat2 - lat1
    val dLon = (b.lon - a.lon).toRadians()
    val h = sin(dLat / 2) * sin(dLat / 2) + cos(lat1) * cos(lat2) * sin(dLon / 2) * sin(dLon / 2)
    return 2 * EARTH_RADIUS_METERS * asin(sqrt(h.coerceIn(0.0, 1.0)))
}

private fun Double.toRadians(): Double = this * kotlin.math.PI / 180.0

/** "350 m", "1.2 km", "12 km": how far a place is, rounded as map apps round it. */
fun formatDistance(meters: Double): String =
    when {
        // Below 995 m, so 998 m does not round to "1000 m".
        meters < 995 -> {
            "${(meters / 10).roundToInt() * 10} m"
        }

        meters < 9_950 -> {
            val tenths = (meters / 100).roundToInt()
            if (tenths % 10 == 0) "${tenths / 10} km" else "${tenths / 10}.${tenths % 10} km"
        }

        else -> {
            "${(meters / 1_000).roundToInt()} km"
        }
    }
