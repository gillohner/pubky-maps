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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.transformLatest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import org.maplibre.compose.expressions.ast.Expression
import org.maplibre.compose.expressions.dsl.asString
import org.maplibre.compose.expressions.dsl.call
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.dsl.feature
import org.maplibre.compose.expressions.dsl.format
import org.maplibre.compose.expressions.dsl.image
import org.maplibre.compose.expressions.dsl.padding
import org.maplibre.compose.expressions.dsl.span
import org.maplibre.compose.expressions.dsl.textOffset
import org.maplibre.compose.expressions.dsl.verbatim
import org.maplibre.compose.expressions.value.BooleanValue
import org.maplibre.compose.expressions.value.ExpressionValue
import org.maplibre.compose.expressions.value.ImageValue
import org.maplibre.compose.expressions.value.StringValue
import org.maplibre.compose.expressions.value.SymbolAnchor
import org.maplibre.compose.interaction.ClickResult
import org.maplibre.compose.layers.Anchor
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
import to.pubky.maps.search.badgeIcon
import to.pubky.maps.search.ringIcon
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** The teal of Pubky places (as their dots); its lighter shade names them on a dark map. */
private val PubkyPoiColor = Color(0xFF00897B)
private val PubkyPoiNameLight = Color(0xFF00695C)
private val PubkyPoiNameDark = Color(0xFF4DB6AC)

/** The inside of a ring around the style's own icon on a dark map (white on a light one). */
private val PubkyPoiRingDark = Color(0xFF263238)

private val PubkyPoiSize = DpSize(24.dp, 24.dp)

/** How long a style whose files could not be read waits before its icons are asked again. */
private val STYLE_ICONS_RETRY = 30.seconds

/**
 * The base map's sources of points of interest, the font its labels use and its lowest layer of
 * place names ([placeNamesLayerOf]), read from the style at [styleUrl].
 */
internal data class PoiBase(
    val sources: List<PoiSource>,
    val font: List<String>?,
    val styleUrl: String?,
    val placeNames: String? = null,
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

    /** The base style's own icons of its points of interest, by source ([StyleIcons]); empty until read, or when it has none. */
    var icons by mutableStateOf<Map<PoiSource, PoiIcon>>(emptyMap())

    /** [places] split into highlighted points of interest and dots ([pubkyPois]). */
    fun split(places: List<MapPlace>): PubkyPois = if (base == null) PubkyPois(dots = places) else pubkyPois(places, found)
}

/**
 * Keeps [tiles] up to date for [places]: the style's sources each time a style from the list is in
 * place, and from [POI_HIGHLIGHT_MIN_ZOOM], each time the map is idle (its tiles drawn) or the places
 * change, which of the places the tiles hold as a point of interest. Below that zoom, or before a
 * style is in place, none (every place is a dot). Once a place is highlighted, the style's own icons
 * of its points of interest are read through [styleIcons] (none without it).
 */
