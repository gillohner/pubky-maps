package to.pubky.maps.map

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.maplibre.compose.expressions.dsl.asNumber
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.dsl.feature
import org.maplibre.compose.expressions.dsl.not
import org.maplibre.compose.expressions.dsl.step
import org.maplibre.compose.interaction.ClickResult
import org.maplibre.compose.layers.CircleLayer
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.rememberGeoJsonSource
import org.maplibre.spatialk.geojson.Feature
import org.maplibre.spatialk.geojson.FeatureCollection
import org.maplibre.spatialk.geojson.Point
import org.maplibre.spatialk.geojson.Position
import to.pubky.maps.place.MapPlace

/** The teal of places people referenced on Pubky: apart from search's red pins and the blue location dot. */
private val PubkyPlaceColor = Color(0xFF00897B)

private const val INDEX = "i"

/**
 * The Pubky places layer: a dot per place, clustered by MapLibre's GeoJSON clustering (radius 50 px,
 * up to zoom 15) into larger dots for crowded areas. Tapping a dot opens its place; tapping a cluster
 * zooms in on it ([onCluster] gets its position).
 */
@Composable
internal fun MapPlacesPins(
    places: List<MapPlace>,
    onSelect: (MapPlace) -> Unit,
    onCluster: (Position) -> Unit,
) {
    val features =
        remember(places) {
            FeatureCollection(
                places.mapIndexed { i, place ->
                    Feature(
                        geometry = Point(Position(longitude = place.lon, latitude = place.lat)),
                        properties = JsonObject(mapOf(INDEX to JsonPrimitive(i))),
                    )
                },
            )
        }
    val source =
        rememberGeoJsonSource(GeoJsonData.Features(features)) {
            cluster = true
            clusterRadius = 50
            clusterMaxZoom = 15
        }
    CircleLayer(
        id = "pubky-place-clusters",
        source = source,
        filter = feature.has("point_count"),
        radius = step(feature["point_count"].asNumber(), const(12.dp), 10 to const(16.dp), 50 to const(20.dp)),
        color = const(PubkyPlaceColor),
        opacity = const(0.85f),
        strokeColor = const(Color.White),
        strokeWidth = const(2.dp),
        onClick = { clicked ->
            val point = clicked.firstOrNull()?.geometry as? Point
            if (point != null) {
                onCluster(point.coordinates)
                ClickResult.Consume
            } else {
                ClickResult.Pass
            }
        },
    )
    CircleLayer(
        id = "pubky-places",
        source = source,
        filter = !feature.has("point_count"),
        radius = const(6.dp),
        color = const(PubkyPlaceColor),
        strokeColor = const(Color.White),
        strokeWidth = const(2.dp),
        hitPadding = 8.dp,
        onClick = { clicked ->
            val index =
                clicked
                    .firstOrNull()
                    ?.properties
                    ?.get(INDEX)
                    ?.jsonPrimitive
                    ?.intOrNull
            val place = index?.let { places.getOrNull(it) }
            if (place != null) {
                onSelect(place)
                ClickResult.Consume
            } else {
                ClickResult.Pass
            }
        },
    )
}
