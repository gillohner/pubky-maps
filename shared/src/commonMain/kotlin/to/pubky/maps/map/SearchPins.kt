package to.pubky.maps.map

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.interaction.ClickResult
import org.maplibre.compose.layers.CircleLayer
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.rememberGeoJsonSource
import org.maplibre.spatialk.geojson.Feature
import org.maplibre.spatialk.geojson.FeatureCollection
import org.maplibre.spatialk.geojson.Point
import org.maplibre.spatialk.geojson.Position
import to.pubky.maps.search.SearchResult

/** The red of search pins, the same as a linked place's pin. */
private val ResultPinColor = Color(0xFFD93025)

private const val INDEX = "i"

/**
 * The search results as pins: small ones for every result, a larger one for the picked result.
 * Tapping a pin picks its result.
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
                    if (result == selected) return@mapIndexedNotNull null
                    Feature(
                        geometry = Point(Position(longitude = point.lon, latitude = point.lat)),
                        properties = JsonObject(mapOf(INDEX to JsonPrimitive(i))),
                    )
                },
            )
        }
    val source = rememberGeoJsonSource(GeoJsonData.Features(features))
    CircleLayer(
        id = "search-results",
        source = source,
        radius = const(6.dp),
        color = const(ResultPinColor),
        strokeColor = const(Color.White),
        strokeWidth = const(2.dp),
        onClick = { features ->
            val index =
                features
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
        },
    )
    selected?.place?.point?.let { PlacePin(it, id = "search-selected") }
}
