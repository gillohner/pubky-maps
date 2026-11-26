package to.pubky.maps.offline

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.tan

/** A box on the map in degrees; [west] is less than [east] (an area across the antimeridian is not one box). */
data class AreaBounds(
    val west: Double,
    val south: Double,
    val east: Double,
    val north: Double,
) {
    val centerLat: Double get() = (south + north) / 2
    val centerLon: Double get() = (west + east) / 2
}

/**
 * What one offline download may take. OpenFreeMap, the default map server, allows downloads for
 * offline use of at most 3,000 tiles and 50 MB each, made with at most 4 parallel requests and a
 * User-Agent that names the app; larger regions are several downloads. The app applies the same
 * limits to every map server.
 */
object OfflineLimits {
    const val MAX_TILES: Long = 3_000

    /**
     * Counted in tiles: the style, its sprites and its fonts are fetched once per phone and shared
     * by every download.
     */
    const val MAX_TILE_BYTES: Long = 50_000_000

    const val PARALLEL_REQUESTS: Int = 4

    /**
     * The area's tiles are counted in the map's vector source; a style can also have a low-zoom
     * layer (OpenFreeMap's shaded relief goes to zoom 6, a few tiles for a city). The picker leaves
     * this many tiles of the limit for them.
     */
    const val OTHER_SOURCES_MARGIN: Long = 100

    /** The deepest zoom of OpenFreeMap's and VersaTiles' vector tiles; the map zooms further in on them. */
    const val MAX_ZOOM: Int = 14
}

/** The tiles a download of [bounds] needs, from zoom 0 to [OfflineLimits.MAX_ZOOM], in one vector source. */
fun tileCount(
    bounds: AreaBounds,
    maxZoom: Int = OfflineLimits.MAX_ZOOM,
): Long = (0..maxZoom).sumOf { zoom -> tilesAt(bounds, zoom) }

/** Whether [bounds] fits in one download. */
fun fitsInOneDownload(bounds: AreaBounds): Boolean = tileCount(bounds) <= OfflineLimits.MAX_TILES - OfflineLimits.OTHER_SOURCES_MARGIN

private fun tilesAt(
    bounds: AreaBounds,
    zoom: Int,
): Long {
    val n = 2.0.pow(zoom)
    val last = n.toLong() - 1
    val west = tileX(bounds.west, n).coerceIn(0, last)
    val east = tileX(bounds.east, n).coerceIn(0, last)
    // Tile rows count from the north.
    val north = tileY(bounds.north, n).coerceIn(0, last)
    val south = tileY(bounds.south, n).coerceIn(0, last)
    return (east - west + 1) * (south - north + 1)
}

private fun tileX(
    lon: Double,
    n: Double,
): Long = floor((lon + 180) / 360 * n).toLong()

/** Web Mercator stops at about 85.05°; latitudes beyond it fall into the first or last row. */
private fun tileY(
    lat: Double,
    n: Double,
): Long {
    val clamped = lat.coerceIn(-MAX_MERCATOR_LAT, MAX_MERCATOR_LAT) * PI / 180
    return floor((1 - ln(tan(clamped) + 1 / cos(clamped)) / PI) / 2 * n).toLong()
}

private const val MAX_MERCATOR_LAT = 85.05112878
