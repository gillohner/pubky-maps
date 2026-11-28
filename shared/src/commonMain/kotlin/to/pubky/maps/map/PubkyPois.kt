package to.pubky.maps.map

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import to.pubky.maps.links.OsmId
import to.pubky.maps.links.OsmType
import to.pubky.maps.place.MapPlace
import to.pubky.maps.search.PlaceKind
import to.pubky.maps.search.osmIdFromUrl
import to.pubky.maps.search.placeKindOf

/**
 * From this zoom (the one the Pubky places are shown from) the base map's points of interest are
 * highlighted when they are Pubky places. The tiles hold only some points of interest before zoom
 * 14: OpenMapTiles' railway stations, halts and ferry terminals from 12, Shortbread's stops and
 * stations of its `public_transport` layer; those are highlighted, the other Pubky places stay dots
 * and clusters until the tiles hold them.
 */
const val POI_HIGHLIGHT_MIN_ZOOM = MapPlacesLayer.MIN_ZOOM

/**
 * From this zoom the highlights are placed before the base map's names of towns and suburbs as
 * before its other labels; zoomed out further, those names come first ([placeNamesLayerOf]), so a
 * highlighted station never hides the name of its town.
 */
const val POI_HIGHLIGHT_OVER_PLACE_NAMES_ZOOM = 14.0

/** Where a zoom is for the highlights: under [POI_HIGHLIGHT_MIN_ZOOM], under the place names, over them. */
enum class PoiZoom { None, UnderPlaceNames, OverPlaceNames }

/** The [PoiZoom] of [zoom]. */
fun poiZoomOf(zoom: Double): PoiZoom =
    when {
        zoom < POI_HIGHLIGHT_MIN_ZOOM -> PoiZoom.None
        zoom < POI_HIGHLIGHT_OVER_PLACE_NAMES_ZOOM -> PoiZoom.UnderPlaceNames
        else -> PoiZoom.OverPlaceNames
    }

/** A vector source of the base map and its source layer of points of interest. */
data class PoiSource(
    val source: String,
    val sourceLayer: String,
)

/**
 * A layer of the base style: its vector source and source layer (null for a background, raster or
 * GeoJSON layer), its id and its type (`symbol`, `fill`, ...).
 */
data class StyleLayerRef(
    val source: String?,
    val sourceLayer: String?,
    val id: String = "",
    val type: String = "",
)

/** The source layers of names of places (countries to neighbourhoods): OpenMapTiles' and Shortbread's. */
private val PlaceNameLayers = setOf("place", "place_labels")

/**
 * The id of the base style's lowest layer of place names ([layers] from the bottom up, as the style
 * lists them), the one the highlights go under below [POI_HIGHLIGHT_OVER_PLACE_NAMES_ZOOM]: every
 * place name is then placed before them. Null when the style draws no place names.
 */
fun placeNamesLayerOf(layers: List<StyleLayerRef>): String? =
    layers.firstOrNull { it.type == "symbol" && it.sourceLayer in PlaceNameLayers && it.id.isNotEmpty() }?.id

/**
 * The id of the base style's lowest label layer ([layers] from the bottom up): what the map draws
 * over the base map (the street-level photo lines) goes under it, so street and place names stay
 * readable. Null when the style has no labels.
 */
fun lowestLabelLayerOf(layers: List<StyleLayerRef>): String? = layers.firstOrNull { it.type == "symbol" && it.id.isNotEmpty() }?.id

/**
 * The base map's sources of points of interest. A source whose style draws its points of interest
 * ([PoiSourceLayers]) gives those layers; one that draws none (OpenFreeMap's dark style) gives its
 * schema's: `poi` for OpenMapTiles (it has `transportation`), `pois` for Shortbread (`streets`).
 */
fun poiSourcesOf(layers: List<StyleLayerRef>): List<PoiSource> =
    layers
        .filter { it.source != null && it.sourceLayer != null }
        .groupBy({ it.source!! }, { it.sourceLayer!! })
        .flatMap { (source, sourceLayers) ->
            val drawn = sourceLayers.filter { it in PoiSourceLayers }.distinct()
            val poiLayers =
                drawn.ifEmpty {
                    when {
                        "transportation" in sourceLayers -> listOf("poi")
                        "streets" in sourceLayers -> listOf("pois")
                        else -> emptyList()
                    }
                }
            poiLayers.map { PoiSource(source, it) }
        }

/**
 * The ids a base map tile gives [osm]'s feature (`osm id × 10 + kind`, see [osmIdOfTileFeature]): a
 * node is 1, or 0 when it is an aggregated transit stop.
 */
fun tileIdsOf(osm: OsmId): List<Long> =
    when (osm.type) {
        OsmType.Node -> listOf(osm.id * 10 + 1, osm.id * 10)
        OsmType.Way -> listOf(osm.id * 10 + 2)
        OsmType.Relation -> listOf(osm.id * 10 + 3)
    }

/** A tile feature's id as MapLibre gives it (a whole number, written as one or as a decimal); null without one. */
fun tileIdOf(id: JsonPrimitive?): Long? {
    val text = id?.contentOrNull ?: return null
    return text.toLongOrNull() ?: text.toDoubleOrNull()?.takeIf { it >= 0 && it == kotlin.math.floor(it) }?.toLong()
}

