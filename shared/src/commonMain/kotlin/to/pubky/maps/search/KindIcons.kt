package to.pubky.maps.search

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp
import to.pubky.maps.ui.AppIcons

/** The Material glyph of each kind of place (Apache-2.0, from google/material-design-icons). */
private val KIND_GLYPHS: Map<PlaceKind, String> =
    mapOf(
        PlaceKind.Food to
            "M11 9H9V2H7v7H5V2H3v7c0 2.12 1.66 3.84 3.75 3.97V22h2.5v-9.03C11.34 12.84 13 11.12 13 9V2h-2v7z" +
            "m5-3v8h2.5v8H21V2c-2.76 0-5 2.24-5 4z",
        PlaceKind.Transit to
            "M12 2c-4.42 0-8 .5-8 4v9.5C4 17.43 5.57 19 7.5 19L6 20.5v.5h12v-.5L16.5 19c1.93 0 3.5-1.57 3.5-3.5V6" +
            "c0-3.5-3.58-4-8-4zM7.5 17c-.83 0-1.5-.67-1.5-1.5S6.67 14 7.5 14s1.5.67 1.5 1.5S8.33 17 7.5 17zm3.5-6H6V6h5v5z" +
            "m5.5 6c-.83 0-1.5-.67-1.5-1.5s.67-1.5 1.5-1.5 1.5.67 1.5 1.5-.67 1.5-1.5 1.5zm1.5-6h-5V6h5v5z",
        PlaceKind.Culture to "M4 10h3v7H4zm6.5 0h3v7h-3zM2 19h20v3H2zm15-9h3v7h-3zm-5-9L2 6v2h20V6z",
        PlaceKind.Education to "M5 13.18v4L12 21l7-3.82v-4L12 17l-7-3.82zM12 3L1 9l11 6 9-4.91V17h2V9L12 3z",
        PlaceKind.Shop to
            "M19 6h-2c0-2.76-2.24-5-5-5S7 3.24 7 6H5c-1.1 0-2 .9-2 2v12c0 1.1.9 2 2 2h14c1.1 0 2-.9 2-2V8" +
            "c0-1.1-.9-2-2-2zm-7-3c1.66 0 3 1.34 3 3H9c0-1.66 1.34-3 3-3zm0 10c-2.76 0-5-2.24-5-5h2c0 1.66 1.34 3 3 3" +
            "s3-1.34 3-3h2c0 2.76-2.24 5-5 5z",
        PlaceKind.Nature to "M14 6l-3.75 5 2.85 3.8-1.6 1.2C9.81 13.75 7 10 7 10l-6 8h22L14 6z",
        PlaceKind.Area to
            "M15 11V5l-3-3-3 3v2H3v14h18V11h-6zm-8 8H5v-2h2v2zm0-4H5v-2h2v2zm0-4H5V9h2v2zm6 8h-2v-2h2v2zm0-4h-2v-2h2v2z" +
            "m0-4h-2V9h2v2zm0-4h-2V5h2v2zm6 12h-2v-2h2v2zm0-4h-2v-2h2v2z",
        PlaceKind.Street to
            "M13 10h5l3-3-3-3h-5V2h-2v2H4v6h7v2H6l-3 3 3 3h5v4h2v-4h7v-6h-7v-2zM6 6h11.17l1 1-1 1H6V6z" +
            "m12 10H6.83l-1-1 1-1H18v2z",
        PlaceKind.Point to
            "M12 8c-2.21 0-4 1.79-4 4s1.79 4 4 4 4-1.79 4-4-1.79-4-4-4zm8.94 3c-.46-4.17-3.77-7.48-7.94-7.94V1h-2v2.06" +
            "C6.83 3.52 3.52 6.83 3.06 11H1v2h2.06c.46 4.17 3.77 7.48 7.94 7.94V23h2v-2.06c4.17-.46 7.48-3.77 7.94-7.94" +
            "H23v-2h-2.06zM12 19c-3.87 0-7-3.13-7-7s3.13-7 7-7 7 3.13 7 7-3.13 7-7 7z",
    )

/** A white dot: the glyph of a place of no particular kind on its pin. */
private const val DOT = "M12 8a4 4 0 1 0 0 8a4 4 0 1 0 0-8z"

private val kindIcons: Map<PlaceKind, ImageVector> =
    PlaceKind.entries.associateWith { kind ->
        val glyph = KIND_GLYPHS[kind]
        if (glyph == null) {
            AppIcons.Place
        } else {
            ImageVector
                .Builder(name = "Kind$kind", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
                .addPath(PathParser().parsePathString(glyph).toNodes(), fill = SolidColor(Color.Black))
                .build()
        }
    }

/** The icon of [kind] for lists (tinted by the list). */
val PlaceKind.icon: ImageVector get() = kindIcons.getValue(this)

/** The pin's outline: a round head over a point at the bottom middle, 24 x 30 units. */
private const val PIN =
    "M12 1C6.48 1 2 5.48 2 11c0 6.6 8.1 15.9 9.3 17.3c.38.44 1.02.44 1.4 0C13.9 26.9 22 17.6 22 11C22 5.48 17.52 1 12 1z"

/** Height of a pin's viewport; its tip is at the bottom, so the pin is drawn above its point. */
internal const val PIN_VIEWPORT_HEIGHT = 30f

/**
 * The map pin of [kind]: [fill] with a white outline and the kind's white glyph in its head. The tip
 * sits at the bottom middle, so the pin stands on its point and leaves the point's own map label
 * readable beside it.
 */
internal fun pinIcon(
    kind: PlaceKind,
    fill: Color,
): ImageVector {
    val glyph = KIND_GLYPHS[kind] ?: DOT
    return ImageVector
        .Builder(
            name = "Pin$kind",
            defaultWidth = 24.dp,
            defaultHeight = 30.dp,
            viewportWidth = 24f,
            viewportHeight = PIN_VIEWPORT_HEIGHT,
        ).addPath(
            PathParser().parsePathString(PIN).toNodes(),
            fill = SolidColor(fill),
            stroke = SolidColor(Color.White),
            strokeLineWidth = 1.5f,
        ).addGroup(
            scaleX = GLYPH_SCALE,
            scaleY = GLYPH_SCALE,
            translationX = 12f - 12f * GLYPH_SCALE,
            translationY =
                11f - 12f * GLYPH_SCALE,
        ).addPath(PathParser().parsePathString(glyph).toNodes(), fill = SolidColor(Color.White))
        .clearGroup()
        .build()
}

/** The glyph fills about half the pin's head. */
private const val GLYPH_SCALE = 0.5f
