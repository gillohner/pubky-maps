package to.pubky.maps.map

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.layers.CircleLayer
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.rememberGeoJsonSource
import org.maplibre.spatialk.geojson.Point
import org.maplibre.spatialk.geojson.Position
import to.pubky.maps.links.GeoPoint

/** The red of the pin on an opened place (Google Maps' and CoMaps' place pins are red too). */
private val PinColor = Color(0xFFD93025)

/** A pin on the place a link opened. */
@Composable
internal fun PlacePin(point: GeoPoint) {
    key(point) {
        val source = rememberGeoJsonSource(GeoJsonData.Features(Point(Position(longitude = point.lon, latitude = point.lat))))
        CircleLayer(
            id = "linked-place",
            source = source,
            radius = const(9.dp),
            color = const(PinColor),
            strokeColor = const(Color.White),
            strokeWidth = const(3.dp),
        )
    }
}
