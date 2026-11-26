package to.pubky.maps.offline

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement

/**
 * The places in the vector tiles of an offline area, read from a copy of MapLibre's offline
 * database. Its layout is MapLibre's (schema version [SCHEMA_VERSION]): `regions` holds each pack
 * with the app's [PackMetadata] as its description, `region_tiles` links a pack to its tiles, and
 * `tiles` holds each tile's data, compressed with zlib when `compressed` is 1. [inflate] unpacks
 * zlib or gzip data (a map server may also send its tiles gzipped). Every call blocks on disk.
 */
class MapLibreTiles(
    private val driver: SqlDriver,
    private val inflate: (ByteArray) -> ByteArray,
) : TilePlaces {
    /** Thrown when the database is not laid out as this reader knows. */
    class UnknownLayout(
        message: String,
    ) : Exception(message)

    /**
     * The places in [area]'s tiles of zoom [OfflineLimits.MAX_ZOOM] (the deepest of the map's vector
     * tiles, which holds every named place), the newest tiles first; null when MapLibre has no pack
     * of [area]. MapLibre keeps the tiles of the releases before linked to a pack after an update, so
     * only the newest tile of each source at each place is read ([newestOfEachSource]): a shop that
     * closed or a street renamed since is not found. The tiles are read one at a time as the sequence
     * is walked.
     */
    override fun places(area: String): Sequence<OfflinePlace>? {
        val version = query("PRAGMA user_version", 0) { it.getLong(0) }.firstOrNull()
        if (version != SCHEMA_VERSION) throw UnknownLayout("MapLibre's offline database has version $version")
        val regions =
            query("SELECT id, description FROM regions", 0) { cursor ->
                cursor.getLong(0)?.takeIf { PackMetadata.decode(cursor.getBytes(1))?.area == area }
            }
        if (regions.isEmpty()) return null
        val linked =
            query(
                "SELECT DISTINCT tiles.id, tiles.url_template, tiles.x, tiles.y FROM region_tiles " +
                    "JOIN tiles ON tiles.id = region_tiles.tile_id " +
                    "WHERE region_tiles.region_id IN (${regions.joinToString(",") { "?" }}) AND tiles.z = ?",
                regions.size + 1,
                binders = {
                    regions.forEachIndexed { i, id -> bindLong(i, id) }
                    bindLong(regions.size, OfflineLimits.MAX_ZOOM.toLong())
                },
            ) { LinkedTile(it.getLong(0)!!, it.getString(1)!!, it.getLong(2)!!, it.getLong(3)!!) }
        val tiles = newestOfEachSource(linked)
        return tiles.asSequence().flatMap { id -> placesOfStoredTile(id) }
    }

    private fun placesOfStoredTile(id: Long): List<OfflinePlace> {
        val tile =
            query("SELECT z, x, y, data, compressed FROM tiles WHERE id = ?", 1, binders = { bindLong(0, id) }) { cursor ->
                val data = cursor.getBytes(3) ?: return@query null
                Triple(
                    Triple(cursor.getLong(0)!!.toInt(), cursor.getLong(1)!!.toInt(), cursor.getLong(2)!!.toInt()),
                    data,
                    cursor.getLong(4) == 1L,
                )
            }.firstOrNull() ?: return emptyList()
        val (zxy, data, compressed) = tile
        return try {
            var bytes = if (compressed) inflate(data) else data
            if (bytes.isGzip()) bytes = inflate(bytes)
            placesOfTile(zxy.first, zxy.second, zxy.third, decodeVectorTile(bytes))
        } catch (e: Exception) {
            // A tile this reader cannot read (another format, damaged): the area's other tiles still count.
            emptyList()
        }
    }

    private fun <T : Any> query(
        sql: String,
        parameters: Int,
        binders: (SqlPreparedStatement.() -> Unit)? = null,
        row: (SqlCursor) -> T?,
    ): List<T> =
        driver
            .executeQuery(
                identifier = null,
                sql = sql,
                mapper = { cursor ->
                    val rows = ArrayList<T>()
                    while (cursor.next().value) row(cursor)?.let { rows += it }
                    QueryResult.Value(rows)
                },
                parameters = parameters,
                binders = binders,
            ).value

    companion object {
        /** The layout of MapLibre's offline database this reader knows (`PRAGMA user_version`). */
        const val SCHEMA_VERSION = 6L
    }
}

/** A tile linked to a pack: its row, address and place in the grid. */
internal class LinkedTile(
    val id: Long,
    val urlTemplate: String,
    val x: Long,
    val y: Long,
)

/**
 * The ids of the newest of [tiles] (the highest id: MapLibre adds a release's tiles after those of
 * the one before) for each source and place in the grid, newest first. A map server's release is a
 * part of its tile address with digits (OpenFreeMap: `.../planet/20251119_001001_pt/{z}/{x}/{y}.pbf`),
 * so addresses that differ only in such parts are one source; a style's other sources keep theirs.
 */
internal fun newestOfEachSource(tiles: List<LinkedTile>): List<Long> =
    tiles
        .groupBy { Triple(sourceOf(it.urlTemplate), it.x, it.y) }
        .values
        .map { same -> same.maxOf { it.id } }
        .sortedDescending()

/** A tile address without the parts that name a release ([newestOfEachSource]). */
private fun sourceOf(urlTemplate: String): String = urlTemplate.split('/').filterNot { part -> part.any { it.isDigit() } }.joinToString("/")

private fun ByteArray.isGzip(): Boolean = size >= 2 && this[0] == 0x1f.toByte() && this[1] == 0x8b.toByte()
