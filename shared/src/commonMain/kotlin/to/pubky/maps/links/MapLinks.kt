package to.pubky.maps.links

/** A WGS84 point. */
data class GeoPoint(
    val lat: Double,
    val lon: Double,
) {
    init {
        require(lat in -90.0..90.0 && lon in -180.0..180.0) { "Point out of range: $lat, $lon" }
    }
}

enum class OsmType { Node, Way, Relation }

/** An OpenStreetMap element. */
data class OsmId(
    val type: OsmType,
    val id: Long,
) {
    /** The canonical element URL. */
    val url: String get() = "https://www.openstreetmap.org/${type.name.lowercase()}/$id"
}

/** The app or format a link came from. */
enum class LinkProvider { Geo, Google, Apple, Waze, OpenStreetMap, OsmAnd, OrganicMaps, CoMaps, Here }

/** A place opened from a link: what the core read from the link, and from Pinakes when it knows the place. */
data class OpenedLink(
    /** The link as the user had it; tags and posts go on it, so it is never rewritten. */
    val sourceUri: String,
    val provider: LinkProvider,
    val name: String?,
    val point: GeoPoint?,
    /** [point] is only the centre of a map view, not a place: the map moves there and pins nothing. */
    val pointIsView: Boolean,
    val osm: OsmId?,
    val zoom: Double?,
    /** Search text, for a link that searches (an address or a name) instead of naming a point. */
    val query: String?,
    val fromPinakes: Boolean,
    /** A short link that could not be followed because the network failed. */
    val shortLinkUnfollowed: Boolean = false,
    /** Pinakes servers were asked and none answered. */
    val pinakesUnreachable: Boolean = false,
    /** The core could not be built, so neither the short link nor Pinakes was tried. */
    val coreUnavailable: Boolean = false,
) {
    /** Shown from the link text alone because a network step failed. */
    val offline: Boolean get() = !fromPinakes && (shortLinkUnfollowed || pinakesUnreachable || coreUnavailable)
}

/** A link format of another app (see the core's `map-links` crate for each format). */
enum class LinkFormat {
    GoogleMaps,
    GoogleNavigation,
    AppleMaps,
    Waze,
    OsmAnd,
    OrganicMaps,
    OrganicMapsWeb,
    CoMaps,
    CoMapsWeb,
    HereWeGo,
    OpenStreetMap,
    Geo,
}

enum class TravelMode { Driving, Walking, Cycling, Transit }

/** A place to build a link for. */
data class LinkTarget(
    val point: GeoPoint,
    val name: String?,
    val osm: OsmId? = null,
    val zoom: Double? = null,
)

/**
 * Map links, read and built by the Rust core. [open] uses the network (short links, Pinakes); the
 * rest is pure and fast.
 */
interface MapLinks {
    /**
     * The place the map link in [text] names, from the text alone: no network and no core instance, so
     * it can be shown at once. Null when [text] holds no map link.
     */
    fun read(text: String): OpenedLink?

    /**
     * Opens the map link in [text] (a link, or shared text holding one): follows a short link, asks the
     * [pinakesServers] in order, and falls back to the link text. Null when [text] holds no map link.
     * Throws when the core cannot be built.
     */
    suspend fun open(
        text: String,
        pinakesServers: List<String>,
    ): OpenedLink?

    /** The link that shows [place] in [format], or null when that app has none. */
    fun placeLink(
        format: LinkFormat,
        place: LinkTarget,
    ): String?

    /** The link that starts directions to [place] in [format], or null when that app has none. */
    fun directionsLink(
        format: LinkFormat,
        place: LinkTarget,
        mode: TravelMode,
    ): String?
}
