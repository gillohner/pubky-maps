package to.pubky.maps.map

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.onStart
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import org.maplibre.compose.expressions.ast.Expression
import org.maplibre.compose.expressions.dsl.asString
import org.maplibre.compose.expressions.dsl.call
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.dsl.feature
import org.maplibre.compose.expressions.dsl.format
import org.maplibre.compose.expressions.dsl.image
import org.maplibre.compose.expressions.dsl.span
import org.maplibre.compose.expressions.dsl.textOffset
import org.maplibre.compose.expressions.dsl.verbatim
import org.maplibre.compose.expressions.value.BooleanValue
import org.maplibre.compose.expressions.value.SymbolAnchor
import org.maplibre.compose.interaction.ClickResult
import org.maplibre.compose.layers.SymbolLayer
import org.maplibre.compose.map.MapEvent
import org.maplibre.compose.map.MapState
import org.maplibre.compose.map.StyleLoadState
import org.maplibre.compose.sources.Source
import org.maplibre.compose.sources.VectorSource
import org.maplibre.compose.sources.VectorTileSourceHandle
import org.maplibre.compose.sources.getBaseSource
import org.maplibre.compose.style.BaseStyle
import to.pubky.maps.place.MapPlace
import to.pubky.maps.search.PlaceKind
import to.pubky.maps.search.badgeIcon

/** The teal of Pubky places (as their dots); its lighter shade names them on a dark map. */
private val PubkyPoiColor = Color(0xFF00897B)
private val PubkyPoiNameLight = Color(0xFF00695C)
private val PubkyPoiNameDark = Color(0xFF4DB6AC)

private val PubkyPoiSize = DpSize(24.dp, 24.dp)

/** The base map's sources of points of interest and the font its labels use. */
internal data class PoiBase(
    val sources: List<PoiSource>,
    val font: List<String>?,
)

/**
 * What the base map's tiles say of the Pubky places: the style's sources of points of interest
 * ([base]) and which places they hold as one ([found], by tile id). Made before the map, read by its
 * layers ([split]), filled by [TrackPubkyPois] once the map is there.
 */
@Stable
internal class PoiTiles {
    var base by mutableStateOf<PoiBase?>(null)
    var found by mutableStateOf<Map<Long, Map<String, String>>>(emptyMap())

    /** [places] split into highlighted points of interest and dots ([pubkyPois]). */
    fun split(places: List<MapPlace>): PubkyPois = if (base == null) PubkyPois(dots = places) else pubkyPois(places, found)
}

/**
 * Keeps [tiles] up to date for [places]: the style's sources each time a style from the list is in
 * place, and from [POI_HIGHLIGHT_MIN_ZOOM], each time the map is idle (its tiles drawn) or the places
 * change, which of the places the tiles hold as a point of interest. Below that zoom, or before a
 * style is in place, none (every place is a dot).
 */
@Composable
internal fun TrackPubkyPois(
    tiles: PoiTiles,
    mapState: MapState,
    places: List<MapPlace>,
) {
    LaunchedEffect(tiles, mapState) {
        // Keyed on the style too: another style (the other theme, the next server) has other sources.
        snapshotFlow {
            (mapState.style.loadState is StyleLoadState.Ready && mapState.style.baseStyle is BaseStyle.Uri) to
                mapState.style.baseStyle
        }.collectLatest { (ready, _) -> tiles.base = if (ready) mapState.poiBase() else null }
    }
    val shown by rememberUpdatedState(places)
    val base = tiles.base
    LaunchedEffect(tiles, mapState, base) {
        if (base == null || base.sources.isEmpty()) {
            tiles.found = emptyMap()
            return@LaunchedEffect
        }
        val idle = mapState.events.filter { it == MapEvent.Idle }.onStart { emit(MapEvent.Idle) }
        // Crossing the zoom also asks at once: zoomed out under it mid-gesture, the highlights hide and
        // their places are dots again before the map is next idle.
        val near = snapshotFlow { mapState.cameraPosition.zoom >= POI_HIGHLIGHT_MIN_ZOOM }.distinctUntilChanged()
        combine(idle, near, snapshotFlow { shown }) { _, isNear, now -> isNear to now }
            .collectLatest { (isNear, now) ->
                tiles.found = if (isNear) mapState.poisAmong(base.sources, now) else emptyMap()
            }
    }
}

