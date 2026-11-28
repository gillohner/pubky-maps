package to.pubky.maps.place

import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import to.pubky.maps.links.GeoPoint
import to.pubky.maps.links.OpenInAction
import to.pubky.maps.links.OsmId
import to.pubky.maps.links.OsmType
import to.pubky.maps.map.MapPlacesLayer
import to.pubky.maps.map.StyleIcons
import to.pubky.maps.osm.NoteController
import to.pubky.maps.routing.DirectionsController

/**
 * Place pages as the app runs them: the page controller and the map's Pubky places layer, for the life
 * of the process (rotation keeps an open page), and Pinakes and Nexus through the user's server lists
 * ([data]) for keeping the offline areas' Pubky places, [directions] to the open place (null
 * where routing is not available), and [notes] to OpenStreetMap about a place's map problems (null
 * where there are none), and [streetPhotos] of the open place from Panoramax and the map's
 * [streetPhotoLayer] of where Panoramax has photos (null where there are none).
 */
class PlaceServices(
    val pages: PlacePageController,
    val layer: MapPlacesLayer,
    val data: PlaceData,
    val directions: DirectionsController? = null,
    val notes: NoteController? = null,
    /** The base map's own icons for the Pubky places it has as points of interest; without it they keep the kind's badge. */
    val styleIcons: StyleIcons? = null,
    val streetPhotos: StreetPhotosController? = null,
    val streetPhotoLayer: StreetPhotoLayer? = null,
)

/** What "Open in" offers for a place, and how an entry is opened; none where map links are missing. */
class OpenInApps(
    val actions: (PlaceRef) -> List<OpenInAction>,
    val open: (OpenInAction) -> Unit,
)

/** Keeps a place across activity recreation (a tapped point of interest stays open on rotation). */
val PlaceRefSaver: Saver<PlaceRef?, Any> =
    listSaver(
        save = { place ->
            if (place == null) {
                emptyList()
            } else {
                listOf(
                    place.uri,
                    place.name,
                    place.point?.lat,
                    place.point?.lon,
                    place.osm?.type?.name,
                    place.osm?.id,
                    place.source.name,
                    place.zoom,
                    place.address,
                    place.category,
                    place.locality,
                )
            }
        },
        restore = { saved ->
            if (saved.size < 10) {
                null
            } else {
                val lat = saved[2] as? Double
                val lon = saved[3] as? Double
                val osmType = (saved[4] as? String)?.let { name -> OsmType.entries.firstOrNull { it.name == name } }
                val osmId = saved[5] as? Long
                PlaceRef(
                    uri = saved[0] as String,
                    name = saved[1] as? String,
                    point = if (lat != null && lon != null) GeoPoint(lat, lon) else null,
                    osm = if (osmType != null && osmId != null) OsmId(osmType, osmId) else null,
                    source = PlaceSource.entries.firstOrNull { it.name == saved[6] } ?: PlaceSource.Map,
                    zoom = saved[7] as? Double,
                    address = saved[8] as? String,
                    category = saved[9] as? String,
                    locality = saved.getOrNull(10) as? String,
                )
            }
        },
    )