/** The tile ids of every place of [places] that is an OpenStreetMap element. */
fun tileIdsOf(places: List<MapPlace>): List<Long> = places.mapNotNull { osmIdFromUrl(it.uri) }.flatMap(::tileIdsOf).distinct()

/** A base map point of interest drawn highlighted: the Pubky place it is, its tile ids and its kind. */
data class PoiHighlight(
    val place: MapPlace,
    val tileIds: List<Long>,
    val kind: PlaceKind,
)

/** The Pubky places in view: those the base map has as a point of interest, highlighted, and the others, as dots. */
data class PubkyPois(
    val highlights: List<PoiHighlight> = emptyList(),
    val dots: List<MapPlace> = emptyList(),
) {
    /** The place whose highlight is the tile feature [tileId], or null. */
    fun placeOf(tileId: Long): MapPlace? = highlights.firstOrNull { tileId in it.tileIds }?.place
}

/**
 * Splits [places] by [found], the base map's features (by tile id, with their properties) that are
 * one of them: a place found there is highlighted, once, with the kind Pinakes gives it (its OSM
 * category, else its schema.org type), else the tile's; every other place stays a dot.
 */
fun pubkyPois(
    places: List<MapPlace>,
    found: Map<Long, Map<String, String>>,
): PubkyPois {
    if (found.isEmpty()) return PubkyPois(dots = places)
    val highlights = mutableListOf<PoiHighlight>()
    val dots = mutableListOf<MapPlace>()
    val seen = HashSet<OsmId>()
    for (place in places) {
        val osm = osmIdFromUrl(place.uri)
        val ids = osm?.let(::tileIdsOf).orEmpty().filter { it in found }
        when {
            ids.isEmpty() -> dots += place
            seen.add(osm!!) -> highlights += PoiHighlight(place, ids, place.kind() ?: kindOfTileFeature(found.getValue(ids.first())))
        }
    }
    return PubkyPois(highlights, dots)
}

/**
 * The kind Pinakes gives a place: its OSM category, or its schema.org type with its ancestors (a
 * `CafeOrCoffeeShop` is a `FoodEstablishment`); null when neither says. A place kept for offline use
 * has its type without the ancestors (the offline store keeps no type path), so only a type
 * [placeKindOf] lists itself gives it a kind; for the others the tile's kind is taken.
 */
private fun MapPlace.kind(): PlaceKind? {
    val fromCategory =
        category?.let { placeKindOf(it.substringBefore('/'), it.substringAfter('/', "").ifEmpty { null }, photonType = null) }
    val fromType = placeKindOf(typePath.ifEmpty { listOfNotNull(type) })
    return listOf(fromCategory, fromType).firstOrNull { it != null && it != PlaceKind.Other }
}

/**
 * The kind of a base map point of interest from its tile properties: Shortbread names it by its OSM
 * key (`amenity=cafe`), a stop of its `public_transport` layer by `kind` (`station`, `bus_stop`),
 * OpenMapTiles by `class` and `subclass`, the subclass being the OSM value.
 */
fun kindOfTileFeature(properties: Map<String, String>): PlaceKind {
    if (properties["kind"] in SHORTBREAD_TRANSIT_KINDS) return PlaceKind.Transit
    val subclass = properties["subclass"]
    val pairs =
        when {
            subclass == null -> SHORTBREAD_KEYS.mapNotNull { key -> properties[key]?.let { key to it } }

            subclass in OMT_SHOP_VALUES -> listOf("shop" to subclass)

            // Only keys whose kind depends on the value: a subclass names no key.
            else -> listOf("amenity", "tourism", "leisure", "railway", "highway", "aeroway").map { it to subclass }
        }
    return pairs.map { (key, value) -> placeKindOf(key, value, photonType = null) }.firstOrNull { it != PlaceKind.Other }
        ?: PlaceKind.Other
}

/** The kinds of Shortbread's `public_transport` layer. */
private val SHORTBREAD_TRANSIT_KINDS = setOf("station", "halt", "tram_stop", "bus_stop", "bus_station", "ferry_terminal", "aerodrome")

/** The OSM keys Shortbread's points of interest carry, in the order their kind is asked. */
private val SHORTBREAD_KEYS =
    listOf("amenity", "shop", "tourism", "leisure", "historic", "railway", "highway", "aeroway", "natural", "public_transport")

/**
 * The font of the highlighted names, out of the `text-font` values of the base style's symbol
 * layers (only fonts the style's glyph server has): a bold one, else a regular one, else the first.
 * Null when the style names no font: the highlights then draw no name.
 */
fun highlightFont(fonts: List<JsonElement?>): List<String>? {
    val stacks =
        fonts.mapNotNull { font ->
            val list =
                (font as? JsonArray)?.let {
                    if ((it.firstOrNull() as? JsonPrimitive)?.contentOrNull ==
                        "literal"
                    ) {
                        it.getOrNull(1)
                    } else {
                        it
                    }
                }
            (list as? JsonArray)
                ?.map { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content ?: return@mapNotNull null }
                ?.takeIf { it.isNotEmpty() }
        }
    return stacks.firstOrNull { stack -> stack.any { it.contains("bold", ignoreCase = true) } }
        ?: stacks.firstOrNull { stack -> stack.any { it.contains("regular", ignoreCase = true) } }
        ?: stacks.firstOrNull()
}
