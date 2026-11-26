package to.pubky.maps.offline

import android.content.Context
import android.net.ConnectivityManager
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import kotlinx.coroutines.CoroutineScope
import to.pubky.maps.offline.pubky.db.OfflinePubkyDatabase
import to.pubky.maps.place.PlaceData
import java.io.File

/** Where the Pubky places of the offline areas are kept: their own file in the no-backup folder (never in an Android backup). */
fun androidOfflinePubkyStore(context: Context): SqlOfflinePubkyStore =
    SqlOfflinePubkyStore(
        AndroidSqliteDriver(OfflinePubkyDatabase.Schema, context, File(context.noBackupFilesDir, "offline-pubky-places.db").path),
    )

/**
 * Keeps the Pubky places of [maps]' areas in [store] for the life of the process, fetched from
 * Pinakes through [data]; refreshed only on a network Android does not count as metered.
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
