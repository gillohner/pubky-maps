package to.pubky.maps.offline

/**
 * The parts of a Mapbox Vector Tile (version 2) that offline search reads: each layer's features
 * with their id, their attributes as text and one point of their geometry.
 */
internal data class TileLayer(
    val name: String,
    val extent: Int,
    val features: List<TileFeature>,
)

/**
 * One feature: [x] and [y] are in tile units (0 to the layer's extent; a feature can reach into the
 * tile's buffer, outside that range). The point is the feature itself for a point, the middle vertex
 * of its first line or ring for a line or a polygon.
 */
internal data class TileFeature(
    val id: Long?,
    val properties: Map<String, String>,
    val x: Int,
    val y: Int,
)

/** Thrown for bytes that are not a vector tile this decoder can read. */
internal class VectorTileException(
    message: String,
) : Exception(message)

/** Decodes a vector tile (not compressed). Features without a geometry are left out. */
internal fun decodeVectorTile(bytes: ByteArray): List<TileLayer> {
    val reader = ProtoReader(bytes, 0, bytes.size)
    val layers = ArrayList<TileLayer>()
    while (reader.more()) {
        val tag = reader.varint()
        if (tag == TILE_LAYER) layers += decodeLayer(reader.message()) else reader.skip(tag)
    }
    return layers
}

private fun decodeLayer(reader: ProtoReader): TileLayer {
    var name = ""
    var extent = DEFAULT_EXTENT
    val keys = ArrayList<String>()
    val values = ArrayList<String?>()
    val features = ArrayList<ProtoReader>()
    while (reader.more()) {
        when (val tag = reader.varint()) {
            LAYER_NAME -> name = reader.string()
            LAYER_FEATURE -> features += reader.message()
            LAYER_KEY -> keys += reader.string()
            LAYER_VALUE -> values += decodeValue(reader.message())
            LAYER_EXTENT -> extent = reader.varint().toInt()
            else -> reader.skip(tag)
        }
    }
    if (extent <= 0) throw VectorTileException("Layer $name has extent $extent")
    return TileLayer(name, extent, features.mapNotNull { decodeFeature(it, keys, values) })
}

private fun decodeValue(reader: ProtoReader): String? {
    var value: String? = null
    while (reader.more()) {
        when (val tag = reader.varint()) {
            VALUE_STRING -> value = reader.string()
            VALUE_FLOAT -> value = Float.fromBits(reader.fixed32()).toString()
            VALUE_DOUBLE -> value = Double.fromBits(reader.fixed64()).toString()
            VALUE_INT, VALUE_UINT -> value = reader.varint().toString()
            VALUE_SINT -> value = zigzag(reader.varint()).toString()
            VALUE_BOOL -> value = (reader.varint() != 0L).toString()
            else -> reader.skip(tag)
        }
    }
    return value
}

private fun decodeFeature(
    reader: ProtoReader,
    keys: List<String>,
    values: List<String?>,
): TileFeature? {
    var id: Long? = null
    var tags: LongArray? = null
    var geometry: LongArray? = null
    while (reader.more()) {
        when (val tag = reader.varint()) {
            FEATURE_ID -> id = reader.varint()
            FEATURE_TAGS -> tags = reader.packed()
            FEATURE_GEOMETRY -> geometry = reader.packed()
            else -> reader.skip(tag)
        }
    }
    val (x, y) = firstPartPoint(geometry ?: return null) ?: return null
    val properties = HashMap<String, String>()
    tags?.let {
        var i = 0
        while (i + 1 < it.size) {
            val key = keys.getOrNull(it[i].toInt())
            val value = values.getOrNull(it[i + 1].toInt())
            if (key != null && value != null) properties[key] = value
            i += 2
        }
    }
    return TileFeature(id, properties, x, y)
}

