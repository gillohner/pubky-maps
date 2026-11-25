package to.pubky.maps.search

import to.pubky.maps.links.GeoPoint
import kotlin.math.abs
import kotlin.math.floor

/** What the search bar holds when it is not plain search text. */
sealed interface GeoInput {
    /** A point: typed coordinates, a `geo:` URI or a plus code. */
    data class Point(
        val point: GeoPoint,
        /** A label the input carried (`geo:0,0?q=47.1,8.2(Home)`). */
        val label: String? = null,
        val zoom: Double? = null,
    ) : GeoInput

    /** A `geo:` URI that only searches (`geo:0,0?q=Kunsthaus`): search for [text]. */
    data class Query(
        val text: String,
    ) : GeoInput
}

/**
 * Reads coordinates in the forms map apps accept: decimal (`47.37, 8.54`, `47.37 8.54`,
 * `47.37N 8.54E`), degrees, minutes and seconds (`47°22'N 8°32'E`, `47°22'12.5"N 8°32'4"E`), `geo:`
 * URIs (with `z=`, `q=lat,lon(label)` or `q=text`) and plus codes (`8FVC9G8F+6X`; a short one like
 * `9G8F+6X` is placed near [reference], the map centre). Null for anything else.
 *
 * Two bare numbers count as coordinates only with a comma between them or a decimal point in both,
 * so "8001 2" stays a text search.
 */
fun parseGeoInput(
    text: String,
    reference: GeoPoint? = null,
): GeoInput? {
    val input = text.trim()
    if (input.isEmpty() || input.length > MAX_INPUT) return null
    if (input.startsWith("geo:", ignoreCase = true)) return parseGeoUri(input)
    parsePlusCode(input, reference)?.let { return GeoInput.Point(it, zoom = PLUS_CODE_ZOOM) }
    return parseCoordinates(input)?.let { GeoInput.Point(it) }
}

private const val MAX_INPUT = 256
private const val PLUS_CODE_ZOOM = 18.0

// Degrees, then optional minutes and seconds: 4 groups (degrees, the part from the degree sign on,
// minutes, seconds).
private const val DMS =
    "([+-]?\\d{1,3}(?:\\.\\d+)?)\\s*(°\\s*(?:(\\d{1,2}(?:\\.\\d+)?)\\s*'\\s*(?:(\\d{1,2}(?:\\.\\d+)?)\\s*\"\\s*)?)?)?"

// Hemisphere letters before the numbers ("N 47.37 E 8.54") or after them ("47.37N 8.54E", or none).
private val PREFIXED_PAIR = Regex("^([NSEW])\\s*$DMS\\s*([,;]?)\\s*([NSEW])\\s*$DMS$", RegexOption.IGNORE_CASE)
private val SUFFIXED_PAIR = Regex("^$DMS\\s*([NSEW])?\\s*([,;]?)\\s*$DMS\\s*([NSEW])?$", RegexOption.IGNORE_CASE)

private fun parseCoordinates(input: String): GeoPoint? {
    val normalized =
        input
            .replace('′', '\'')
            .replace('’', '\'')
            .replace('″', '"')
            .replace('”', '"')
            .replace("''", "\"")
            .replace('º', '°')
    val first: Coordinate
    val second: Coordinate
    val separator: String
    val prefixed = PREFIXED_PAIR.matchEntire(normalized)
    if (prefixed != null) {
        val g = prefixed.groupValues
        first = coordinate(g[1], g[2], g[3], g[4], g[5]) ?: return null
        separator = g[6]
        second = coordinate(g[7], g[8], g[9], g[10], g[11]) ?: return null
    } else {
        val g = SUFFIXED_PAIR.matchEntire(normalized)?.groupValues ?: return null
        first = coordinate(g[5], g[1], g[2], g[3], g[4]) ?: return null
        separator = g[6]
        second = coordinate(g[11], g[7], g[8], g[9], g[10]) ?: return null
    }
    val marked = first.hemisphere != null || second.hemisphere != null || first.hasDegreeSign || second.hasDegreeSign
    if (!marked && separator.isEmpty() && !(first.hasDecimals && second.hasDecimals)) return null
    val (lat, lon) =
        when {
            first.isLongitude && second.isLatitude -> second to first
            first.isLongitude || second.isLatitude -> return null
            else -> first to second
        }
    return geoPointOrNull(lat.value, lon.value)
}

private class Coordinate(
    val value: Double,
    val hemisphere: Char?,
    val hasDegreeSign: Boolean,
    val hasDecimals: Boolean,
) {
    val isLatitude get() = hemisphere == 'N' || hemisphere == 'S'
    val isLongitude get() = hemisphere == 'E' || hemisphere == 'W'
}

