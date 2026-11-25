package to.pubky.maps.search

import io.ktor.http.URLBuilder
import io.ktor.http.appendPathSegments
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import to.pubky.maps.links.GeoPoint
import to.pubky.maps.links.OsmId
import to.pubky.maps.links.OsmType
import to.pubky.maps.place.formatCoordinate

/** A place or address Photon found. [rank] is its position in Photon's answer, 0 first. */
data class PhotonPlace(
    val osm: OsmId?,
    val name: String?,
    val point: GeoPoint,
    /** OpenStreetMap's main tag, e.g. `tourism`/`museum`, `highway`/`residential`. */
    val osmKey: String?,
    val osmValue: String?,
    /** Photon's own kind: `house`, `street`, `locality`, `district`, `city`, `county`, `state`, `country`, `other`. */
    val type: String?,
    val street: String?,
    val houseNumber: String?,
    val postcode: String?,
    val city: String?,
    val state: String?,
    val country: String?,
    val rank: Int,
) {
    /** "Bahnhofstrasse 21", or null without a street. */
    val streetLine: String?
        get() = street?.let { listOfNotNull(it, houseNumber).joinToString(" ") }

    /** What the place is called on screen: its name, else its street address, else its town. */
    val title: String
        get() = name ?: streetLine ?: city ?: state ?: country ?: "${formatCoordinate(point.lat)}, ${formatCoordinate(point.lon)}"

    /**
     * The address line under the title: street and town for places in a town ("Heimplatz 1, 8001
     * Zürich"), the region and country for a town or a region itself ("Ticino, Schweiz").
     */
    val address: String?
        get() {
            val parts =
                if (type in AREA_TYPES) {
                    listOfNotNull(state, country)
                } else {
                    val town = listOfNotNull(postcode, city).joinToString(" ").ifEmpty { null }
                    listOfNotNull(streetLine, town ?: state)
                }
            return parts
                .filter { it != title }
                .distinct()
                .joinToString(", ")
                .ifEmpty { null }
        }

    private companion object {
        val AREA_TYPES = setOf("city", "county", "state", "country")
    }
}

/** Photon's `lang`: the device language when Photon has it, else Photon's default (local names). */
fun photonLanguage(deviceLanguage: String): String = deviceLanguage.lowercase().takeIf { it in PHOTON_LANGUAGES } ?: "default"

/** The languages the public Photon server answers in, besides `default` (checked 2026-11-25). */
private val PHOTON_LANGUAGES = setOf("de", "en", "fr")

/** Results asked of each source. */
internal const val RESULTS_PER_SOURCE = 10

/** Photon's search URL on [server]: [query] near the map centre at the map's zoom. */
fun photonSearchUrl(
    server: String,
    query: String,
    area: SearchArea,
    language: String,
    limit: Int = RESULTS_PER_SOURCE,
): String =
    URLBuilder(server)
        .apply {
            appendPathSegments("api")
            parameters.append("q", query)
            parameters.append("lat", formatCoordinate(area.centre.lat))
            parameters.append("lon", formatCoordinate(area.centre.lon))
            parameters.append(
                "zoom",
                area.zoom
                    .toInt()
                    .coerceIn(0, MAX_PHOTON_ZOOM)
                    .toString(),
            )
            parameters.append("limit", limit.toString())
            parameters.append("lang", language)
        }.buildString()

private const val MAX_PHOTON_ZOOM = 18

/**
 * Reads Photon's GeoJSON answer. Features without a point are dropped, and so are repeats: the same
 * OSM element listed twice, and the pieces of one street (OSM splits a street into many ways; Photon
 * lists each, map apps show the street once).
 */
fun parsePhoton(json: String): List<PhotonPlace> {
    val response = SearchJson.decodeFromString(PhotonResponse.serializer(), json)
    val seenOsm = HashSet<OsmId>()
    val seenStreets = HashSet<Pair<String, String?>>()
    val places = ArrayList<PhotonPlace>()
    response.features.forEachIndexed { index, feature ->
        val p = feature.properties
        val coordinates = feature.geometry?.coordinates ?: return@forEachIndexed
        if (coordinates.size < 2) return@forEachIndexed
        val point = geoPointOrNull(lat = coordinates[1], lon = coordinates[0]) ?: return@forEachIndexed
        val osm = osmIdOf(p.osmType, p.osmId)
        if (osm != null && !seenOsm.add(osm)) return@forEachIndexed
        if (p.type == "street" && p.name != null && !seenStreets.add(normalizeForMatch(p.name) to p.city)) return@forEachIndexed
        places +=
            PhotonPlace(
                osm = osm,
                name = p.name?.trim()?.ifEmpty { null },
                point = point,
                osmKey = p.osmKey,
                osmValue = p.osmValue,
                type = p.type,
                street = p.street,
                houseNumber = p.housenumber,
                postcode = p.postcode,
                city = p.city ?: p.locality,
                state = p.state,
                country = p.country,
                rank = index,
            )
    }
    return places
}

private fun osmIdOf(
    type: String?,
    id: Long?,
): OsmId? {
    if (id == null || id <= 0) return null
    val osmType =
        when (type) {
            "N" -> OsmType.Node
            "W" -> OsmType.Way
            "R" -> OsmType.Relation
            else -> return null
        }
    return OsmId(osmType, id)
}

internal fun geoPointOrNull(
    lat: Double,
    lon: Double,
): GeoPoint? = if (lat in -90.0..90.0 && lon in -180.0..180.0) GeoPoint(lat, lon) else null

@Serializable
private data class PhotonResponse(
    val features: List<PhotonFeature> = emptyList(),
)

@Serializable
private data class PhotonFeature(
    val geometry: PhotonGeometry? = null,
    val properties: PhotonProperties = PhotonProperties(),
)

@Serializable
private data class PhotonGeometry(
    val coordinates: List<Double> = emptyList(),
)

@Serializable
private data class PhotonProperties(
    @SerialName("osm_type") val osmType: String? = null,
    @SerialName("osm_id") val osmId: Long? = null,
    @SerialName("osm_key") val osmKey: String? = null,
    @SerialName("osm_value") val osmValue: String? = null,
    val type: String? = null,
    val name: String? = null,
    val housenumber: String? = null,
    val street: String? = null,
    val locality: String? = null,
    val city: String? = null,
    val state: String? = null,
    val country: String? = null,
    val postcode: String? = null,
)
