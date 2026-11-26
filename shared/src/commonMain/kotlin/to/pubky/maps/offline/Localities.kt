package to.pubky.maps.offline

import to.pubky.maps.search.normalizeForMatch
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * Where the places of an offline area lie, "Hottingen, Zürich", read from the area's own place
 * labels ([labels], the area's places with the `place` key): the district (borough, suburb, quarter,
 * neighbourhood, hamlet) and the settlement (city, town, village) whose label is nearest, each
 * distance divided by how far a label of its kind reaches (a city 12 km, a village 2 km), so a
 * point a few kilometres from a city's centre is named after the city and not after the village
 * next to it. A label farther than its reach names nothing. The map tiles have no boundaries: a place near one can be
 * named after the neighbour, as Photon's nearest-label answers can.
 */
internal class Localities(
    labels: List<OfflinePlace>,
) {
    private val districts = Grid(labels.filter { it.key == "place" && it.value in DISTRICT_REACH })
    private val settlements = Grid(labels.filter { it.key == "place" && it.value in SETTLEMENT_REACH })

    /**
     * The district and settlement of [place], comma separated, or null when no label reaches it.
     * A settlement's own label gets none (it is the place people name others by), a
     * district's label only its settlement, and a label never names itself.
     */
    fun of(place: OfflinePlace): String? {
        if (place.key != "place") return at(place.point.lat, place.point.lon)
        if (place.value in SETTLEMENT_REACH) return null
        return name(place.point.lat, place.point.lon, own = normalizeForMatch(place.name), withDistrict = place.value !in DISTRICT_REACH)
    }

    /** The district and settlement of a place that is not a label, at [lat], [lon]. */
    fun at(
        lat: Double,
        lon: Double,
    ): String? = name(lat, lon, own = null, withDistrict = true)

    private fun name(
        lat: Double,
        lon: Double,
        own: String?,
        withDistrict: Boolean,
    ): String? {
        val settlement = settlements.nearest(lat, lon, own)
        // "Winterthur, Winterthur" says it once.
        val district = if (withDistrict) districts.nearest(lat, lon, own)?.takeIf { it.match != settlement?.match } else null
        return listOfNotNull(district?.name, settlement?.name).joinToString(", ").ifEmpty { null }
    }

    /** A label as the grid keeps it: its [match] name ([normalizeForMatch]) and [reach] worked out once. */
    private class Label(
        val name: String,
        val match: String,
        val lat: Double,
        val lon: Double,
        val reach: Double,
    )

    /** Labels in cells of [CELL_DEGREES], each in every cell its reach touches, so a point looks in its own cell only. */
    private class Grid(
        labels: List<OfflinePlace>,
    ) {
        private val cells = HashMap<Long, MutableList<Label>>()

        init {
            for (place in labels) {
                val label = Label(place.name, normalizeForMatch(place.name), place.point.lat, place.point.lon, REACH.getValue(place.value))
                val latCells = label.reach / METERS_PER_DEGREE / CELL_DEGREES
                val lonCells = latCells / cos(label.lat * PI / 180).coerceAtLeast(MIN_COS)
                val row = label.lat / CELL_DEGREES
                val column = label.lon / CELL_DEGREES
                for (r in floor(row - latCells).toInt()..ceil(row + latCells).toInt()) {
                    for (c in floor(column - lonCells).toInt()..ceil(column + lonCells).toInt()) {
                        cells.getOrPut(key(r, c)) { ArrayList() } += label
                    }
                }
            }
        }

        /** The label nearest [lat], [lon] for its reach, not named [own]; null when none reaches it. */
        fun nearest(
            lat: Double,
            lon: Double,
            own: String?,
        ): Label? {
            var best: Label? = null
            var bestShare = Double.MAX_VALUE
            for (label in cells[key(floor(lat / CELL_DEGREES).toInt(), floor(lon / CELL_DEGREES).toInt())].orEmpty()) {
                val share = metersBetween(lat, lon, label.lat, label.lon) / label.reach
                if (share > 1.0 || share >= bestShare || label.match == own) continue
                best = label
                bestShare = share
            }
            return best
        }

        private fun key(
            row: Int,
            column: Int,
        ): Long = (row.toLong() shl 32) or (column.toLong() and 0xffffffffL)
    }

    companion object {
        /** True when a label of [kind] names the places around it (a town, a district). */
        fun names(kind: String): Boolean = kind in REACH

        /** How far a settlement's label names the places around it, in meters. */
        private val SETTLEMENT_REACH = mapOf("city" to 12_000.0, "town" to 5_000.0, "village" to 2_000.0)

        /** How far a district's label names the places around it, in meters. */
        private val DISTRICT_REACH =
            mapOf("borough" to 4_000.0, "suburb" to 2_500.0, "quarter" to 1_200.0, "neighbourhood" to 600.0, "hamlet" to 800.0)

        /** Every kind of label that names others, with its reach. */
        private val REACH: Map<String, Double> = SETTLEMENT_REACH + DISTRICT_REACH

        /** About 2 km: a city's reach spans some 12 cells each way, a district's one or two. */
        private const val CELL_DEGREES = 0.02

        private const val METERS_PER_DEGREE = 111_320.0

        /** Keeps the cells of a label near a pole finite. */
        private const val MIN_COS = 0.01

        /** Distance on a plane, enough within a few kilometres and much cheaper than a great circle. */
        private fun metersBetween(
            lat1: Double,
            lon1: Double,
            lat2: Double,
            lon2: Double,
        ): Double {
            val dLat = (lat1 - lat2) * METERS_PER_DEGREE
            val dLon = (lon1 - lon2) * METERS_PER_DEGREE * cos((lat1 + lat2) / 2 * PI / 180)
            return sqrt(dLat * dLat + dLon * dLon)
        }
    }
}