/** The base style's sources of points of interest and its labels' font; null while the map cannot tell. */
private suspend fun MapState.poiBase(): PoiBase? =
    try {
        val layers = style.layers.toList()
        val sources = poiSourcesOf(layers.map { StyleLayerRef(it.source, it.sourceLayer) })
        val fonts = layers.filter { it.type == "symbol" }.map { it.getProperty("text-font") }
        PoiBase(sources, highlightFont(fonts))
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        // A style changing under the read: read again when it is ready.
        null
    }

/** The base map's points of interest among [places], by tile id with their properties; empty when the map cannot tell. */
private suspend fun MapState.poisAmong(
    sources: List<PoiSource>,
    places: List<MapPlace>,
): Map<Long, Map<String, String>> {
    val ids = tileIdsOf(places)
    if (ids.isEmpty()) return emptyMap()
    val found = HashMap<Long, Map<String, String>>()
    for (poiSource in sources) {
        val handle =
            try {
                style.sources[poiSource.source] as? VectorTileSourceHandle
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            } ?: continue
        val features =
            try {
                handle.querySourceFeatures(setOf(poiSource.sourceLayer), idIn(ids))
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                continue
            }
        for (feature in features) {
            val id = tileIdOf(feature.id) ?: continue
            if (id in found) continue
            found[id] = feature.properties.strings()
        }
    }
    return found
}

/** Whether a tile feature's id is one of [ids]. */
private fun idIn(ids: List<Long>): Expression<BooleanValue> =
    call("match", feature.id(), verbatim(JsonArray(ids.map(::JsonPrimitive))), const(true), const(false))

/**
 * The base map's points of interest that are Pubky places, drawn over the base map's own: a teal
 * badge with the kind's glyph and the name in teal under it, from [POI_HIGHLIGHT_MIN_ZOOM] whatever
 * the style's own zoom for that point is. They are placed before every label of the base map, so a
 * label in their way gives way (the point's own icon and name among them). Tapping one opens its place.
 */
@Composable
internal fun PubkyPoiHighlights(
    base: PoiBase,
    pois: PubkyPois,
    dark: Boolean,
    onSelect: (MapPlace) -> Unit,
) {
    val byKind = remember(pois) { pois.highlights.groupBy { it.kind } }
    for ((index, poiSource) in base.sources.withIndex()) {
        key(poiSource) {
            // Null when the style has no such source (it changed under the read).
            val styleSource: Source? = getBaseSource(poiSource.source)
            val source = styleSource as? VectorSource
            if (source != null) {
                for ((kind, ofKind) in byKind) {
                    key(kind) {
                        PubkyPoiLayer(
                            id = "pubky-pois-$index-${kind.name.lowercase()}",
                            source = source,
                            sourceLayer = poiSource.sourceLayer,
                            kind = kind,
                            ids = ofKind.flatMap { it.tileIds },
                            font = base.font,
                            dark = dark,
                            onSelect = { tileId -> pois.placeOf(tileId)?.let(onSelect) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PubkyPoiLayer(
    id: String,
    source: VectorSource,
    sourceLayer: String,
    kind: PlaceKind,
    ids: List<Long>,
    font: List<String>?,
    dark: Boolean,
    onSelect: (Long) -> Unit,
) {
    val painter = rememberVectorPainter(remember(kind) { badgeIcon(kind, PubkyPoiColor) })
    SymbolLayer(
        id = id,
        source = source,
        sourceLayer = sourceLayer,
        minZoom = POI_HIGHLIGHT_MIN_ZOOM.toFloat(),
        filter = idIn(ids),
        iconImage = image(painter, PubkyPoiSize),
        // Without a font of the style's, the badge draws no name and leaves the map's own in place.
        iconIgnorePlacement = const(font == null),
        textField = format(span(if (font != null) feature["name"].asString(const("")) else const(""))),
        // Asked of the glyph server only for a name to draw.
        textFont = const(font ?: listOf("Noto Sans Regular")),
        textSize = const(13.sp),
        textColor = const(if (dark) PubkyPoiNameDark else PubkyPoiNameLight),
        textHaloColor = const(if (dark) Color(0xFF121212) else Color.White),
        textHaloWidth = const(1.5.dp),
        textAnchor = const(SymbolAnchor.Top),
        textOffset = textOffset(0.em, 1.1.em),
        // A name with no room leaves the badge on the map.
        textOptional = const(true),
        onClick = { clicked ->
            val tileId = tileIdOf(clicked.firstOrNull()?.id)
            if (tileId != null) {
                onSelect(tileId)
                ClickResult.Consume
            } else {
                ClickResult.Pass
            }
        },
    )
}
