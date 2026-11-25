package to.pubky.maps.map

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import to.pubky.maps.links.GeoPoint
import to.pubky.maps.links.OsmId
import to.pubky.maps.links.OsmType
import to.pubky.maps.place.GeoBox
import to.pubky.maps.place.MapPlace
import to.pubky.maps.place.PlaceData
import to.pubky.maps.place.PlaceRef
import to.pubky.maps.place.PlaceSource
import to.pubky.maps.search.SourceAnswer
import to.pubky.maps.search.categoryOf
import to.pubky.maps.search.osmIdFromUrl
import to.pubky.maps.search.schemaTypeLabel
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** The places Pinakes holds in the area last asked for. */
data class MapPlacesState(
    val places: List<MapPlace> = emptyList(),
    /** The area they are for; null before the first answer. */
    val area: GeoBox? = null,
    /** Pinakes had more places in the area than the cap. */
    val truncated: Boolean = false,
    /** The map is zoomed out too far to show them. */
    val hidden: Boolean = true,
)

/**
 * The map's Pubky places layer: the places people referenced on Pubky in view, from Pinakes'
 * `search?bbox=&type=Place`, until Pinakes has a cluster endpoint. Asked when the camera stops, from
 * zoom [MIN_ZOOM] in, for the view grown by half on each side (so small pans need no request), at
 * most [MAX_PAGES] pages of 500. Asked again when the view leaves that area, when it was cut at the
 * cap and the user zoomed in two levels, or after [maxAge]. A failure keeps what is shown.
 */
class MapPlacesLayer(
    private val data: PlaceData,
    private val scope: CoroutineScope,
    private val clock: Clock = Clock.System,
    private val maxAge: Duration = 5.minutes,
) {
    private val _state = MutableStateFlow(MapPlacesState())
    val state: StateFlow<MapPlacesState> = _state.asStateFlow()

    private var job: Job? = null
    private var asked: Asked? = null

    private data class Asked(
        val area: GeoBox,
        val zoom: Double,
        val at: Instant,
        val truncated: Boolean,
    )

    /** The camera stopped on [view] at [zoom]. */
    fun onCameraIdle(
        view: GeoBox,
        zoom: Double,
    ) {
        if (zoom < MIN_ZOOM) {
            _state.value = _state.value.copy(hidden = true)
            return
        }
        _state.value = _state.value.copy(hidden = false)
        val last = asked
        val fresh =
            last != null &&
                last.area.contains(view) &&
                clock.now() - last.at < maxAge &&
                !(last.truncated && zoom >= last.zoom + 2)
        if (fresh) return
        val area = view.expandedBy(0.5)
        asked = Asked(area, zoom, clock.now(), truncated = false)
        job?.cancel()
        job =
            scope.launch {
                val places = mutableListOf<MapPlace>()
                var cursor: String? = null
                var pages = 0
                do {
                    when (val answer = data.placesIn(area, cursor)) {
                        is SourceAnswer.Answered -> {
                            places += answer.value.places
                            cursor = answer.value.cursor
                        }

                        SourceAnswer.Unreachable -> {
                            // Asked again on the next stop of the camera.
                            asked = null
                            return@launch
                        }
                    }
                    pages++
                } while (cursor != null && pages < MAX_PAGES)
                val truncated = cursor != null
                asked = asked?.copy(truncated = truncated)
                _state.value = MapPlacesState(places.distinctBy { it.uri }, area, truncated, hidden = _state.value.hidden)
            }
    }

    companion object {
        /** Below this zoom an area holds too many places for a request; a cluster endpoint will lift it. */
        const val MIN_ZOOM = 12.0

        /** Up to 1,000 places: enough for a city centre, cheap for Pinakes' 120 requests a minute. */
        const val MAX_PAGES = 2
    }
}

/**
 * The map's view as a box on the globe. MapLibre gives longitudes of the world copy in view (they can
 * pass ±180); they are wrapped back. A view of the whole width is the whole width; a view across the
 * antimeridian has no single box and is null.
 */
fun viewBox(
    west: Double,
    south: Double,
    east: Double,
    north: Double,
): GeoBox? {
    val lat = { v: Double -> v.coerceIn(-90.0, 90.0) }
    if (east - west >= 360.0) return GeoBox(-180.0, lat(south), 180.0, lat(north))
    val wrap = { v: Double -> ((v + 180.0) % 360.0 + 360.0) % 360.0 - 180.0 }
    val w = wrap(west)
    val e = wrap(east).let { if (it == -180.0 && east > west) 180.0 else it }
    if (w > e) return null
    return GeoBox(w, lat(south), e, lat(north))
}

/** The place page reference of a Pubky places layer pin. */
fun MapPlace.toPlaceRef(): PlaceRef =
    PlaceRef(
        uri = uri,
        name = name,
        point = GeoPoint(lat, lon),
        osm = osmIdFromUrl(uri),
        source = PlaceSource.Pinakes,
        category =
            category?.let { categoryOf(it.substringBefore('/'), it.substringAfter('/', "").ifEmpty { null }, photonType = null) }
                ?: type?.let(::schemaTypeLabel),
    )

/**
 * The OpenStreetMap element of a base map feature. OpenFreeMap's OpenMapTiles and VersaTiles'
 * Shortbread tiles (both built with Planetiler) give a feature the id `osm id × 10 + kind`: 1 node,
 * 2 way, 3 relation; 0 on aggregated transit stops, which are nodes. Checked against OpenStreetMap
 * on Zürich's tiles.
 */
fun osmIdOfTileFeature(id: Long): OsmId? {
    if (id < 10) return null
    val osm = id / 10
    val type =
        when (id % 10) {
            0L, 1L -> OsmType.Node
            2L -> OsmType.Way
            3L -> OsmType.Relation
            else -> return null
        }
    return OsmId(type, osm)
}

/** The source layers of points of interest in the base map schemas the app's styles use. */
val PoiSourceLayers = setOf("poi", "pois", "public_transport")

/**
 * The place page reference of a tapped base map point of interest: its OSM element, the name it
 * shows on the map, and its kind from the tile's `class`/`subclass` (OpenMapTiles) or tag keys
 * (Shortbread). Null without an OSM id or a name.
 */
fun tappedPlace(
    id: Long?,
    properties: Map<String, String>,
    point: GeoPoint,
): PlaceRef? {
    val osm = id?.let(::osmIdOfTileFeature) ?: return null
    val name = (properties["name"] ?: properties["name_int"])?.trim()?.ifEmpty { null } ?: return null
    val category =
        properties["subclass"]?.let { categoryOf(properties["class"] ?: "amenity", it, photonType = null) }
            ?: listOf("amenity", "shop", "tourism", "leisure", "historic", "office", "man_made")
                .firstNotNullOfOrNull { key -> properties[key]?.let { categoryOf(key, it, photonType = null) } }
            ?: properties["kind"]?.let { categoryOf("place", it, photonType = null) }
    return PlaceRef(uri = osm.url, name = name, point = point, osm = osm, source = PlaceSource.Map, category = category)
}
