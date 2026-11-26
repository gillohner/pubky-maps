package to.pubky.maps.map

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.dsl.image
import org.maplibre.compose.interaction.ClickResult
import org.maplibre.compose.layers.SymbolLayer
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.rememberGeoJsonSource
import org.maplibre.spatialk.geojson.Feature
import org.maplibre.spatialk.geojson.FeatureCollection
import org.maplibre.spatialk.geojson.Point
import org.maplibre.spatialk.geojson.Position
import to.pubky.maps.place.PlaceRef
import to.pubky.maps.place.PlaceSource
import to.pubky.maps.search.osmIdFromUrl
import to.pubky.maps.write.SavedEntry

/**
 * The purple of the user's saved places: apart from search's and the opened place's red, the teal
 * of Pubky places and the blue location dot; the white bookmark on it has 8:1 contrast.
 */
private val SavedPlaceColor = Color(0xFF7B1FA2)

private val SavedPinSize = DpSize(22.dp, 22.dp)

private const val INDEX = "i"

/** The Material "bookmark" glyph (Apache-2.0, google/material-design-icons), as the Saved page's rows. */
private const val BOOKMARK = "M17 3H7c-1.1 0-1.99.9-1.99 2L5 21l7-3 7 3V5c0-1.1-.9-2-2-2z"
private const val CIRCLE = "M12 1a11 11 0 1 0 0 22a11 11 0 1 0 0-22z"
private const val GLYPH_SCALE = 0.55f

/**
 * The saved places that get a pin: those whose point is known, one per place (a place saved on the
 * phone and on the homeserver is one pin), in the order of [saved].
 */
internal fun savedPins(saved: List<SavedEntry>): List<SavedEntry> = saved.filter { it.point != null }.distinctBy { it.place }

/** The place page of a saved place's pin: the place as saved, where it was saved. */
internal fun SavedEntry.toPlaceRef(): PlaceRef =
    PlaceRef(uri = place, name = name, point = point, osm = osmIdFromUrl(place), source = PlaceSource.Map)

/**
 * The user's saved places on the map, as Google Maps, CoMaps and OsmAnd draw theirs: a round purple
 * badge with a bookmark, centred on the place. Like search pins they carry no text and never push the
 * map's own labels away (the place's name stays readable beside the badge). Tapping one opens its place.
 */
@Composable
internal fun SavedPins(
    saved: List<SavedEntry>,
    onSelect: (SavedEntry) -> Unit,
) {
    val features =
        remember(saved) {
            FeatureCollection(
                saved.mapIndexedNotNull { i, entry ->
                    val point = entry.point ?: return@mapIndexedNotNull null
                    Feature(
                        geometry = Point(Position(longitude = point.lon, latitude = point.lat)),
                        properties = JsonObject(mapOf(INDEX to JsonPrimitive(i))),
                    )
                },
            )
        }
    val source = rememberGeoJsonSource(GeoJsonData.Features(features))
    val painter = rememberVectorPainter(remember { savedPinIcon() })
    SymbolLayer(
        id = "saved-places",
        source = source,
        iconImage = image(painter, SavedPinSize),
        iconAllowOverlap = const(true),
        // The map's own labels (the place's name among them) are placed as if the badges were not there.
        iconIgnorePlacement = const(true),
        onClick = { clicked ->
            val index =
                clicked
                    .firstOrNull()
                    ?.properties
                    ?.get(INDEX)
                    ?.jsonPrimitive
                    ?.intOrNull
            val entry = index?.let { saved.getOrNull(it) }
            if (entry != null) {
                onSelect(entry)
                ClickResult.Consume
            } else {
                ClickResult.Pass
            }
        },
    )
}

private fun savedPinIcon(): ImageVector =
    ImageVector
        .Builder(name = "SavedPin", defaultWidth = 22.dp, defaultHeight = 22.dp, viewportWidth = 24f, viewportHeight = 24f)
        .addPath(
            PathParser().parsePathString(CIRCLE).toNodes(),
            fill = SolidColor(SavedPlaceColor),
            stroke = SolidColor(Color.White),
            strokeLineWidth = 2f,
        ).addGroup(
            scaleX = GLYPH_SCALE,
            scaleY = GLYPH_SCALE,
            translationX = 12f - 12f * GLYPH_SCALE,
            translationY = 12f - 12f * GLYPH_SCALE,
        ).addPath(PathParser().parsePathString(BOOKMARK).toNodes(), fill = SolidColor(Color.White))
        .clearGroup()
        .build()
