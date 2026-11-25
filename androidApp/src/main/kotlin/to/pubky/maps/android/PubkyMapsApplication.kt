package to.pubky.maps.android

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import to.pubky.maps.account.AccountUi
import to.pubky.maps.account.CoreServers
import to.pubky.maps.account.SharedCore
import to.pubky.maps.account.createPubkyMapsCore
import to.pubky.maps.android.update.startUpdater
import to.pubky.maps.config.InMemoryServerConfigRepository
import to.pubky.maps.config.ServerConfigRepository
import to.pubky.maps.config.Service
import to.pubky.maps.config.SettingsRepository
import to.pubky.maps.config.androidSettingsRepository
import to.pubky.maps.links.CoreMapLinks
import to.pubky.maps.links.LinkedPlaces
import to.pubky.maps.links.MapLinks
import to.pubky.maps.status.ServiceHealth
import to.pubky.maps.status.ServicePart
import to.pubky.maps.update.UpdateController

/** Holds what lives as long as the process, so it survives Activity recreation. */
class PubkyMapsApplication : Application() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** The one settings store of the process (DataStore allows one per file). */
    val settingsRepository: SettingsRepository by lazy { androidSettingsRepository(this, scope) }

    /** The update feeds of this build; not user settings. */
    private val serverConfigRepository: ServerConfigRepository = InMemoryServerConfigRepository()

    /** Which parts of the app are missing because every server of their list failed. */
    val serviceHealth = ServiceHealth()

    /** The in-app updater; null in builds an app store updates and in local builds. */
    var updates: UpdateController? = null
        private set

    /**
     * The one Rust core, with the Pkarr relays and sign-in relays of the user's server lists (a change
     * applies from the next start). Built off the main thread by whoever needs it first.
     */
    private val core =
        SharedCore {
            val servers =
                settingsRepository.settings
                    .filterNotNull()
                    .first()
                    .servers
            createPubkyMapsCore(
                CoreServers(
                    pkarrRelays = servers[Service.PkarrRelay].map { it.url },
                    authRelays = servers[Service.AuthRelay].map { it.url },
                ),
            )
        }

    /** Map links, read and built by the core. */
    val mapLinks: MapLinks = CoreMapLinks(core::get)

    /** The place a link from another app opened; kept for the whole process like the map's other state. */
    val linkedPlaces =
        LinkedPlaces(mapLinks, scope) { reachable ->
            if (reachable) serviceHealth.recovered(ServicePart.Pinakes) else serviceHealth.failed(ServicePart.Pinakes)
        }

    lateinit var account: AccountUi
        private set

    override fun onCreate() {
        super.onCreate()
        updates = startUpdater(this, serverConfigRepository)
        // Restores a stored session in the background; the map never waits for it.
        account = startAccount(this, scope, serviceHealth, core)
    }
}
