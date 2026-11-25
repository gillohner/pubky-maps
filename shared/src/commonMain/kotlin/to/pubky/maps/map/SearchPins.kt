package to.pubky.maps.map

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.maplibre.compose.expressions.ast.Expression
import org.maplibre.compose.expressions.dsl.asBoolean
import org.maplibre.compose.expressions.dsl.asString
import org.maplibre.compose.expressions.dsl.case
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.dsl.feature
import org.maplibre.compose.expressions.dsl.format
import org.maplibre.compose.expressions.dsl.image
import org.maplibre.compose.expressions.dsl.not
import org.maplibre.compose.expressions.dsl.span
import org.maplibre.compose.expressions.dsl.switch
import org.maplibre.compose.expressions.value.ImageValue
import org.maplibre.compose.expressions.value.SymbolAnchor
import org.maplibre.compose.interaction.ClickResult
import org.maplibre.compose.layers.SymbolLayer
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.rememberGeoJsonSource
import org.maplibre.spatialk.geojson.Feature
import org.maplibre.spatialk.geojson.FeatureCollection
import org.maplibre.spatialk.geojson.Point
import org.maplibre.spatialk.geojson.Position
import to.pubky.maps.search.PlaceKind
import to.pubky.maps.search.SearchResult
import to.pubky.maps.search.pinIcon

/** The red of search pins, as the link pin and Google Maps' and CoMaps' result pins. */
private val ResultPinColor = Color(0xFFD93025)

/** Size of a pin on the map; the picked one is drawn [SELECTED_SCALE] times as large. */
private val PinSize = DpSize(24.dp, 30.dp)
private const val SELECTED_SCALE = 1.4f

/** Only the best results get their name beside the pin, as in Google Maps; the rest are pins. */
private const val LABELLED_RESULTS = 5

private const val INDEX = "i"
private const val KIND = "k"
private const val NAME = "n"
private const val SELECTED = "s"

/**
 * The search results as pins, one per row of the list, each with its kind's icon; the best results
 * show their name under the pin, and the picked result's pin is larger. The pin stands above its
 * point, so the map's own label of the place stays readable. Tapping a pin picks its result.
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
                    val picked = result == selected
                    Feature(
                        geometry = Point(Position(longitude = point.lon, latitude = point.lat)),
                        properties =
                            JsonObject(
                                mapOf(
                                    INDEX to JsonPrimitive(i),
                                    KIND to JsonPrimitive(result.kind.name),
                                    NAME to JsonPrimitive(if (picked || i < LABELLED_RESULTS) result.place.title else ""),
                                    SELECTED to JsonPrimitive(picked),
                                ),
                            ),
                    )
                },
            )
        }
    val source = rememberGeoJsonSource(GeoJsonData.Features(features))
    val dark = isSystemInDarkTheme()
    val pins = PlaceKind.entries.map { kind -> kind to rememberVectorPainter(remember(kind) { pinIcon(kind, ResultPinColor) }) }
    val icon: Expression<ImageValue> =
        switch(
            feature[KIND].asString(),
            *pins.map { (kind, painter) -> case(kind.name, image(painter, PinSize)) }.toTypedArray(),
            fallback = image(pins.first { it.first == PlaceKind.Other }.second, PinSize),
        )
    val onClick: org.maplibre.compose.interaction.ClickEvent.(List<Feature<*, JsonObject?>>) -> ClickResult = { clicked ->
        val index =
            clicked
                .firstOrNull()
                ?.properties
                ?.get(INDEX)
                ?.jsonPrimitive
                ?.intOrNull
        val result = index?.let { results.getOrNull(it) }
        if (result != null) {
            onSelect(result)
            ClickResult.Consume
        } else {
            ClickResult.Pass
        }
    }
    for (picked in listOf(false, true)) {
        SymbolLayer(
            id = if (picked) "search-selected" else "search-results",
            source = source,
            filter = if (picked) feature[SELECTED].asBoolean() else !feature[SELECTED].asBoolean(),
            iconImage = icon,
            iconSize = const(if (picked) SELECTED_SCALE else 1f),
            iconAnchor = const(SymbolAnchor.Bottom),
            iconAllowOverlap = const(true),
            textField = format(span(feature[NAME].asString())),
            textSize = const(12.sp),
            textAnchor = const(SymbolAnchor.Top),
            textOptional = const(true),
            textColor = const(if (dark) Color(0xFFE8EAED) else Color(0xFF3C4043)),
            textHaloColor = const(if (dark) Color(0xFF202124) else Color.White),
            textHaloWidth = const(1.5.dp),
            onClick = onClick,
        )
    }
}
