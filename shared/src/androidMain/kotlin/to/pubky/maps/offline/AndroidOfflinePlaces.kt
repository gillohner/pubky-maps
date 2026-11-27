package to.pubky.maps.offline

import android.content.Context
import android.net.ConnectivityManager
import androidx.sqlite.db.SupportSQLiteDatabase
import app.cash.sqldelight.db.AfterVersion
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import to.pubky.maps.config.AppSettings
import to.pubky.maps.config.Service
import to.pubky.maps.offline.db.OfflinePlacesDatabase
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.zip.GZIPInputStream
import java.util.zip.InflaterInputStream

/**
 * The places of the offline areas and their OSM details, in one file in the no-backup folder (they
 * are built again from the tiles and fetched again from Overpass). Write-ahead logging lets a search
 * read while an area's places are being written, which takes one transaction of up to tens of
 * seconds. Give the driver to [SqlOfflinePlaceIndex] and [SqlOfflineDetails]; one driver per process.
 */
fun androidOfflinePlacesDriver(context: Context): SqlDriver =
    AndroidSqliteDriver(
        schema = OfflinePlacesDatabase.Schema,
        context = context,
        name = File(context.noBackupFilesDir, "offline-places.db").path,
        callback =
            object : AndroidSqliteDriver.Callback(OfflinePlacesDatabase.Schema) {
                override fun onConfigure(db: SupportSQLiteDatabase) {
                    db.enableWriteAheadLogging()
                }
            },
    )

/**
 * Keeps the OSM details of [maps]' areas in [store] for the life of the process, from the Overpass
 * servers of the user's list in [settings]; refreshed only on a network Android does not count as
 * metered.
 */
fun startOfflinePlaceDetails(
    context: Context,
    scope: CoroutineScope,
    maps: OfflineMaps,
    settings: StateFlow<AppSettings?>,
    appVersion: String,
    store: OfflineDetailsStore,
) {
    val connectivity = context.getSystemService(ConnectivityManager::class.java)
    OfflinePlaceDetails(
        maps = maps.state,
        source =
            OverpassPlaceDetails(
                client = overpassHttpClient(appVersion),
                servers = { (settings.value ?: AppSettings()).servers[Service.Overpass].map { it.url } },
            ),
        store = store,
        scope = scope,
        unmetered = { connectivity?.isActiveNetworkMetered == false },
    ).start()
}

/**
 * Starts keeping [index] in step with [maps], reading the tiles from MapLibre's database
 * (`no_backup/maplibre.db`, see [configureMapRuntime]) through a copy in the cache folder.
 */
fun startOfflinePlaceIndexer(
    context: Context,
    scope: CoroutineScope,
    maps: OfflineMaps,
    index: SqlOfflinePlaceIndex,
) {
    val tiles =
        MapLibreDatabaseCopy(
            database = File(context.noBackupFilesDir, "maplibre.db"),
            copy = File(context.cacheDir, "maplibre-copy.db"),
            openCopy = { AndroidSqliteDriver(MapLibreLayout, context, it.path) },
        )
    scope.launch(Dispatchers.IO) { tiles.deleteCopy() }
    OfflinePlaceIndexer(maps.state, maps.fetchingChanges, index, tiles, scope).start()
}

/**
 * Reads MapLibre's offline database through a copy of its file. MapLibre has its own SQLite inside
 * its native library; a second SQLite in the same process opening the same file does not see the
 * first one's locks (POSIX locks are per process) and could take a write in progress for a crashed
 * one and roll it back. A plain copy only reads the file. It counts only if the file had no journal
 * (no write in progress) and the same size and time before and after, else the read is tried later.
 * [openCopy] opens the copy with [MapLibreLayout] as its schema.
 */
internal class MapLibreDatabaseCopy(
    private val database: File,
    private val copy: File,
    private val openCopy: (File) -> SqlDriver,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : AreaTiles {
    override suspend fun <T> open(read: (TilePlaces) -> T): T? =
        withContext(io) {
            try {
                // A copy left by a process that died, with its journal, must not meet the new one.
                deleteCopy()
                if (!copyUnchanged(database, copy)) return@withContext null
                val driver = openCopy(copy)
                try {
                    read(MapLibreTiles(driver, ::inflateZlibOrGzip))
                } finally {
                    driver.close()
                }
            } finally {
                deleteCopy()
            }
        }

    /** Deletes the copy and its journal files, also those a process that died left. */
    fun deleteCopy() {
        for (suffix in listOf("", "-journal", "-wal", "-shm")) File(copy.path + suffix).delete()
    }
}

/**
 * Copies [database] to [copy] when no write runs on it; false when one does (its rollback journal
 * exists, its size or time changed during the copy, or the copy's SQLite change counter is not the
 * file's after the copy), when it is missing, or when there is no room for the copy and what SQLite
 * may need to open it ([usableSpace] of the copy's folder). [copyFile] copies.
 */
internal fun copyUnchanged(
    database: File,
    copy: File,
    usableSpace: (File) -> Long = { it.usableSpace },
    copyFile: (File, File) -> Unit = { from, to -> Files.copy(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING) },
): Boolean {
    if (!database.isFile) return false
    val before = stamp(database) ?: return false
    val folder = copy.absoluteFile.parentFile ?: return false
    if (usableSpace(folder) < 2 * before.first) return false
    try {
        copyFile(database, copy)
    } catch (e: IOException) {
        return false
    }
    return stamp(database) == before && changeCounter(copy)?.contentEquals(changeCounter(database)) == true
}

/** SQLite's file change counter (header bytes 24 to 27), which every write transaction raises. */
private fun changeCounter(file: File): ByteArray? =
    try {
        RandomAccessFile(file, "r").use { f ->
            ByteArray(4).also {
                f.seek(24)
                f.readFully(it)
            }
        }
    } catch (e: IOException) {
        null
    }

/** Size and modification time of [database], or null while its rollback journal says a write runs. */
private fun stamp(database: File): Pair<Long, Any>? {
    if (File(database.path + "-journal").exists()) return null
    val attributes =
        try {
            Files.readAttributes(database.toPath(), BasicFileAttributes::class.java)
        } catch (e: IOException) {
            return null
        }
    return attributes.size() to attributes.lastModifiedTime()
}

/**
 * MapLibre's layout as the copy's schema: a copy is never created or migrated here, so a database
 * of another version fails to open (the read is tried later) instead of being changed.
 */
internal object MapLibreLayout : SqlSchema<QueryResult.Value<Unit>> {
    override val version: Long = MapLibreTiles.SCHEMA_VERSION

    override fun create(driver: SqlDriver): QueryResult.Value<Unit> =
        throw MapLibreTiles.UnknownLayout("The copy of MapLibre's database is empty")

    override fun migrate(
        driver: SqlDriver,
        oldVersion: Long,
        newVersion: Long,
        vararg callbacks: AfterVersion,
    ): QueryResult.Value<Unit> = throw MapLibreTiles.UnknownLayout("MapLibre's offline database has version $oldVersion")
}

/** Unpacks zlib data (MapLibre's compressed tiles) or gzip data (a tile as some servers send it). */
internal fun inflateZlibOrGzip(bytes: ByteArray): ByteArray {
    val input = ByteArrayInputStream(bytes)
    val gzip = bytes.size >= 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte()
    return (if (gzip) GZIPInputStream(input) else InflaterInputStream(input)).use { it.readBytes() }
}
