package to.pubky.maps.offline

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.io.files.Path
import org.maplibre.compose.map.DefaultMapRuntime
import org.maplibre.compose.offline.offlineStorage
import org.maplibre.compose.util.ExperimentalMaplibreComposeApi
import java.io.File

/**
 * Sets up MapLibre for the process, before its first map: its database (the map cache and the
 * offline maps) in the no-backup folder, and [OfflineMapResources] for the requests of offline
 * downloads while [downloading]. MapLibre's default database is in the cache folder, which Android
 * empties when the phone runs low on storage, so the one from before is deleted.
 */
@OptIn(ExperimentalMaplibreComposeApi::class)
fun configureMapRuntime(
    context: Context,
    appVersion: String,
    downloading: () -> Boolean,
) {
    context.cacheDir.listFiles { file -> file.name.startsWith("maplibre-cache.db") }?.forEach { it.delete() }
    DefaultMapRuntime.configure {
        cacheFile = Path(File(context.noBackupFilesDir, "maplibre.db").path)
        resourceProvider = OfflineMapResources(offlineHttpClient(appVersion), downloading)
    }
}

/** The offline maps of the process, in MapLibre's database; created once, after [configureMapRuntime]. */
fun androidOfflineMaps(
    context: Context,
    scope: CoroutineScope,
): OfflineMaps =
    OfflineMaps(
        MapLibrePackStore({ DefaultMapRuntime.instance.offlineStorage }, context.resources.displayMetrics.density),
        scope,
    )
