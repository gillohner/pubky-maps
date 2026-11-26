package to.pubky.maps.offline

import android.content.Context
import android.net.ConnectivityManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.io.files.Path
import org.maplibre.compose.map.DefaultMapRuntime
import org.maplibre.compose.offline.offlineStorage
import org.maplibre.compose.util.ExperimentalMaplibreComposeApi
import java.io.File

/**
 * Sets up MapLibre for the process, before its first map: its database (the map cache and the
 * offline maps) in the no-backup folder, and [OfflineMapResources] for the requests of offline
 * downloads while [downloading], telling [fetchedNewData] when one got new map data. MapLibre's
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
) {
    scope.launch {
        context.cacheDir.listFiles { file -> file.name.startsWith("maplibre-cache.db") }?.forEach { it.delete() }
    }
    DefaultMapRuntime.configure {
        cacheFile = Path(File(context.noBackupFilesDir, "maplibre.db").path)
        resourceProvider = OfflineMapResources({ offlineHttpClient(appVersion) }, downloading, fetchedNewData = fetchedNewData)
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
