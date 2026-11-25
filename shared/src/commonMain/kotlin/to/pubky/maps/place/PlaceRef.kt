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

    /** A Photon search result (an OpenStreetMap place or address) that Pinakes does not know. */
    Photon,

    /** Coordinates the user typed or pasted. */
    Coordinates,

    /** A place tapped on the base map (its OpenStreetMap element). */
    Map,
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
    /** True when the place is shown from its link alone because a network step failed. */
    val offline: Boolean = false,
    /** The link was a short link that could not be followed (the network failed). */
    val shortLinkUnfollowed: Boolean = false,
    /** [point] is only the centre of a map view or of a search, not the place itself. */
    val pointIsView: Boolean = false,
    /** The postal address, one line ("Heimplatz 1, 8001 Zürich"), when a source knows it. */
    val address: String? = null,
    /** What kind of place it is, in words ("Museum", "Street"), when a source says. */
    val category: String? = null,
) {
    /** True when [point] is the place, so the map pins it: not a map view, not a search near it. */
    val pinned: Boolean get() = point != null && !pointIsView && query == null

    /** True when the link only shows part of the map (no name, no search): the map moves, no card. */
    val viewOnly: Boolean get() = pointIsView && name == null && query == null

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
        shortLinkUnfollowed = shortLinkUnfollowed && !fromPinakes,
        pointIsView = pointIsView,
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
