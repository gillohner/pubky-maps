package to.pubky.maps.offline

import android.content.Context
import android.net.ConnectivityManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.io.files.Path
import org.maplibre.compose.map.DefaultMapRuntime
import org.maplibre.compose.offline.offlineStorage
import org.maplibre.compose.util.ExperimentalMaplibreComposeApi
import java.io.File

/**
 * Sets up MapLibre for the process, before its first map: its database (the map cache and the
 * offline maps) in the no-backup folder, and [OfflineMapResources] for the map's styles and, while
 * [downloading], every request of the map, telling [fetchedNewData] when a download's request got
 * new map data; a style [keepsCopy] says is an offline area's keeps its cached copy without its server. MapLibre's
 * default database is in the cache folder, which Android empties when the phone runs low on
 * storage, so the one from before is deleted in [scope]: this runs on the main thread at app start,
 * and listing and deleting files there is disk work the start waits for (StrictMode fails the
 * emulator tests on it). MapLibre never opens the old file, so it can go later.
 */
@OptIn(ExperimentalMaplibreComposeApi::class)
fun configureMapRuntime(
    context: Context,
    appVersion: String,
    scope: CoroutineScope,
    downloading: () -> Boolean,
    fetchedNewData: () -> Unit,
    keepsCopy: suspend (styleUrl: String) -> Boolean,
) {
    scope.launch {
        context.cacheDir.listFiles { file -> file.name.startsWith("maplibre-cache.db") }?.forEach { it.delete() }
    }
    DefaultMapRuntime.configure {
        cacheFile = Path(File(context.noBackupFilesDir, "maplibre.db").path)
        resourceProvider =
            OfflineMapResources(
                { offlineHttpClient(appVersion) },
                downloading,
                fetchedNewData = fetchedNewData,
                keepsCopy = keepsCopy,
            )
    }
}

/**
 * Makes MapLibre's runtime for the process in [scope], after [configureMapRuntime]. Making it loads
 * MapLibre's native library (about 14 MB) and starts its thread. A screen with the map calls this
 * as it is created, so the first composition, where the offline maps read their packs from the
 * runtime, finds it made or waits only for the rest of its making, instead of loading the library on
 * the main thread. Starts with no screen (the outbox job) never make it. A runtime that cannot be made
 * is logged here and fails again where a screen uses it. StrictMode holds a runtime made on the main
 * thread against our code (`StrictModeProvider.kt`).
 */
fun startMapRuntime(
    scope: CoroutineScope,
    make: () -> Unit = { DefaultMapRuntime.instance },
) {
    scope.launch {
        runCatching(make).onFailure { Log.w("PubkyMapsMap", "MapLibre's runtime could not be made", it) }
    }
}

/**
 * The offline maps of the process, in MapLibre's database; created once, after [configureMapRuntime].
 * They check for new map data only on a network Android does not count as metered (no network
 * counts as metered); an update they began pauses when the phone moves to a metered network (a
 * moment without a network pauses only a walk whose first seconds, [QUIET_CHECK], end in it).
 */
fun androidOfflineMaps(
    context: Context,
    scope: CoroutineScope,
): OfflineMaps {
    val connectivity = context.getSystemService(ConnectivityManager::class.java)
    return OfflineMaps(
        store = MapLibrePackStore({ DefaultMapRuntime.instance.offlineStorage }, context.resources.displayMetrics.density),
        scope = scope,
        unmetered = { connectivity?.isActiveNetworkMetered == false },
        metered = { connectivity?.activeNetwork != null && connectivity.isActiveNetworkMetered },
    )
}
