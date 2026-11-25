package to.pubky.maps.map

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.maplibre.compose.expressions.dsl.and
import org.maplibre.compose.expressions.dsl.asBoolean
import org.maplibre.compose.expressions.dsl.asString
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.dsl.eq
import org.maplibre.compose.expressions.dsl.feature
import org.maplibre.compose.expressions.dsl.image
import org.maplibre.compose.expressions.dsl.not
import org.maplibre.compose.expressions.value.SymbolAnchor
import org.maplibre.compose.interaction.ClickEvent
import org.maplibre.compose.interaction.ClickResult
import org.maplibre.compose.layers.SymbolLayer
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.rememberGeoJsonSource
import org.maplibre.spatialk.geojson.Feature
import org.maplibre.spatialk.geojson.FeatureCollection
import org.maplibre.spatialk.geojson.Point
import org.maplibre.spatialk.geojson.Position
import to.pubky.maps.search.SearchResult
import to.pubky.maps.search.pinIcon

/** The red of search pins, as the link pin and Google Maps' and CoMaps' result pins. */
private val ResultPinColor = Color(0xFFD93025)

/** Size of a pin on the map; the picked one is drawn [SELECTED_SCALE] times as large. */
private val PinSize = DpSize(24.dp, 30.dp)
private const val SELECTED_SCALE = 1.4f

private const val INDEX = "i"
private const val KIND = "k"
private const val SELECTED = "s"

/**
 * The search results as pins, one per row of the list, each with its kind's icon; the picked result's
 * pin is larger and on top. A pin stands above its point (anchored at its tip), so the map's own label
 * of the place stays readable under it; the pins carry no text of their own, which also keeps them
 * independent of the fonts a map style serves. Tapping a pin picks its result.
 */
@Composable
internal fun SearchPins(
    results: List<SearchResult>,
    selected: SearchResult?,
    onSelect: (SearchResult) -> Unit,
) {
    val features =
        remember(results, selected) {
            FeatureCollection(
                results.mapIndexedNotNull { i, result ->
                    val point = result.place.point ?: return@mapIndexedNotNull null
                    Feature(
                        geometry = Point(Position(longitude = point.lon, latitude = point.lat)),
                        properties =
                            JsonObject(
                                mapOf(
                                    INDEX to JsonPrimitive(i),
                                    KIND to JsonPrimitive(result.kind.name),
                                    SELECTED to JsonPrimitive(result == selected),
                                ),
                            ),
                    )
                },
            )
        }
    val source = rememberGeoJsonSource(GeoJsonData.Features(features))
    val onClick: ClickEvent.(List<Feature<*, JsonObject?>>) -> ClickResult = { clicked ->
        val index = clicked.firstOrNull()?.properties?.get(INDEX)?.jsonPrimitive?.intOrNull
        val result = index?.let { results.getOrNull(it) }
        if (result != null) {
            onSelect(result)
            ClickResult.Consume
        } else {
            ClickResult.Pass
        }
    }
    // One layer per kind shown, each with its own icon; the picked pin last, so it is drawn on top.
    val kinds = remember(results) { results.map { it.kind }.distinct() }
    for (kind in kinds) {
        key(kind) {
            val painter = rememberVectorPainter(remember(kind) { pinIcon(kind, ResultPinColor) })
            SymbolLayer(
                id = "search-results-${kind.name.lowercase()}",
                source = source,
                filter = (feature[KIND].asString() eq const(kind.name)) and !feature[SELECTED].asBoolean(),
                iconImage = image(painter, PinSize),
                iconAnchor = const(SymbolAnchor.Bottom),
                iconAllowOverlap = const(true),
                onClick = onClick,
            )
        }
    }
    val pickedKind = selected?.kind
    if (pickedKind != null && selected.place.point != null) {
        key(pickedKind) {
            val painter = rememberVectorPainter(remember(pickedKind) { pinIcon(pickedKind, ResultPinColor) })
            SymbolLayer(
                id = "search-selected",
                source = source,
                filter = feature[SELECTED].asBoolean(),
                iconImage = image(painter, PinSize),
                iconSize = const(SELECTED_SCALE),
                iconAnchor = const(SymbolAnchor.Bottom),
                iconAllowOverlap = const(true),
                onClick = onClick,
            )
        }
    }
}
