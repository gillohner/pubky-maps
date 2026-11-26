package to.pubky.maps.offline

import to.pubky.maps.links.GeoPoint
import to.pubky.maps.links.OsmId
import to.pubky.maps.map.PoiSourceLayers
import to.pubky.maps.map.osmIdOfTileFeature
import to.pubky.maps.map.tappedPlace
import to.pubky.maps.search.categoryOf
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.roundToLong

/**
 * A named place in the map tiles of an offline area: a point of interest, a town or district, a
 * street, a peak or a lake. [key] and [value] are the OpenStreetMap tag it stands for as far as the
 * tile says (`amenity`/`cafe`, `place`/`village`, `highway`/`primary`); [category] is the label the
 * map gives it when tapped; [locality] the district and town it lies in ([Localities]), set once the
 * area's places are stored.
 */
data class OfflinePlace(
    val name: String,
    val osm: OsmId?,
    val key: String,
    val value: String,
    val category: String?,
    val point: GeoPoint,
    val locality: String? = null,
) {
    /** The kind of place in Photon's words, for its zoom and icon: `city`, `district`, `locality`, `street`, or null. */
    internal val photonType: String?
        get() =
            when {
                key == "place" && value in CITIES -> "city"
                key == "place" && value in DISTRICTS -> "district"
                key == "place" -> "locality"
                key == "highway" && category == STREET -> "street"
                else -> null
            }

    /** One place seen in several tiles (a street crosses them, a point sits in their buffers) is kept once. */
    internal val sameness: String
        get() =
            osm?.let { "${it.type}/${it.id}" }
                ?: "$name|$value|${(point.lat * SAME_PLACE_GRID).roundToLong()}|${(point.lon * SAME_PLACE_GRID).roundToLong()}"
}

private val CITIES = setOf("city", "town")
private val DISTRICTS = setOf("village", "suburb", "quarter", "borough")

/** The category of a street label. */
private const val STREET = "Street"

/** About 1 km: the pieces of one street in neighbouring tiles. */
private const val SAME_PLACE_GRID = 100.0

/**
 * The named places of the vector tile at [z]/[x]/[y], in the OpenMapTiles schema (OpenFreeMap) or
 * the Shortbread schema (VersaTiles). A feature counts in the tile that holds its point, not in the
 * neighbours whose buffer it reaches into.
 */
internal fun placesOfTile(
    z: Int,
    x: Int,
    y: Int,
    layers: List<TileLayer>,
): List<OfflinePlace> {
    val places = ArrayList<OfflinePlace>()
    for (layer in layers) {
        for (feature in layer.features) {
            if (feature.x !in 0 until layer.extent || feature.y !in 0 until layer.extent) continue
            val point = tilePoint(z, x + feature.x.toDouble() / layer.extent, y + feature.y.toDouble() / layer.extent)
            placeOf(layer.name, feature, point)?.let { places += it }
        }
    }
    return places
}

private fun placeOf(
    layer: String,
    feature: TileFeature,
    point: GeoPoint,
): OfflinePlace? {
    val properties = feature.properties
    val name = (properties["name"] ?: properties["name_int"])?.trim()?.ifEmpty { null } ?: return null
    val osm = feature.id?.let(::osmIdOfTileFeature)

    fun place(
        key: String,
        value: String,
        category: String? = categoryOf(key, value, photonType = null),
    ) = OfflinePlace(name, osm, key, value, category, point)
    return when (layer) {
        in PoiSourceLayers -> {
            // Named as the map names a tapped point of interest.
            val tapped = tappedPlace(feature.id, properties, point) ?: return null
            val (key, value) = poiTag(layer, properties) ?: return null
            OfflinePlace(name, tapped.osm, key, value, tapped.category, point)
        }

        "place", "place_labels" -> {
            val kind = properties["class"] ?: properties["kind"] ?: return null
            if (kind in SKIPPED_PLACES) null else place("place", kind)
        }

        "transportation_name", "street_labels" -> {
            val kind = properties["class"] ?: properties["kind"] ?: return null
            if (kind in NOT_STREETS) null else place("highway", kind, category = categoryOf("highway", kind, photonType = "street"))
        }

        "mountain_peak" -> {
            place("natural", properties["class"] ?: "peak")
        }

        "water_name" -> {
            place("natural", properties["class"] ?: "water")
        }

        else -> {
            null
        }
    }
}

