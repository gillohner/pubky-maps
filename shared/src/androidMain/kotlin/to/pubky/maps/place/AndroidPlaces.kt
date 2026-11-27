package to.pubky.maps.place

import android.content.Context
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import to.pubky.maps.config.AppSettings
import to.pubky.maps.config.Service
import to.pubky.maps.map.MapPlacesLayer
import to.pubky.maps.offline.KeptPubkyPlaces
import to.pubky.maps.place.db.PlaceCacheDatabase
import to.pubky.maps.search.searchHttpClient
import to.pubky.maps.status.ServiceHealth
import java.io.File

/**
 * Place pages for the app process: Pinakes and Nexus through the user's server lists in [settings],
 * the place cache in the no-backup folder (never in an Android backup), and the Pubky places layer.
 * [viewer] is the signed-in user's pubky id, null when signed out; [health] gets the layer's Pinakes
 * outages for the map's degraded line; the layer shows the Pubky places of the offline areas
 * ([offline]) when Pinakes cannot be reached, and a page their tags when Nexus cannot; a page with
 * no address names its district and town from the offline areas' labels ([localities]), and a page
 * with no card shows the OSM details the offline areas keep ([details]). Create it once per process.
 */
fun androidPlaceServices(
    context: Context,
    scope: CoroutineScope,
    settings: StateFlow<AppSettings?>,
    appVersion: String,
    viewer: () -> String?,
    health: ServiceHealth,
    offline: KeptPubkyPlaces,
    localities: OfflineLocality,
    details: KeptPlaceDetails,
): PlaceServices {
    fun servers(service: Service): List<String> = (settings.value ?: AppSettings()).servers[service].map { it.url }
    val data =
        HttpPlaceData(
            client = searchHttpClient(appVersion),
            pinakesServers = { servers(Service.Pinakes) },
            nexusServers = { servers(Service.Nexus) },
        )
    val driver = AndroidSqliteDriver(PlaceCacheDatabase.Schema, context, File(context.noBackupFilesDir, "place-cache.db").path)
    // State lives on the main thread; requests run on Ktor's own threads, the cache on IO.
    val main = CoroutineScope(scope.coroutineContext + Dispatchers.Main.immediate)
    return PlaceServices(
        pages =
            PlacePageController(
                data,
                SqlPlaceCache(driver),
                main,
                kept = offline,
                viewer = viewer,
                localities = localities,
                details = details,
            ),
        layer = MapPlacesLayer(data, main, health = health, offline = offline),
        data = data,
    )
}
