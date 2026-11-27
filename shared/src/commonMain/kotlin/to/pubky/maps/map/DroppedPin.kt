package to.pubky.maps.map

import to.pubky.maps.links.GeoPoint
import to.pubky.maps.place.PlaceRef
import to.pubky.maps.place.PlaceSource
import to.pubky.maps.place.formatCoordinate

/** What the page of a dropped pin says the place is, where a place's kind goes. */
const val DROPPED_PIN = "Dropped pin"

/**
 * The page of a point the user long-pressed on the map, as Google Maps, CoMaps and OsmAnd drop a pin
 * anywhere: named by its coordinates, its kind [DROPPED_PIN]. A point, not a place of any source, so
 * it is [PlaceSource.Coordinates] as typed coordinates are: it is asked of no server (a long-press can
 * be the user's home), the downloaded areas give its district and town, and Directions route to it.
 */
fun droppedPin(
    latitude: Double,
    longitude: Double,
): PlaceRef {
    val point = mapPoint(latitude, longitude)
    return PlaceRef(
        uri = "geo:${formatCoordinate(point.lat)},${formatCoordinate(point.lon)}",
        name = null,
        point = point,
        osm = null,
        source = PlaceSource.Coordinates,
        category = DROPPED_PIN,
    )
}

/**
 * A point of the map as a place's point: the map repeats sideways, so a longitude past the
 * antimeridian is wrapped into -180..180, and the latitude is kept in -90..90.
 */
fun mapPoint(
    latitude: Double,
    longitude: Double,
): GeoPoint {
    // Only a longitude out of range is moved, so a point on the map's first world stays exactly as given.
    val wrapped = if (longitude in -180.0..180.0) longitude else ((longitude + 180.0) % 360.0 + 360.0) % 360.0 - 180.0
    return GeoPoint(latitude.coerceIn(-90.0, 90.0), wrapped)
}