/** The middle vertex of the geometry's first part (a point's own position). */
private fun firstPartPoint(geometry: LongArray): Pair<Int, Int>? {
    val xs = ArrayList<Int>()
    val ys = ArrayList<Int>()
    var x = 0
    var y = 0
    var i = 0
    var parts = 0
    while (i < geometry.size) {
        val command = geometry[i++]
        val id = (command and 7).toInt()
        val count = (command ushr 3).toInt()
        when (id) {
            MOVE_TO, LINE_TO -> {
                if (id == MOVE_TO && ++parts > 1) break
                repeat(count) {
                    if (i + 1 >= geometry.size) throw VectorTileException("Geometry ends inside a command")
                    x += zigzag(geometry[i++]).toInt()
                    y += zigzag(geometry[i++]).toInt()
                    xs += x
                    ys += y
                }
            }

            CLOSE_PATH -> {}

            else -> {
                throw VectorTileException("Unknown geometry command $id")
            }
        }
    }
    if (xs.isEmpty()) return null
    val middle = xs.size / 2
    return xs[middle] to ys[middle]
}

private fun zigzag(value: Long): Long = (value ushr 1) xor -(value and 1)

/** Reads protocol buffer fields from [bytes] between [start] and [end]. */
private class ProtoReader(
    private val bytes: ByteArray,
    private var position: Int,
    private val end: Int,
) {
    fun more(): Boolean = position < end

    fun varint(): Long {
        var result = 0L
        var shift = 0
        while (true) {
            if (position >= end) throw VectorTileException("Varint ends early")
            val byte = bytes[position++].toInt()
            result = result or ((byte and 0x7F).toLong() shl shift)
            if (byte and 0x80 == 0) return result
            shift += 7
            if (shift >= 64) throw VectorTileException("Varint too long")
        }
    }

    fun fixed32(): Int {
        if (end - position < 4) throw VectorTileException("Fixed32 ends early")
        var result = 0
        for (i in 0 until 4) result = result or ((bytes[position + i].toInt() and 0xFF) shl (8 * i))
        position += 4
        return result
    }

    fun fixed64(): Long {
        if (end - position < 8) throw VectorTileException("Fixed64 ends early")
        var result = 0L
        for (i in 0 until 8) result = result or ((bytes[position + i].toLong() and 0xFF) shl (8 * i))
        position += 8
        return result
    }

    private fun length(): Int {
        val length = varint()
        if (length < 0 || length > end - position) throw VectorTileException("Field longer than its message")
        return length.toInt()
    }

    fun message(): ProtoReader {
        val length = length()
        val inner = ProtoReader(bytes, position, position + length)
        position += length
        return inner
    }

    fun string(): String {
        val length = length()
        val text = bytes.decodeToString(position, position + length)
        position += length
        return text
    }

    fun packed(): LongArray {
        val inner = message()
        val out = ArrayList<Long>()
        while (inner.more()) out += inner.varint()
        return out.toLongArray()
    }

    /** Skips the field of [tag] (its number and wire type), whose tag was already read. */
    fun skip(tag: Long) {
        when ((tag and 7).toInt()) {
            WIRE_VARINT -> varint()
            WIRE_FIXED64 -> fixed64()
            WIRE_LENGTH -> position += length()
            WIRE_FIXED32 -> fixed32()
            else -> throw VectorTileException("Unknown wire type ${tag and 7}")
        }
    }
}

private const val DEFAULT_EXTENT = 4096

// Field tags (number shl 3 or wire type) of the vector tile schema.
private const val TILE_LAYER = 3L shl 3 or 2
private const val LAYER_NAME = 1L shl 3 or 2
private const val LAYER_FEATURE = 2L shl 3 or 2
private const val LAYER_KEY = 3L shl 3 or 2
private const val LAYER_VALUE = 4L shl 3 or 2
private const val LAYER_EXTENT = 5L shl 3
private const val FEATURE_ID = 1L shl 3
private const val FEATURE_TAGS = 2L shl 3 or 2
private const val FEATURE_GEOMETRY = 4L shl 3 or 2
private const val VALUE_STRING = 1L shl 3 or 2
private const val VALUE_FLOAT = 2L shl 3 or 5
private const val VALUE_DOUBLE = 3L shl 3 or 1
private const val VALUE_INT = 4L shl 3
private const val VALUE_UINT = 5L shl 3
private const val VALUE_SINT = 6L shl 3
private const val VALUE_BOOL = 7L shl 3

private const val WIRE_VARINT = 0
private const val WIRE_FIXED64 = 1
private const val WIRE_LENGTH = 2
private const val WIRE_FIXED32 = 5

private const val MOVE_TO = 1
private const val LINE_TO = 2
private const val CLOSE_PATH = 7
