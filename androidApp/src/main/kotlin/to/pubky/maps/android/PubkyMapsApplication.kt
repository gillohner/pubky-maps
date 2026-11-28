package to.pubky.maps.android

import android.app.Application
import android.net.ConnectivityManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import to.pubky.maps.BuildConfig
import to.pubky.maps.account.AccountState
import to.pubky.maps.account.AccountUi
import to.pubky.maps.account.CoreServers
import to.pubky.maps.account.SharedCore
import to.pubky.maps.account.androidWebPagePlaces
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
import to.pubky.maps.map.LastMapViewStore
import to.pubky.maps.map.androidLastMapViewStore
import to.pubky.maps.offline.OfflineMaps
import to.pubky.maps.offline.SqlOfflineDetails
import to.pubky.maps.offline.SqlOfflinePlaceIndex
import to.pubky.maps.offline.SqlOfflinePubkyStore
import to.pubky.maps.offline.androidOfflineMaps
import to.pubky.maps.offline.androidOfflinePlacesDriver
import to.pubky.maps.offline.androidOfflinePubkyStore
import to.pubky.maps.offline.configureMapRuntime
import to.pubky.maps.offline.startMapRuntime
import to.pubky.maps.offline.startOfflinePlaceDetails
import to.pubky.maps.offline.startOfflinePlaceIndexer
import to.pubky.maps.offline.startOfflinePubkyPlaces
import to.pubky.maps.place.PlaceServices
import to.pubky.maps.place.androidPlaceServices
import to.pubky.maps.search.SearchServices
import to.pubky.maps.search.androidSearchServices
import to.pubky.maps.search.openingHours
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

    /** Where the user left the map; read with the settings when the first screen asks for them. */
    val lastMapView: LastMapViewStore by lazy { androidLastMapViewStore(this, scope) }

    /** The update feeds of this build; not user settings. */
    private val serverConfigRepository: ServerConfigRepository = InMemoryServerConfigRepository()

    /** Which parts of the app are missing because every server of their list failed. */
    val serviceHealth = ServiceHealth()

    /**
     * The places of the offline maps, which search answers from when Photon cannot be reached, and
     * which name the district and town of a place page with no address.
     */
    private val offlinePlaces: SqlOfflinePlaceIndex by lazy { SqlOfflinePlaceIndex(offlinePlacesDriver) }

    /**
     * The OSM details (opening hours, phone, ...) of the offline maps' places, for pages whose card has
     * no OSM details, and the opening hours of search's results.
     */
    private val offlineDetails: SqlOfflineDetails by lazy { SqlOfflineDetails(offlinePlacesDriver) }

    private val offlinePlacesDriver by lazy { androidOfflinePlacesDriver(this) }

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
            offline = offlinePlaces,
            hours = offlineDetails.openingHours(),
        )
    }

    /** The Pubky places of the offline maps, which the map shows when Pinakes cannot be reached. */
    private val offlinePubky: SqlOfflinePubkyStore by lazy { androidOfflinePubkyStore(this) }

    /** Place pages and the map's Pubky places layer, with the place cache in the no-backup folder. */
    val places: PlaceServices by lazy {
        androidPlaceServices(
            context = this,
            scope = scope,
            settings = settingsRepository.settings,
            appVersion = BuildConfig.VERSION_NAME,
            viewer = { signedInKey() },
            health = serviceHealth,
            offline = offlinePubky,
            localities = offlinePlaces,
            details = offlineDetails,
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

    /** Set once [offlineMaps] exists; MapLibre's threads read it for every request. */
    @Volatile
    private var offlineStarted: OfflineMaps? = null

    /**
     * The offline maps; made by the first screen that needs them, as MapLibre starts with the map.
     * Their places are read into [offlinePlaces] as areas become ready, the Pubky places of their
     * areas are kept in [offlinePubky] and the OSM details of their places in [offlineDetails].
     */
    val offlineMaps: OfflineMaps by lazy {
        androidOfflineMaps(this, CoroutineScope(scope.coroutineContext + Dispatchers.Main.immediate)).also {
            offlineStarted = it
            startOfflinePubkyPlaces(this, scope, it, places.data, offlinePubky)
            startOfflinePlaceIndexer(this, scope, it, offlinePlaces)
            startOfflinePlaceDetails(this, scope, it, settingsRepository.settings, BuildConfig.VERSION_NAME, offlineDetails)
        }
    }

    /** Makes MapLibre's runtime in the background; MainActivity calls it as it is created. */
    fun prepareMapRuntime() = startMapRuntime(scope)

    /** The user's writes about places (tags, posts, photos, lists, saved places) and their outbox. */
    lateinit var writes: WriteController
        private set

    /**
     * Whether writes or notes to OpenStreetMap wait for a connection, for the outbox job; null until
     * both were read from the phone.
     */
    val outboxWaiting: StateFlow<Boolean?> by lazy {
        combine(writes.waiting, places.notes?.waiting ?: MutableStateFlow(false), ::anyWaiting)
            .stateIn(scope, SharingStarted.Eagerly, null)
    }

    /** Asks the account, the outbox and the notes to try now. */
    fun sendWaitingNow() {
        account.controller.onNetworkAvailable()
        writes.flush()
        places.notes?.flush()
    }

    override fun onCreate() {
        super.onCreate()
        configureMapRuntime(
            this,
            BuildConfig.VERSION_NAME,
            scope,
            downloading = { offlineStarted?.downloading == true },
            fetchedNewData = { offlineStarted?.fetchedNewData() },
            keepsCopy = { offlineStarted?.hasStyle(it) == true },
        )
        updates = startUpdater(this, serverConfigRepository)
        // Restores a stored session in the background; the map never waits for it.
        // Bookmarked web pages are checked on Pinakes through the place services' client and server list.
        val (started, cacheDriver) = startAccount(this, scope, serviceHealth, core, androidWebPagePlaces { places.data })
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
        // With the app closed too: a job Android runs when the phone has a network starts the process.
        followOutbox(this, outboxWaiting, scope)
        // Writes and notes that wait for a connection are sent as soon as the phone has one: the
        // homeserver is tried again first (writes wait for it), then the outbox, and the notes.
        getSystemService(ConnectivityManager::class.java)?.registerDefaultNetworkCallback(
            NetworkReturns(::sendWaitingNow),
        )
    }
}
