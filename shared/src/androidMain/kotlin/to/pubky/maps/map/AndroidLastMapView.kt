package to.pubky.maps.map

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import okio.FileSystem
import okio.Path.Companion.toOkioPath

/**
 * Where the user left the map, in the no-backup folder (it shows where they look, often their home,
 * so it stays on this phone). Create it once per process.
 */
fun androidLastMapViewStore(
    context: Context,
    scope: CoroutineScope,
): LastMapViewStore =
    DataStoreLastMapViewStore(
        fileSystem = FileSystem.SYSTEM,
        producePath = { context.noBackupFilesDir.toOkioPath() / "map-view.json" },
        scope = scope,
    )
