package to.pubky.maps.map

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.dsl.interpolate
import org.maplibre.compose.expressions.dsl.linear
import org.maplibre.compose.expressions.dsl.zoom
import org.maplibre.compose.expressions.value.LineCap
import org.maplibre.compose.expressions.value.LineJoin
import org.maplibre.compose.layers.Anchor
import org.maplibre.compose.layers.CircleLayer
import org.maplibre.compose.layers.LineLayer
import org.maplibre.compose.sources.rememberVectorTileSource
import to.pubky.maps.config.AttributionLink
import to.pubky.maps.place.PICTURES_LAYER
import to.pubky.maps.place.SEQUENCES_LAYER
import to.pubky.maps.place.StreetPhotoTiles

/**
 * Panoramax' own orange, as its viewer draws its photos: apart from the route's blue and the Pubky
 * places' teal, and seen on the light and the dark map alike.
 */
internal val StreetPhotoColor = Color(0xFFFF6F00)

/** Lines start at this zoom, as in Panoramax' viewer: below it a country's lines would cover the map. */
internal const val STREET_PHOTO_LINES_ZOOM = 7f

/** Each photo's point from this zoom on, as in Panoramax' viewer: a street's photos lie metres apart. */
internal const val STREET_PHOTO_POINTS_ZOOM = 16f

/**
 * Where Panoramax has street-level photos, drawn over the base map as Google Maps draws its Street View
 * layer: an orange line along each sequence (the way a camera was carried) from zoom 7, each photo's
 * point on it from zoom 16. Drawn from [tiles]; at zooms past the server's last tiles MapLibre draws
 * those larger. Under the base map's [labels] layer (its lowest label layer, null while unknown) so
 * street and place names stay readable over the lines. Taps go through to the map.
 */
@Composable
internal fun StreetPhotoCoverage(
    tiles: StreetPhotoTiles,
    labels: String?,
) {
    // Keyed: another style has its labels in another layer.
    key(labels) {
        if (labels != null) {
            Anchor.Below(labels) { StreetPhotoLayers(tiles) }
        } else {
            StreetPhotoLayers(tiles)
        }
    }
}

@Composable
private fun StreetPhotoLayers(tiles: StreetPhotoTiles) {
    key(tiles.tiles, tiles.maxZoom) {
        val source = rememberVectorTileSource(listOf(tiles.tiles)) { maxZoom = tiles.maxZoom }
        LineLayer(
            id = "street-photos-sequences",
            source = source,
            sourceLayer = SEQUENCES_LAYER,
            minZoom = STREET_PHOTO_LINES_ZOOM,
            color = const(StreetPhotoColor),
            opacity = const(0.8f),
            width = interpolate(linear(), zoom(), 7 to const(1.dp), 12 to const(2.dp), 15 to const(3.dp), 18 to const(4.dp)),
            cap = const(LineCap.Round),
            join = const(LineJoin.Round),
        )
        CircleLayer(
            id = "street-photos-pictures",
            source = source,
            sourceLayer = PICTURES_LAYER,
            minZoom = STREET_PHOTO_POINTS_ZOOM,
            color = const(StreetPhotoColor),
            radius = interpolate(linear(), zoom(), 16 to const(3.dp), 19 to const(6.dp)),
            strokeColor = const(Color.White),
            strokeWidth = const(1.dp),
        )
    }
}

/**
 * The credit for the layer's lines while the map draws them: Panoramax, linking to the server whose
 * tiles they are (its photos are credited by name on the place page and in the viewer).
 */
internal fun streetPhotoCredit(tiles: StreetPhotoTiles): AttributionLink = AttributionLink("Panoramax", tiles.server)
