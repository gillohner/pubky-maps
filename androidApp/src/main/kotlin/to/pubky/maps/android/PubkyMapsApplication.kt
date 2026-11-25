package to.pubky.maps.android

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import to.pubky.maps.android.update.startUpdater
import to.pubky.maps.config.InMemoryServerConfigRepository
import to.pubky.maps.config.ServerConfigRepository
import to.pubky.maps.config.SettingsRepository
import to.pubky.maps.config.androidSettingsRepository
import to.pubky.maps.update.UpdateController

/** Holds what lives as long as the process, so it survives Activity recreation. */
class PubkyMapsApplication : Application() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** The one settings store of the process (DataStore allows one per file). */
    val settingsRepository: SettingsRepository by lazy { androidSettingsRepository(this, scope) }

    /** The update feeds of this build; not user settings. */
    private val serverConfigRepository: ServerConfigRepository = InMemoryServerConfigRepository()

    /** The in-app updater; null in builds an app store updates and in local builds. */
    var updates: UpdateController? = null
        private set

    override fun onCreate() {
        super.onCreate()
        updates = startUpdater(this, serverConfigRepository)
    }
}