private fun coordinate(
    hemisphereLetter: String,
    degrees: String,
    degreePart: String,
    minutes: String,
    seconds: String,
): Coordinate? {
    val hemisphere = hemisphereLetter.firstOrNull()?.uppercaseChar()
    val deg = degrees.toDoubleOrNull() ?: return null
    val min = minutes.toDoubleOrNull() ?: 0.0
    val sec = seconds.toDoubleOrNull() ?: 0.0
    if (min >= MINUTES || sec >= MINUTES) return null
    // A sign and a hemisphere together ("-47 N") are contradictory; minutes after decimal degrees too.
    if (hemisphere != null && degrees.startsWith('-')) return null
    if ((minutes.isNotEmpty()) && '.' in degrees) return null
    val magnitude = abs(deg) + min / MINUTES + sec / (MINUTES * MINUTES)
    val negative = degrees.startsWith('-') || hemisphere == 'S' || hemisphere == 'W'
    return Coordinate(
        value = if (negative) -magnitude else magnitude,
        hemisphere = hemisphere,
        hasDegreeSign = degreePart.isNotEmpty(),
        hasDecimals = '.' in degrees,
    )
}

private const val MINUTES = 60.0

private val GEO_POINT = Regex("^([+-]?\\d{1,3}(?:\\.\\d+)?),([+-]?\\d{1,3}(?:\\.\\d+)?)(?:,[+-]?\\d+(?:\\.\\d+)?)?$")
private val GEO_QUERY_POINT = Regex("^\\s*([+-]?\\d{1,3}(?:\\.\\d+)?)\\s*,\\s*([+-]?\\d{1,3}(?:\\.\\d+)?)\\s*(?:\\((.*)\\))?\\s*$")

/** `geo:lat,lon[,alt][;params][?z=..&q=..]` (RFC 5870 plus Android's `q` and `z`). */
private fun parseGeoUri(input: String): GeoInput? {
    val rest = input.substring(4)
    val path = rest.substringBefore('?')
    val query = rest.substringAfter('?', "")
    val pathParts = path.split(';')
    val crs = pathParts.drop(1).firstOrNull { it.startsWith("crs=", ignoreCase = true) }?.substringAfter('=')
    if (crs != null && !crs.equals("wgs84", ignoreCase = true)) return null
    val params =
        query
            .split('&')
            .filter { '=' in it }
            .associate { decodeQueryPart(it.substringBefore('=')).lowercase() to decodeQueryPart(it.substringAfter('=')) }
    val zoom = params["z"]?.toDoubleOrNull()?.takeIf { it in 1.0..MAX_GEO_ZOOM }
    val q = params["q"]?.trim().orEmpty()
    if (q.isNotEmpty()) {
        val m = GEO_QUERY_POINT.matchEntire(q)
        if (m != null) {
            val point = geoPointOrNull(m.groupValues[1].toDouble(), m.groupValues[2].toDouble()) ?: return null
            return GeoInput.Point(point, label = m.groupValues[3].trim().ifEmpty { null }, zoom = zoom)
        }
        return GeoInput.Query(q)
    }
    val m = GEO_POINT.matchEntire(pathParts.first()) ?: return null
    val point = geoPointOrNull(m.groupValues[1].toDouble(), m.groupValues[2].toDouble()) ?: return null
    // Android writes geo:0,0 for "no point".
    if (point.lat == 0.0 && point.lon == 0.0) return null
    return GeoInput.Point(point, zoom = zoom)
}

private const val MAX_GEO_ZOOM = 23.0

/** Percent-decoding of a query part, `+` as a space. Malformed escapes stay as typed. */
private fun decodeQueryPart(part: String): String {
    val bytes = ArrayList<Byte>(part.length)
    var i = 0
    while (i < part.length) {
        val c = part[i]
        val hex = if (c == '%' && i + 2 <= part.lastIndex) part.substring(i + 1, i + 3).toIntOrNull(HEX) else null
        when {
            hex != null -> {
                bytes += hex.toByte()
                i += 3
            }

            c == '+' -> {
                bytes += ' '.code.toByte()
                i++
            }

            else -> {
                c.toString().encodeToByteArray().forEach { bytes += it }
                i++
            }
        }
    }
    return bytes.toByteArray().decodeToString()
}

private const val HEX = 16

// Open Location Code (plus codes), https://github.com/google/open-location-code (spec: Apache-2.0).
private const val OLC_ALPHABET = "23456789CFGHJMPQRVWX"
private const val OLC_SEPARATOR_POSITION = 8
private const val OLC_PAIR_LENGTH = 10
private const val OLC_GRID_COLUMNS = 4
private const val OLC_GRID_ROWS = 5
private const val OLC_MAX_LENGTH = 15
private val OLC_PAIR_RESOLUTIONS = doubleArrayOf(20.0, 1.0, 0.05, 0.0025, 0.000125)
private val PLUS_CODE = Regex("^[23456789CFGHJMPQRVWX]{2,8}\\+[23456789CFGHJMPQRVWX]{0,7}$", RegexOption.IGNORE_CASE)

