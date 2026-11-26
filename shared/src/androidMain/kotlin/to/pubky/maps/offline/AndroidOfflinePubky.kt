package to.pubky.maps.offline

import android.content.Context
import android.net.ConnectivityManager
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import kotlinx.coroutines.CoroutineScope
import to.pubky.maps.offline.pubky.db.OfflinePubkyDatabase
import to.pubky.maps.place.PlaceData
import java.io.File

/**
 * Where the Pubky places of the offline areas and their tags and counts are kept: their own file in
 * the no-backup folder (never in an Android backup).
 */
fun androidOfflinePubkyStore(context: Context): SqlOfflinePubkyStore =
    SqlOfflinePubkyStore(
        AndroidSqliteDriver(OfflinePubkyDatabase.Schema, context, File(context.noBackupFilesDir, "offline-pubky-places.db").path),
    )

/**
 * Keeps the Pubky places of [maps]' areas in [store] for the life of the process, fetched from
 * Pinakes through [data], with their tags and counts from Nexus. The places and tags are refreshed
 * only on a network Android does not count as metered; tags Nexus did not give are asked again on
 * any network.
 */
fun startOfflinePubkyPlaces(
    context: Context,
    scope: CoroutineScope,
    maps: OfflineMaps,
    data: PlaceData,
    store: OfflinePubkyStore,
) {
    val connectivity = context.getSystemService(ConnectivityManager::class.java)
    OfflinePubkyPlaces(
        maps = maps.state,
        data = data,
        store = store,
        scope = scope,
        unmetered = { connectivity?.isActiveNetworkMetered == false },
    ).start()
}
