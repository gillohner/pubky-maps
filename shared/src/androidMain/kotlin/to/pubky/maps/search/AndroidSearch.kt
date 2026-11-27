package to.pubky.maps.search

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import okio.FileSystem
import okio.Path.Companion.toOkioPath
import to.pubky.maps.config.AppSettings
import to.pubky.maps.config.Service
import to.pubky.maps.offline.OfflinePlaceSearch
import to.pubky.maps.status.ServiceHealth
import java.util.Locale

/**
 * Search for the app process: Photon and Pinakes through the user's server lists in [settings], and
 * the recent searches in the app's no-backup folder, so they never leave the phone, not even in an
 * Android backup. [viewer] is the signed-in user's pubky id (null when signed out), for Pinakes'
 * friends ranking; [health] gets search's outages for the map's degraded line; [offline] answers
 * from the offline maps' places when Photon cannot be reached; [hours] gives results the opening
 * hours the offline areas keep. Create it once per process.
 */
fun androidSearchServices(
    context: Context,
    scope: CoroutineScope,
    settings: StateFlow<AppSettings?>,
    appVersion: String,
    viewer: () -> String?,
    health: ServiceHealth,
    offline: OfflinePlaceSearch,
    hours: KeptOpeningHours,
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
    val controller = SearchController(search, recents, controllerScope, viewer = viewer, health = health, offline = offline, hours = hours)
    return SearchServices(controller, recents)
}
