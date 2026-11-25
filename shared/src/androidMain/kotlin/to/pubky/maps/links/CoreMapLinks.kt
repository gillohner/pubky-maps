package to.pubky.maps.links

import to.pubky.maps.core.LinkPlace
import to.pubky.maps.core.LinkedPlace
import to.pubky.maps.core.MapApp
import to.pubky.maps.core.MapPoint
import to.pubky.maps.core.MapProvider
import to.pubky.maps.core.OsmElement
import to.pubky.maps.core.OsmElementRef
import to.pubky.maps.core.PubkyMapsCore
import to.pubky.maps.core.buildDirectionsLink
import to.pubky.maps.core.buildPlaceLink
import to.pubky.maps.core.findMapLink
import to.pubky.maps.core.TravelMode as CoreTravelMode

/**
 * [MapLinks] through the Rust core. Reading and building links are plain calls; only [open] needs
 * the core instance (for the network), which [core] provides once it is built.
 */
class CoreMapLinks(
    private val core: suspend () -> PubkyMapsCore,
) : MapLinks {
    override fun containsLink(text: String): Boolean = findMapLink(text) != null

    override suspend fun open(
        text: String,
        pinakesServers: List<String>,
    ): OpenedLink? {
        if (!containsLink(text)) return null
        return core().openMapLink(text, pinakesServers).toOpenedLink()
    }

    override fun placeLink(
        format: LinkFormat,
        place: LinkTarget,
    ): String? = buildPlaceLink(format.toCore(), place.toCore())

    override fun directionsLink(
        format: LinkFormat,
        place: LinkTarget,
        mode: TravelMode,
    ): String? = buildDirectionsLink(format.toCore(), place.toCore(), mode.toCore())
}

internal fun LinkedPlace.toOpenedLink(): OpenedLink =
    OpenedLink(
        sourceUri = sourceUri,
        provider = provider.toLinkProvider(),
        name = name,
        point = point?.let { GeoPoint(it.lat, it.lon) },
        osm = osm?.toOsmId(),
        zoom = zoom,
        query = query,
        fromPinakes = fromPinakes,
        offline = offline,
    )

private fun MapProvider.toLinkProvider(): LinkProvider =
    when (this) {
        MapProvider.GEO -> LinkProvider.Geo
        MapProvider.GOOGLE -> LinkProvider.Google
        MapProvider.APPLE -> LinkProvider.Apple
        MapProvider.WAZE -> LinkProvider.Waze
        MapProvider.OPEN_STREET_MAP -> LinkProvider.OpenStreetMap
        MapProvider.OSM_AND -> LinkProvider.OsmAnd
        MapProvider.ORGANIC_MAPS -> LinkProvider.OrganicMaps
        MapProvider.CO_MAPS -> LinkProvider.CoMaps
        MapProvider.HERE -> LinkProvider.Here
    }

private fun OsmElementRef.toOsmId(): OsmId =
    OsmId(
        type =
            when (element) {
                OsmElement.NODE -> OsmType.Node
                OsmElement.WAY -> OsmType.Way
                OsmElement.RELATION -> OsmType.Relation
            },
        id = id.toLong(),
    )

private fun OsmId.toCore(): OsmElementRef =
    OsmElementRef(
        element =
            when (type) {
                OsmType.Node -> OsmElement.NODE
                OsmType.Way -> OsmElement.WAY
                OsmType.Relation -> OsmElement.RELATION
            },
        id = id.toULong(),
    )

private fun LinkTarget.toCore(): LinkPlace =
    LinkPlace(point = MapPoint(point.lat, point.lon), name = name, osm = osm?.toCore(), zoom = zoom)

private fun LinkFormat.toCore(): MapApp =
    when (this) {
        LinkFormat.GoogleMaps -> MapApp.GOOGLE_MAPS
        LinkFormat.GoogleNavigation -> MapApp.GOOGLE_NAVIGATION
        LinkFormat.AppleMaps -> MapApp.APPLE_MAPS
        LinkFormat.Waze -> MapApp.WAZE
        LinkFormat.OsmAnd -> MapApp.OSM_AND
        LinkFormat.OrganicMaps -> MapApp.ORGANIC_MAPS
        LinkFormat.OrganicMapsWeb -> MapApp.ORGANIC_MAPS_WEB
        LinkFormat.CoMaps -> MapApp.CO_MAPS
        LinkFormat.CoMapsWeb -> MapApp.CO_MAPS_WEB
        LinkFormat.HereWeGo -> MapApp.HERE_WE_GO
        LinkFormat.OpenStreetMap -> MapApp.OPEN_STREET_MAP
        LinkFormat.Geo -> MapApp.GEO
    }

private fun TravelMode.toCore(): CoreTravelMode =
    when (this) {
        TravelMode.Driving -> CoreTravelMode.DRIVING
        TravelMode.Walking -> CoreTravelMode.WALKING
        TravelMode.Cycling -> CoreTravelMode.CYCLING
        TravelMode.Transit -> CoreTravelMode.TRANSIT
    }
