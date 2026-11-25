package to.pubky.maps.links

import kotlinx.coroutines.CompletableDeferred

/**
 * A stand-in for the core: links are `<format>:<lat>,<lon>`, and directions exist where the core
 * builds them. [open] answers from [opened] (null: no map link), waiting for [gate] when set, or
 * throws when [coreFails]. [read] answers from [fromText], by default [opened] without what only the
 * network adds.
 */
class FakeMapLinks(
    var opened: (String) -> OpenedLink? = { null },
) : MapLinks {
    var gate: CompletableDeferred<Unit>? = null
    var coreFails = false
    var fromText: (String) -> OpenedLink? = { text ->
        opened(text)?.copy(fromPinakes = false, shortLinkUnfollowed = false, pinakesUnreachable = false)
    }
    val asked = mutableListOf<Pair<String, List<String>>>()

    override fun read(text: String): OpenedLink? = fromText(text)

    override suspend fun open(
        text: String,
        pinakesServers: List<String>,
    ): OpenedLink? {
        asked += text to pinakesServers
        gate?.await()
        if (coreFails) error("the core could not be built")
        return opened(text)
    }

    override fun placeLink(
        format: LinkFormat,
        place: LinkTarget,
    ): String = "$format:${place.point.lat},${place.point.lon}"

    override fun directionsLink(
        format: LinkFormat,
        place: LinkTarget,
        mode: TravelMode,
    ): String? =
        when (format) {
            LinkFormat.OsmAnd, LinkFormat.OrganicMaps, LinkFormat.OrganicMapsWeb, LinkFormat.CoMaps,
            LinkFormat.CoMapsWeb, LinkFormat.Geo,
            -> null

            LinkFormat.Waze -> if (mode == TravelMode.Driving) "Waze-dir:${place.point.lat}" else null

            else -> "$format-dir:${place.point.lat},${place.point.lon}"
        }
}

fun openedLink(
    source: String = "geo:47.37,8.54?q=47.37,8.54(Test)",
    name: String? = "Test",
    point: GeoPoint? = GeoPoint(47.37, 8.54),
    fromPinakes: Boolean = false,
    pinakesUnreachable: Boolean = false,
    provider: LinkProvider = LinkProvider.Geo,
    osm: OsmId? = null,
    pointIsView: Boolean = false,
    query: String? = null,
    shortLinkUnfollowed: Boolean = false,
) = OpenedLink(
    sourceUri = source,
    provider = provider,
    name = name,
    point = point,
    pointIsView = pointIsView,
    osm = osm,
    zoom = null,
    query = query,
    fromPinakes = fromPinakes,
    shortLinkUnfollowed = shortLinkUnfollowed,
    pinakesUnreachable = pinakesUnreachable,
)