/** The centre of a full plus code, or of a short one placed nearest to [reference]. */
internal fun parsePlusCode(
    input: String,
    reference: GeoPoint?,
): GeoPoint? {
    val code = input.uppercase()
    if (!PLUS_CODE.matches(code)) return null
    val separator = code.indexOf('+')
    if (separator % 2 != 0) return null
    val digitsAfter = code.length - separator - 1
    if (digitsAfter == 1) return null
    return if (separator == OLC_SEPARATOR_POSITION) {
        decodeFullPlusCode(code)
    } else {
        // A short code needs its area: the reference point gives the dropped first digits.
        if (digitsAfter < 2) return null
        recoverShortPlusCode(code, reference ?: return null)
    }
}

private fun decodeFullPlusCode(code: String): GeoPoint? {
    val digits = code.replace("+", "")
    if (digits.length > OLC_MAX_LENGTH) return null
    val first = OLC_ALPHABET.indexOf(digits[0])
    val second = OLC_ALPHABET.indexOf(digits[1])
    // The first digit encodes latitude in 20-degree bands (9 of them), the second longitude (18).
    if (first * OLC_PAIR_RESOLUTIONS[0] >= LAT_SPAN || second * OLC_PAIR_RESOLUTIONS[0] >= LON_SPAN) return null
    var lat = -LAT_SPAN / 2
    var lon = -LON_SPAN / 2
    var latSize = OLC_PAIR_RESOLUTIONS[0]
    var lonSize = OLC_PAIR_RESOLUTIONS[0]
    val pairDigits = minOf(digits.length, OLC_PAIR_LENGTH)
    for (i in 0 until pairDigits step 2) {
        val res = OLC_PAIR_RESOLUTIONS[i / 2]
        lat += OLC_ALPHABET.indexOf(digits[i]) * res
        lon += OLC_ALPHABET.indexOf(digits[i + 1]) * res
        latSize = res
        lonSize = res
    }
    for (i in OLC_PAIR_LENGTH until digits.length) {
        val d = OLC_ALPHABET.indexOf(digits[i])
        latSize /= OLC_GRID_ROWS
        lonSize /= OLC_GRID_COLUMNS
        lat += (d / OLC_GRID_COLUMNS) * latSize
        lon += (d % OLC_GRID_COLUMNS) * lonSize
    }
    return geoPointOrNull(
        lat = (lat + latSize / 2).coerceAtMost(LAT_SPAN / 2),
        lon = normalizeLongitude(lon + lonSize / 2),
    )
}

private fun recoverShortPlusCode(
    short: String,
    reference: GeoPoint,
): GeoPoint? {
    val dropped = OLC_SEPARATOR_POSITION - short.indexOf('+')
    val prefix = encodePlusCodePairs(reference, dropped)
    val decoded = decodeFullPlusCode(prefix + short) ?: return null
    // The area the dropped digits name; move to the neighbouring area when that is nearer.
    val resolution = OLC_PAIR_RESOLUTIONS[dropped / 2 - 1]
    val half = resolution / 2
    var lat = decoded.lat
    var lon = decoded.lon
    if (reference.lat + half < lat && lat - resolution >= -LAT_SPAN / 2) {
        lat -= resolution
    } else if (reference.lat - half > lat && lat + resolution <= LAT_SPAN / 2) {
        lat += resolution
    }
    if (reference.lon + half < lon) {
        lon -= resolution
    } else if (reference.lon - half > lon) {
        lon += resolution
    }
    return geoPointOrNull(lat, normalizeLongitude(lon))
}

/** The first [length] digits (whole pairs) of the plus code of [point]. */
private fun encodePlusCodePairs(
    point: GeoPoint,
    length: Int,
): String {
    var lat = point.lat.coerceIn(-LAT_SPAN / 2, LAT_SPAN / 2) + LAT_SPAN / 2
    var lon = normalizeLongitude(point.lon) + LON_SPAN / 2
    if (lat >= LAT_SPAN) lat = LAT_SPAN - OLC_PAIR_RESOLUTIONS.last() / 2
    val out = StringBuilder()
    for (i in 0 until length / 2) {
        val res = OLC_PAIR_RESOLUTIONS[i]
        val latDigit = floor(lat / res).toInt().coerceIn(0, OLC_ALPHABET.length - 1)
        val lonDigit = floor(lon / res).toInt().coerceIn(0, OLC_ALPHABET.length - 1)
        lat -= latDigit * res
        lon -= lonDigit * res
        out.append(OLC_ALPHABET[latDigit]).append(OLC_ALPHABET[lonDigit])
    }
    return out.toString()
}

private const val LAT_SPAN = 180.0
private const val LON_SPAN = 360.0

private fun normalizeLongitude(lon: Double): Double {
    var l = lon
    while (l < -LON_SPAN / 2) l += LON_SPAN
    while (l >= LON_SPAN / 2) l -= LON_SPAN
    return l
}
