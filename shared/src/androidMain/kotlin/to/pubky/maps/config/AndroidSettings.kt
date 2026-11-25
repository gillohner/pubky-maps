package to.pubky.maps.config

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import okio.FileSystem
import okio.Path.Companion.toOkioPath

/** The settings store of the app, in the app's private files. Create it once per process. */
fun androidSettingsRepository(
    context: Context,
    scope: CoroutineScope,
): SettingsRepository =
    DataStoreSettingsRepository(
        fileSystem = FileSystem.SYSTEM,
        path = context.filesDir.toOkioPath() / "datastore" / "settings.json",
        scope = scope,
    )
