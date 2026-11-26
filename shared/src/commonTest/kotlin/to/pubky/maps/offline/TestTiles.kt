package to.pubky.maps.offline

/** A feature to write into a test vector tile: [points] in tile units, a point when there is one. */
internal data class TestFeature(
    val id: Long?,
    val properties: Map<String, Any>,
    val points: List<Pair<Int, Int>>,
    val polygon: Boolean = false,
)

/** Writes a Mapbox Vector Tile (version 2) with [layers] (name to features), as a map server sends it. */
internal fun vectorTile(
    layers: Map<String, List<TestFeature>>,
    extent: Int = 4096,
): ByteArray {
    val tile = Proto()
    for ((name, features) in layers) {
        val keys = ArrayList<String>()
        val values = ArrayList<Any>()
        val layer = Proto()
        layer.varintField(15, 2)
        layer.bytesField(1, name.encodeToByteArray())
        for (feature in features) {
            val f = Proto()
            feature.id?.let { f.varintField(1, it) }
            val tags = Proto()
            for ((key, value) in feature.properties) {
                if (key !in keys) keys += key
                if (value !in values) values += value
                tags.varint(keys.indexOf(key).toLong())
                tags.varint(values.indexOf(value).toLong())
            }
            f.bytesField(2, tags.bytes())
            val type =
                if (feature.points.size == 1) {
                    1L
                } else if (feature.polygon) {
                    3L
                } else {
                    2L
                }
            f.varintField(3, type)
            f.bytesField(4, geometry(feature.points, feature.polygon))
            layer.bytesField(2, f.bytes())
        }
        keys.forEach { layer.bytesField(3, it.encodeToByteArray()) }
        values.forEach { value ->
            val v = Proto()
            when (value) {
                is String -> v.bytesField(1, value.encodeToByteArray())
                is Double -> v.fixed64Field(3, value.toRawBits())
                is Long -> v.varintField(4, value)
                is Boolean -> v.varintField(7, if (value) 1L else 0L)
                else -> error("No vector tile value for $value")
            }
            layer.bytesField(4, v.bytes())
        }
        layer.varintField(5, extent.toLong())
        tile.bytesField(3, layer.bytes())
    }
    return tile.bytes()
}

private fun geometry(
    points: List<Pair<Int, Int>>,
    polygon: Boolean,
): ByteArray {
    val out = Proto()
    var x = 0
    var y = 0

    fun move(to: Pair<Int, Int>) {
        out.varint(zigzag(to.first - x))
        out.varint(zigzag(to.second - y))
        x = to.first
        y = to.second
    }
    out.varint(1L or (1L shl 3))
    move(points.first())
    if (points.size > 1) {
        out.varint(2L or ((points.size - 1).toLong() shl 3))
        points.drop(1).forEach { move(it) }
    }
    if (polygon) out.varint(7L or (1L shl 3))
    return out.bytes()
}

private fun zigzag(value: Int): Long = ((value shl 1) xor (value shr 31)).toLong() and 0xFFFFFFFFL

private class Proto {
    private val out = ArrayList<Byte>()

    fun bytes(): ByteArray = out.toByteArray()

    fun varint(value: Long) {
        var v = value
        while (true) {
            val byte = (v and 0x7F).toInt()
            v = v ushr 7
            if (v == 0L) {
                out += byte.toByte()
                return
            }
            out += (byte or 0x80).toByte()
        }
    }

    fun varintField(
        number: Int,
        value: Long,
    ) {
        varint((number shl 3).toLong())
        varint(value)
    }

    fun fixed64Field(
        number: Int,
        value: Long,
    ) {
        varint((number shl 3 or 1).toLong())
        for (i in 0 until 8) out += (value ushr (8 * i)).toByte()
    }

    fun bytesField(
        number: Int,
        value: ByteArray,
    ) {
        varint((number shl 3 or 2).toLong())
        varint(value.size.toLong())
        value.forEach { out += it }
    }
}