/**
 * The OSM tag of a point of interest. Shortbread names it by its key (`amenity=cafe`); OpenMapTiles
 * by `class` and `subclass`, where the subclass is the OSM value and the key is left to the class.
 */
private fun poiTag(
    layer: String,
    properties: Map<String, String>,
): Pair<String, String>? {
    properties["subclass"]?.let { value ->
        val key = OMT_KEYS.entries.firstOrNull { (_, values) -> value in values }?.key
        return (key ?: if (properties["class"] in OMT_SHOP_CLASSES) "shop" else "amenity") to value
    }
    SHORTBREAD_KEYS.firstNotNullOfOrNull { key -> properties[key]?.let { key to it } }?.let { return it }
    val kind = properties["kind"] ?: return null
    return when {
        layer != "public_transport" -> "amenity" to kind
        kind == "bus_stop" -> "highway" to kind
        kind in setOf("station", "halt", "tram_stop", "subway_entrance") -> "railway" to kind
        else -> "public_transport" to kind
    }
}

/** OpenMapTiles subclasses whose OSM key is not `amenity`, for the place's icon and weight. */
private val OMT_KEYS: Map<String, Set<String>> =
    mapOf(
        "highway" to setOf("bus_stop"),
        "railway" to setOf("station", "halt", "tram_stop", "subway_entrance", "platform"),
        "public_transport" to setOf("stop_position", "stop_area"),
        "tourism" to
            setOf(
                "museum",
                "gallery",
                "attraction",
                "artwork",
                "zoo",
                "aquarium",
                "theme_park",
                "hotel",
                "hostel",
                "guest_house",
                "motel",
                "camp_site",
                "caravan_site",
                "information",
                "viewpoint",
                "picnic_site",
                "alpine_hut",
            ),
        "leisure" to
            setOf(
                "park",
                "playground",
                "stadium",
                "sports_centre",
                "swimming_pool",
                "pitch",
                "garden",
                "nature_reserve",
                "golf_course",
                "marina",
                "water_park",
                "fitness_centre",
                "dog_park",
                "ice_rink",
            ),
        "historic" to setOf("castle", "monument", "memorial", "ruins", "archaeological_site", "wayside_cross", "wayside_shrine"),
        "aeroway" to setOf("aerodrome"),
    )

/** OpenMapTiles classes that hold only shops. */
private val OMT_SHOP_CLASSES = setOf("shop", "grocery", "clothing_store", "alcohol_shop", "bakery", "jewelry", "furniture")

/** The tag keys a Shortbread point of interest carries, in the order the map's tap reads them. */
private val SHORTBREAD_KEYS = listOf("amenity", "shop", "tourism", "leisure", "historic", "office", "man_made")

/** Place labels too large for a search inside a downloaded area. */
private val SKIPPED_PLACES = setOf("continent", "country", "state", "province")

/** Line labels that are not streets: railways, ferries, lifts, pistes. */
private val NOT_STREETS = setOf("rail", "transit", "ferry", "aerialway", "ski", "railway", "subway", "tram", "light_rail")

/** The point at fractional tile coordinates [x], [y] of zoom [z] (Web Mercator). */
internal fun tilePoint(
    z: Int,
    x: Double,
    y: Double,
): GeoPoint {
    val n = 2.0.pow(z)
    val lon = x / n * 360.0 - 180.0
    val mercator = PI * (1 - 2 * y / n)
    val lat = atan((exp(mercator) - exp(-mercator)) / 2) * 180.0 / PI
    return GeoPoint(lat, lon)
}
