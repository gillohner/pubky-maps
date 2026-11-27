package to.pubky.maps.map

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.value.LineCap
import org.maplibre.compose.expressions.value.LineJoin
import org.maplibre.compose.layers.CircleLayer
import org.maplibre.compose.layers.LineLayer
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.rememberGeoJsonSource
import org.maplibre.spatialk.geojson.Feature
import org.maplibre.spatialk.geojson.FeatureCollection
import org.maplibre.spatialk.geojson.LineString
import org.maplibre.spatialk.geojson.Point
import org.maplibre.spatialk.geojson.Position
import to.pubky.maps.links.GeoPoint

/** The blue of a route, as in Google Maps and OsmAnd: apart from the red pins and the teal Pubky places. */
private val RouteColor = Color(0xFF1A73E8)

/** The route's darker edge, so the line stands out on light and dark maps alike. */
private val RouteCasing = Color(0xFF0B4FB3)

/** The line of a route on the map: a blue line with a darker edge, rounded at its ends and turns. */
@Composable
internal fun RouteLine(points: List<GeoPoint>) {
    val features =
        remember(points) {
            FeatureCollection(
                listOf(
                    Feature(
                        geometry = LineString(points.map { Position(longitude = it.lon, latitude = it.lat) }),
                        properties = null,
                    ),
                ),
            )
        }
    val source = rememberGeoJsonSource(GeoJsonData.Features(features))
    LineLayer(
        id = "route-casing",
        source = source,
        color = const(RouteCasing),
        width = const(9.dp),
        cap = const(LineCap.Round),
        join = const(LineJoin.Round),
    )
    LineLayer(
        id = "route",
        source = source,
        color = const(RouteColor),
        width = const(6.dp),
        cap = const(LineCap.Round),
        join = const(LineJoin.Round),
    )
}

/**
 * The route step the user tapped in the list: a white dot ringed in the route's blue on the line,
 * as Google Maps marks a step; apart from the red pin of the place at one of the route's ends.
 */
@Composable
internal fun RouteStepMark(point: GeoPoint) {
    key(point) {
        val source = rememberGeoJsonSource(GeoJsonData.Features(Point(Position(longitude = point.lon, latitude = point.lat))))
        CircleLayer(
            id = "route-step",
            source = source,
            radius = const(7.dp),
            color = const(Color.White),
            strokeColor = const(RouteCasing),
            strokeWidth = const(3.dp),
        )
    }
}

/** The grey of a chosen start's ring, as Google Maps marks a start that is not the user's position. */
private val StartRing = Color(0xFF3C4043)

/**
 * The start the user chose for directions ([to.pubky.maps.routing.Directions.from]): a white dot in a
 * dark ring where the route's line begins (ends, swapped), as Google Maps marks a start other than the
 * user's position; apart from the blue location dot and the place's red pin.
 */
@Composable
internal fun RouteStartMark(point: GeoPoint) {
    key(point) {
        val source = rememberGeoJsonSource(GeoJsonData.Features(Point(Position(longitude = point.lon, latitude = point.lat))))
        CircleLayer(
            id = "route-start",
            source = source,
            radius = const(6.dp),
            color = const(Color.White),
            strokeColor = const(StartRing),
            strokeWidth = const(3.dp),
        )
    }
}
