package to.pubky.maps.android

import android.app.Application
import to.pubky.maps.config.InMemoryServerConfigRepository
import to.pubky.maps.config.ServerConfigRepository

/** Holds what lives as long as the process, so it survives Activity recreation. */
class PubkyMapsApplication : Application() {
    val serverConfigRepository: ServerConfigRepository = InMemoryServerConfigRepository()
}
