package to.pubky.maps.search

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import okio.FileSystem
import okio.Path.Companion.toOkioPath
import to.pubky.maps.config.AppSettings
import to.pubky.maps.config.Service
import java.util.Locale

/**
 * Search for the app process: Photon and Pinakes through the user's server lists in [settings], and
 * the recent searches in the app's no-backup folder, so they never leave the phone, not even in an
 * Android backup. Create it once per process.
 */
fun androidSearchServices(
    context: Context,
    scope: CoroutineScope,
    settings: StateFlow<AppSettings?>,
    appVersion: String,
): SearchServices {
    val recents =
        DataStoreRecentSearchStore(
            fileSystem = FileSystem.SYSTEM,
            path = context.noBackupFilesDir.toOkioPath() / "recent-searches.json",
            scope = scope,
        )

    fun servers(service: Service): List<String> = (settings.value ?: AppSettings()).servers[service].map { it.url }
    val search =
        HttpPlaceSearch(
            client = searchHttpClient(appVersion),
            photonServers = { servers(Service.Photon) },
            pinakesServers = { servers(Service.Pinakes) },
            language = { Locale.getDefault().language },
        )
    // The controller's state lives on the main thread; requests run on Ktor's own IO threads.
    val controllerScope = CoroutineScope(scope.coroutineContext + Dispatchers.Main.immediate)
    return SearchServices(SearchController(search, recents, controllerScope), recents)
}
