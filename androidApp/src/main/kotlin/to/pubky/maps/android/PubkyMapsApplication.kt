package to.pubky.maps.android

import android.app.Application
import to.pubky.maps.android.update.startUpdater
import to.pubky.maps.config.InMemoryServerConfigRepository
import to.pubky.maps.config.ServerConfigRepository
import to.pubky.maps.update.UpdateController

/** Holds what lives as long as the process, so it survives Activity recreation. */
class PubkyMapsApplication : Application() {
    val serverConfigRepository: ServerConfigRepository = InMemoryServerConfigRepository()

    /** The in-app updater; null in builds an app store updates and in local builds. */
    var updates: UpdateController? = null
        private set

    override fun onCreate() {
        super.onCreate()
        updates = startUpdater(this, serverConfigRepository)
    }
}
