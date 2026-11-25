package to.pubky.maps.links

import kotlinx.coroutines.CompletableDeferred

/**
 * A stand-in for the core: links are `<format>:<lat>,<lon>`, and directions exist where the core
 * builds them. [open] answers from [opened] (null: no map link), waiting for [gate] when set.
 */
class FakeMapLinks(
    var opened: (String) -> OpenedLink? = { null },
) : MapLinks {
    var gate: CompletableDeferred<Unit>? = null
    val asked = mutableListOf<Pair<String, List<String>>>()

    override fun containsLink(text: String): Boolean = opened(text) != null

    override suspend fun open(
        text: String,
        pinakesServers: List<String>,
    ): OpenedLink? {
        asked += text to pinakesServers
        gate?.await()
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
    offline: Boolean = false,
    provider: LinkProvider = LinkProvider.Geo,
    osm: OsmId? = null,
) = OpenedLink(
    sourceUri = source,
    provider = provider,
    name = name,
    point = point,
    osm = osm,
    zoom = null,
    query = null,
    fromPinakes = fromPinakes,
    offline = offline,
)
