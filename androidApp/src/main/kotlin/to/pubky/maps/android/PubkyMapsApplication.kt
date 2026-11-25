package to.pubky.maps.android

import android.app.Application
import android.net.ConnectivityManager
import android.net.Network
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import to.pubky.maps.BuildConfig
import to.pubky.maps.account.AccountState
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
import to.pubky.maps.place.PlaceServices
import to.pubky.maps.place.androidPlaceServices
import to.pubky.maps.search.SearchServices
import to.pubky.maps.search.androidSearchServices
import to.pubky.maps.status.ServiceHealth
import to.pubky.maps.status.ServicePart
import to.pubky.maps.update.UpdateController
import to.pubky.maps.write.WriteController
import to.pubky.maps.write.androidWriteController

/** Holds what lives as long as the process, so it survives Activity recreation. */
class PubkyMapsApplication : Application() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** The one settings store of the process (DataStore allows one per file). */
    val settingsRepository: SettingsRepository by lazy { androidSettingsRepository(this, scope) }

    /** The update feeds of this build; not user settings. */
    private val serverConfigRepository: ServerConfigRepository = InMemoryServerConfigRepository()

    /** Which parts of the app are missing because every server of their list failed. */
    val serviceHealth = ServiceHealth()

    /**
     * Search and the recent searches, kept while the process lives (rotation keeps the results).
     * Pinakes ranks what the signed-in user's follows referenced higher.
     */
    val search: SearchServices by lazy {
        androidSearchServices(
            context = this,
            scope = scope,
            settings = settingsRepository.settings,
            appVersion = BuildConfig.VERSION_NAME,
            viewer = { signedInKey() },
            health = serviceHealth,
        )
    }

    /** Place pages and the map's Pubky places layer, with the place cache in the no-backup folder. */
    val places: PlaceServices by lazy {
        androidPlaceServices(
            context = this,
            scope = scope,
            settings = settingsRepository.settings,
            appVersion = BuildConfig.VERSION_NAME,
            viewer = { signedInKey() },
            health = serviceHealth,
        )
    }

    private fun signedInKey(): String? =
        if (::account.isInitialized) (account.controller.state.value as? AccountState.SignedIn)?.user?.publicKey else null

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

    /** The user's writes about places (tags, posts, photos, lists, saved places) and their outbox. */
    lateinit var writes: WriteController
        private set

    override fun onCreate() {
        super.onCreate()
        updates = startUpdater(this, serverConfigRepository)
        // Restores a stored session in the background; the map never waits for it.
        val (started, cacheDriver) = startAccount(this, scope, serviceHealth, core)
        account = started
        writes =
            androidWriteController(
                context = this,
                scope = CoroutineScope(scope.coroutineContext + Dispatchers.Main.immediate),
                account = account.controller,
                accountDriver = cacheDriver,
                core = core,
                settings = settingsRepository.settings,
            )
        // Writes that wait for a connection are sent as soon as the phone has one.
        getSystemService(ConnectivityManager::class.java)?.registerDefaultNetworkCallback(
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = writes.flush()
            },
        )
    }
}