@Composable
internal fun TrackPubkyPois(
    tiles: PoiTiles,
    mapState: MapState,
    places: List<MapPlace>,
    styleIcons: StyleIcons?,
) {
    LaunchedEffect(tiles, mapState) {
        // Keyed on the style too: another style (the other theme, the next server) has other sources.
        snapshotFlow {
            (mapState.style.loadState is StyleLoadState.Ready && mapState.style.baseStyle is BaseStyle.Uri) to
                mapState.style.baseStyle
        }.collectLatest { (ready, style) -> tiles.base = if (ready) mapState.poiBase((style as? BaseStyle.Uri)?.uri) else null }
    }
    val shown by rememberUpdatedState(places)
    val base = tiles.base
    LaunchedEffect(tiles, styleIcons) {
        if (styleIcons == null) {
            tiles.icons = emptyMap()
            return@LaunchedEffect
        }
        // By the style's URL too (the light and dark styles of one server can have the same sources),
        // the one its sources were read from.
        val asks =
            snapshotFlow {
                tiles.base?.let { base ->
                    base.styleUrl?.takeIf { base.sources.isNotEmpty() }?.let { IconsAsk(it, base.sources) }
                }
            }
        styleIconsOf(asks, snapshotFlow { tiles.found.isNotEmpty() }) { styleIcons.of(it.styleUrl, it.sources) }
            .collect { tiles.icons = it }
    }
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

/** Whose icons to read: the style at [styleUrl], for its sources of points of interest. */
internal data class IconsAsk(
    val styleUrl: String,
    val sources: List<PoiSource>,
)

/**
 * The base style's own icons for each of [asks] ([read]; null when its files could not be read):
 * none at once for a new ask (the badges draw meanwhile), and its icons once [highlighted] is
 * true, as most views highlight no place. Files that could not be read are asked again [retry]
 * later (still only for a map that highlights one); an ask's icons, once read, stay until the next.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun styleIconsOf(
    asks: Flow<IconsAsk?>,
    highlighted: Flow<Boolean>,
    retry: Duration = STYLE_ICONS_RETRY,
    read: suspend (IconsAsk) -> Map<PoiSource, PoiIcon>?,
): Flow<Map<PoiSource, PoiIcon>> =
    asks.distinctUntilChanged().transformLatest { ask ->
        emit(emptyMap())
        if (ask == null) return@transformLatest
        while (true) {
            highlighted.first { it }
            val icons = read(ask)
            if (icons != null) {
                emit(icons)
                return@transformLatest
            }
            delay(retry)
        }
    }

/** The base style's sources of points of interest and its labels' font; null while the map cannot tell. */
private suspend fun MapState.poiBase(styleUrl: String?): PoiBase? =
    try {
        val layers = style.layers.toList()
        val refs = layers.map { StyleLayerRef(it.source, it.sourceLayer, it.id, it.type) }
        val fonts = layers.filter { it.type == "symbol" }.map { it.getProperty("text-font") }
        PoiBase(poiSourcesOf(refs), highlightFont(fonts), styleUrl, placeNamesLayerOf(refs))
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
 * The base map's points of interest that are Pubky places, drawn over the base map's own, from
 * [POI_HIGHLIGHT_MIN_ZOOM] whatever the style's own zoom for that point is: the style's own icon of
 * the point ([icons]: OpenFreeMap's coloured one, VersaTiles' glyph in teal) in a teal ring, or where
 * the style has none (OpenFreeMap's dark style draws no points of interest, a value its sprite has
 * no image for) a teal badge with the kind's glyph; the name in teal under it. From
 * [POI_HIGHLIGHT_OVER_PLACE_NAMES_ZOOM] they are placed before every label of the base map, so a
 * label in their way gives way (the point's own icon and name among them); zoomed out further they
 * are drawn under the base map's place names ([PoiBase.placeNames]), which are placed first: the
 * names of towns and suburbs stay, a highlight's name in their way is left out. Every icon is drawn,
 * also where two meet; a name with no room is left out. Tapping one opens its place.
 */
@Composable
internal fun PubkyPoiHighlights(
    base: PoiBase,
    pois: PubkyPois,
    icons: Map<PoiSource, PoiIcon>,
    dark: Boolean,
    onSelect: (MapPlace) -> Unit,
) {
    val zoomedOut = HighlightBand("out", POI_HIGHLIGHT_MIN_ZOOM, POI_HIGHLIGHT_OVER_PLACE_NAMES_ZOOM)
    val near = HighlightBand("near", POI_HIGHLIGHT_OVER_PLACE_NAMES_ZOOM, MAX_ZOOM)
    val under = base.placeNames
    if (under != null) {
        // Keyed: another style has its place names in another layer.
        key(under) { Anchor.Below(under) { HighlightLayers(base, pois, icons, dark, onSelect, zoomedOut) } }
    } else {
        HighlightLayers(base, pois, icons, dark, onSelect, zoomedOut)
    }
    HighlightLayers(base, pois, icons, dark, onSelect, near)
}

/** The highest zoom MapLibre draws. */
private const val MAX_ZOOM = 24.0

/** The zooms one set of highlight layers is drawn at, from [minZoom] up to (without) [maxZoom]; [name] is in their ids. */
private data class HighlightBand(
    val name: String,
    val minZoom: Double,
    val maxZoom: Double,
)

/** The layers of [PubkyPoiHighlights] for the zooms of [band]. */
@Composable
private fun HighlightLayers(
    base: PoiBase,
    pois: PubkyPois,
    icons: Map<PoiSource, PoiIcon>,
    dark: Boolean,
    onSelect: (MapPlace) -> Unit,
    band: HighlightBand,
) {
    val byKind = remember(pois) { pois.highlights.groupBy { it.kind } }
    val allIds = remember(pois) { pois.highlights.flatMap { it.tileIds } }
    val select = { tileId: Long -> pois.placeOf(tileId)?.let(onSelect) ?: Unit }
    for ((index, poiSource) in base.sources.withIndex()) {
        key(poiSource) {
            // Null when the style has no such source (it changed under the read).
            val styleSource: Source? = getBaseSource(poiSource.source)
            val source = styleSource as? VectorSource
            val icon = icons[poiSource]
            val prefix = "pubky-pois-${band.name}-$index"
            if (source != null) {
                if (icon != null) {
                    val ringFill = if (dark) PubkyPoiRingDark else Color.White
                    val ring = rememberVectorPainter(remember(ringFill) { ringIcon(ringFill, PubkyPoiColor) })
                    // Under the icon; it takes no room, the icon above it takes the ring's.
                    SymbolLayer(
                        id = "$prefix-ring",
                        source = source,
                        sourceLayer = poiSource.sourceLayer,
                        minZoom = band.minZoom.toFloat(),
                        maxZoom = band.maxZoom.toFloat(),
                        filter = withStyleIcon(allIds, icon, has = true),
                        iconImage = image(ring, PubkyPoiSize),
                        iconAllowOverlap = const(true),
                        iconIgnorePlacement = const(true),
                        // The whole ring opens the place, as a badge does.
                        onClick = { clicked -> clickedPoi(clicked.firstOrNull()?.id, select) },
                    )
                    PubkyPoiLayer(
                        id = "$prefix-own",
                        source = source,
                        sourceLayer = poiSource.sourceLayer,
                        band = band,
                        filter = withStyleIcon(allIds, icon, has = true),
                        iconImage = image(expressionOf<StringValue>(icon.image)),
                        iconSize = icon.size,
                        font = base.font,
                        dark = dark,
                        onSelect = select,
                        // The ring's room (24 dp) around the smaller icon, and the 2 dp every icon keeps.
                        iconPadding = ((PubkyPoiSize.width.value - icon.dp) / 2 + 2).dp,
                    )
                }
                for ((kind, ofKind) in byKind) {
                    key(kind) {
                        val ids = ofKind.flatMap { it.tileIds }
                        val painter = rememberVectorPainter(remember(kind) { badgeIcon(kind, PubkyPoiColor) })
                        PubkyPoiLayer(
                            id = "$prefix-${kind.name.lowercase()}",
                            source = source,
                            sourceLayer = poiSource.sourceLayer,
                            band = band,
                            filter = if (icon != null) withStyleIcon(ids, icon, has = false) else idIn(ids),
                            iconImage = image(painter, PubkyPoiSize),
                            iconSize = 1f,
                            font = base.font,
                            dark = dark,
                            onSelect = select,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Whether a tile feature is one of [ids] and the style has ([has]) or has not an image of its own
 * for it ([PoiIcon.image] names one of [PoiIcon.known]).
 */
private fun withStyleIcon(
    ids: List<Long>,
    icon: PoiIcon,
    has: Boolean,
): Expression<BooleanValue> {
    val known = expr("in", icon.image, expr("literal", JsonArray(icon.known.map(::JsonPrimitive))))
    val idMatch = expr("match", expr("id"), JsonArray(ids.map(::JsonPrimitive)), true, false)
    return expressionOf(expr("all", idMatch, if (has) known else expr("!", known)))
}

/** A style's expression (a call or a string) as one of the map's. */
private fun <T : ExpressionValue> expressionOf(json: JsonElement): Expression<T> =
    if (json is JsonArray) {
        call((json[0] as JsonPrimitive).content, json.drop(1).map(::verbatim))
    } else {
        const((json as JsonPrimitive).content).cast()
    }

@Composable
private fun PubkyPoiLayer(
    id: String,
    source: VectorSource,
    sourceLayer: String,
    band: HighlightBand,
    filter: Expression<BooleanValue>,
    iconImage: Expression<ImageValue?>,
    iconSize: Float,
    font: List<String>?,
    dark: Boolean,
    onSelect: (Long) -> Unit,
    iconPadding: Dp = 2.dp,
) {
    SymbolLayer(
        id = id,
        source = source,
        sourceLayer = sourceLayer,
        minZoom = band.minZoom.toFloat(),
        maxZoom = band.maxZoom.toFloat(),
        filter = filter,
        iconImage = iconImage,
        iconSize = const(iconSize),
        // An SDF icon of the style (a bare glyph) in the Pubky teal; other icons keep their colours.
        iconColor = const(PubkyPoiColor),
        iconPadding = padding(iconPadding, iconPadding, iconPadding, iconPadding),
        // A highlighted place has no dot (pubkyPois.dots), so its badge is always drawn, also over
        // another Pubky badge it would collide with; it still takes its room from the labels drawn
        // after it, the base map's.
        iconAllowOverlap = const(true),
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
        onClick = { clicked -> clickedPoi(clicked.firstOrNull()?.id, onSelect) },
    )
}

/** Opens the highlight a tap hit, by its tile feature's id. */
private fun clickedPoi(
    id: JsonPrimitive?,
    onSelect: (Long) -> Unit,
): ClickResult {
    val tileId = tileIdOf(id) ?: return ClickResult.Pass
    onSelect(tileId)
    return ClickResult.Consume
}
