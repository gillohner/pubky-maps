package to.pubky.maps.place

import to.pubky.maps.links.GeoPoint
import to.pubky.maps.links.OpenedLink
import to.pubky.maps.links.OsmId

/** Where a place shown on the map came from. */
enum class PlaceSource {
    /** A Pinakes card (from a link Pinakes knows). */
    Pinakes,

    /** The text of a map link alone: Pinakes did not know it or could not be reached. */
    LinkText,
}

/**
 * A place the map shows, as the rest of the app refers to it. [uri] is the place's URI in Pubky data:
 * for a place opened from a link, the link as the user had it.
 */
data class PlaceRef(
    val uri: String,
    val name: String?,
    val point: GeoPoint?,
    val osm: OsmId?,
    val source: PlaceSource,
    /** The zoom the place should be shown at, when its link asked for one. */
    val zoom: Double? = null,
    /** Search text, for a link that searches instead of naming a point. */
    val query: String? = null,
    /** True when the place may be less complete than it could be because a network step failed. */
    val offline: Boolean = false,
) {
    /** What the place is called on screen: its name, else the search text, else its coordinates. */
    val title: String
        get() = name ?: query ?: point?.let { "${formatCoordinate(it.lat)}, ${formatCoordinate(it.lon)}" } ?: uri
}

fun OpenedLink.toPlaceRef(): PlaceRef =
    PlaceRef(
        uri = sourceUri,
        name = name,
        point = point,
        osm = osm,
        source = if (fromPinakes) PlaceSource.Pinakes else PlaceSource.LinkText,
        zoom = zoom,
        query = query,
        offline = offline,
    )

/** At most 5 fraction digits (about 1 m), trailing zeros dropped. */
internal fun formatCoordinate(value: Double): String {
    val scaled = kotlin.math.round(value * 100_000).toLong()
    val negative = scaled < 0
    val abs = kotlin.math.abs(scaled)
    val whole = abs / 100_000
    val fraction = (abs % 100_000).toString().padStart(5, '0').trimEnd('0')
    val sign = if (negative && (whole != 0L || fraction.isNotEmpty())) "-" else ""
    return if (fraction.isEmpty()) "$sign$whole" else "$sign$whole.$fraction"
}
