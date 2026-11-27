package to.pubky.maps

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import coil3.ImageLoader
import coil3.compose.setSingletonImageLoaderFactory
import coil3.network.ktor3.KtorNetworkFetcherFactory
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import to.pubky.maps.account.AccountRoute
import to.pubky.maps.account.AccountState
import to.pubky.maps.account.AccountUi
import to.pubky.maps.account.accountPlaceLabel
import to.pubky.maps.config.AppSettings
import to.pubky.maps.config.Service
import to.pubky.maps.config.SettingsRepository
import to.pubky.maps.links.AppLinks
import to.pubky.maps.links.LinkTarget
import to.pubky.maps.links.LinkedPlaceState
import to.pubky.maps.links.OpenIn
import to.pubky.maps.map.LastMapViewStore
import to.pubky.maps.map.MapRoute
import to.pubky.maps.map.StartView
import to.pubky.maps.offline.OfflineMaps
import to.pubky.maps.offline.OfflineMapsScreen
import to.pubky.maps.offline.offlineMapsSummary
import to.pubky.maps.place.OpenInApps
import to.pubky.maps.place.PlaceServices
import to.pubky.maps.search.SearchServices
import to.pubky.maps.search.searchHttpClient
import to.pubky.maps.settings.AddServerScreen
import to.pubky.maps.settings.LicencesRoute
import to.pubky.maps.settings.OpenInAppsScreen
import to.pubky.maps.settings.ServerListScreen
import to.pubky.maps.settings.ServersScreen
import to.pubky.maps.settings.SettingsScreen
import to.pubky.maps.settings.StoredOnlineScreen
import to.pubky.maps.ui.PubkyMapsTheme
import to.pubky.maps.ui.SystemBack
import to.pubky.maps.update.UpdateController
import to.pubky.maps.update.UpdateDialogHost
import to.pubky.maps.update.UpdatesUiState
import to.pubky.maps.write.ListRoute
import to.pubky.maps.write.PhotoRoute
import to.pubky.maps.write.PostRoute
import to.pubky.maps.write.SavedRoute
import to.pubky.maps.write.TagRoute
import to.pubky.maps.write.WriteIntent
import to.pubky.maps.write.WriteTarget
import to.pubky.maps.write.WriteUi
import to.pubky.maps.write.signInReason

@Serializable
private data object MapDestination

@Serializable
private data object SettingsDestination

@Serializable
private data object ServersDestination

@Serializable
private data class ServerListDestination(
    val serviceKey: String,
)

@Serializable
private data class AddServerDestination(
    val serviceKey: String,
)

@Serializable
private data object StoredOnlineDestination

@Serializable
private data object OpenInAppsDestination

@Serializable
private data object LicencesDestination

@Serializable
private data object OfflineMapsDestination

@Serializable
private data object AccountDestination

@Serializable
private data object SavedDestination

/**
 * A write page about the place [uri] (named [name]): which page is [intent]'s name. [pointPage]: the
 * place is a point of its own ([WriteTarget.pointPage]).
 */
@Serializable
private data class WriteDestination(
    val intent: String,
    val uri: String,
    val name: String? = null,
    val pointPage: Boolean = false,
)

private fun WriteDestination(
    intent: WriteIntent,
    target: WriteTarget,
) = WriteDestination(intent.name, target.uri, target.name, target.pointPage)

/**
 * The whole app: the map, and the settings pages on top of it. [loadLicences] returns the
 * AboutLibraries JSON of the bundled libraries. [updates] is the in-app updater, null in builds that
 * an app store updates. [account] is the Pubky account (sign-in with Pubky Ring), null where there is
 * none. [links] opens map links other apps send and shares places; null where the core is not
 * available. [search] is the place search; null where it is not available. [places] opens place
 * pages and shows the places people referenced on Pubky on the map; null where it is not available.
 * [offline] downloads areas for use without a connection; null where it is not available.
 * [lastMapView] keeps where the user left the map, so the next start opens there; null opens the
 * whole world.
 */
@Composable
fun App(
    appInfo: AppInfo,
    settingsRepository: SettingsRepository,
    loadLicences: suspend () -> String,
    updates: UpdateController? = null,
    account: AccountUi? = null,
    links: AppLinks? = null,
    search: SearchServices? = null,
    places: PlaceServices? = null,
    writes: WriteUi? = null,
    offline: OfflineMaps? = null,
    /** Called once the map has drawn its first whole view; Android reports it as the start's fully drawn time. */
    onMapDrawn: () -> Unit = {},
    lastMapView: LastMapViewStore? = null,
) {
    val loaded by settingsRepository.settings.collectAsStateWithLifecycle()
    // Place photos and profile pictures, with the app's User-Agent like every other request. Coil
    // keeps the first loader for the process, so the client is made once, inside it.
    setSingletonImageLoaderFactory { context ->
        val imageClient = searchHttpClient(appInfo.versionName)
        ImageLoader
            .Builder(context)
            .components { add(KtorNetworkFetcherFactory(httpClient = { imageClient })) }
            .build()
    }
    val updatesState = updates?.state?.collectAsStateWithLifecycle()?.value
    PubkyMapsTheme {
        // The settings file is read in a few milliseconds at start-up; until then the window stays
        // blank instead of loading the default map style and switching to the user's own.
        loaded?.let { settings ->
            AppNavigation(
                appInfo,
                settings,
                settingsRepository,
                loadLicences,
                updates,
                updatesState,
                account,
                links,
                search,
                places,
                writes,
                offline,
                onMapDrawn,
                lastMapView,
            )
        }
        val dialog = updatesState?.dialog
        if (updates != null && dialog != null) {
            UpdateDialogHost(
                dialog = dialog,
                onInstall = updates::install,
                onOpenSettings = updates::openInstallPermissionSettings,
                onCancelDownload = updates::cancelDownload,
                onDismiss = updates::dismissDialog,
            )
        }
    }
}

private fun NavController.open(destination: Any) = navigate(destination) { launchSingleTop = true }

@Composable
private fun AppNavigation(
    appInfo: AppInfo,
    settings: AppSettings,
    settingsRepository: SettingsRepository,
    loadLicences: suspend () -> String,
    updates: UpdateController?,
    updatesState: UpdatesUiState?,
    account: AccountUi?,
    links: AppLinks?,
    search: SearchServices?,
    places: PlaceServices?,
    writes: WriteUi?,
    offline: OfflineMaps?,
    onMapDrawn: () -> Unit,
    lastMapView: LastMapViewStore?,
) {
    val update: ((AppSettings) -> AppSettings) -> Unit = remember(settingsRepository) { { settingsRepository.update(it) } }
    val navController = rememberNavController()
    val back: () -> Unit = {
        // Only from a page that is fully shown: a second quick tap would also leave the page below.
        if (navController.currentBackStackEntry?.lifecycle?.currentState == Lifecycle.State.RESUMED) navController.navigateUp()
    }
    val accountState: AccountState? =
        account
            ?.controller
            ?.state
            ?.collectAsStateWithLifecycle()
            ?.value
    val missing =
        account
            ?.health
            ?.missing
            ?.collectAsStateWithLifecycle()
            ?.value ?: emptySet()
    if (account != null) {
        // Pubky Ring sent the user back: show the sign-in, also when the app was started by that.
        LaunchedEffect(account) {
            account.showAccount.filter { it }.collect {
                navController.open(AccountDestination)
                account.accountShown()
            }
        }
    }

    if (writes != null) {
        val controller = writes.controller
        val signInFirst by controller.signInFirst.collectAsStateWithLifecycle()
        // A write that needs the user's homeserver asks them to sign in first...
        LaunchedEffect(signInFirst) { if (signInFirst != null) navController.open(AccountDestination) }
        // ...and continues once they are signed in: back on the place's page, then its write page.
        val signedIn = (accountState as? AccountState.SignedIn)?.takeUnless { it.signingOut } != null
        LaunchedEffect(signedIn) {
            if (!signedIn) return@LaunchedEffect
            val waiting = controller.takeSignInFirst() ?: return@LaunchedEffect
            navController.popBackStack(MapDestination, inclusive = false)
            if (waiting.intent == WriteIntent.Save) {
                controller.save(waiting.target)
            } else {
                navController.open(WriteDestination(waiting.intent, waiting.target))
            }
        }
    }

    // A link from another app opens on the map, whichever page is showing.
    if (links != null) {
        val pinakes by rememberUpdatedState(settings.servers[Service.Pinakes].map { it.url })
        LaunchedEffect(links) {
            links.incoming.collect { text ->
                navController.popBackStack(MapDestination, inclusive = false)
                search?.controller?.close()
                links.linkedPlaces.open(text, pinakes)
            }
        }
    }

    NavHost(navController = navController, startDestination = MapDestination) {
        composable<MapDestination> {
            // Read with the settings at start-up (a few milliseconds): the map opens at its view
            // instead of drawing the world first and jumping. Taken once per map, so the saves of
            // the map's own moves do not recompose it.
            val startView by produceState(lastMapView?.startView?.value ?: StartView.Read(null), lastMapView) {
                if (lastMapView != null) value = lastMapView.startView.first { it is StartView.Read }
            }
            val opensAt = startView as? StartView.Read ?: return@composable
            val linkedPlace =
                links
                    ?.linkedPlaces
                    ?.state
                    ?.collectAsStateWithLifecycle()
                    ?.value ?: LinkedPlaceState()
            MapRoute(
                servers = settings.servers,
                onOpenSettings = { navController.open(SettingsDestination) },
                linkedPlace = linkedPlace,
                onSharePlace = links?.let { l -> { place -> l.share(OpenIn.shareText(place, l.mapLinks)) } },
                onClosePlace = { links?.linkedPlaces?.close() },
                linkMessages = links?.linkedPlaces?.messages ?: emptyFlow(),
                missing = missing,
                search = search,
                places = places,
                openIn =
                    links?.let { l ->
                        OpenInApps(
                            actions = { place ->
                                place.point
                                    ?.let {
                                        OpenIn.actions(
                                            LinkTarget(it, place.name, place.osm, place.zoom),
                                            settings,
                                            l.installedApps,
                                            l.mapLinks,
                                        )
                                    }.orEmpty()
                            },
                            open = l.openApp,
                        )
                    },
                writes = writes?.controller,
                onWrite = { target, intent ->
                    navController.open(WriteDestination(intent, target))
                },
                onOpenSaved = writes?.let { { navController.open(SavedDestination) } },
                offline = offline,
                onDownloadStarted = { navController.open(OfflineMapsDestination) },
                onMapDrawn = onMapDrawn,
                startView = opensAt.view,
                onViewRested = { lastMapView?.save(it) },
            )
        }
        composable<SettingsDestination> {
            val recentSearches =
                search
                    ?.recents
                    ?.entries
                    ?.collectAsStateWithLifecycle()
                    ?.value
            SettingsScreen(
                appInfo = appInfo,
                settings = settings,
                onOpenServers = { navController.open(ServersDestination) },
                onOpenStoredOnline = { navController.open(StoredOnlineDestination) },
                onOpenOpenIn = { navController.open(OpenInAppsDestination) },
                onFollowLocalRulesChange = { on -> update { it.copy(followLocalRules = on) } },
                onOpenLicences = { navController.open(LicencesDestination) },
                onBack = back,
                updates = updatesState,
                onCheckForUpdates = { updates?.checkNow() },
                onAutoCheckUpdatesChange = { updates?.setAutoCheck(it) },
                account = accountState,
                onOpenAccount = { navController.open(AccountDestination) },
                recentSearchCount = recentSearches?.size,
                onClearRecentSearches = { search?.recents?.clear() },
                offlineMaps =
                    offline
                        ?.state
                        ?.collectAsStateWithLifecycle()
                        ?.value
                        ?.let(::offlineMapsSummary),
                onOpenOfflineMaps = { navController.open(OfflineMapsDestination) },
            )
        }
        if (offline != null) {
            composable<OfflineMapsDestination> {
                val state by offline.state.collectAsStateWithLifecycle()
                val problem by offline.problem.collectAsStateWithLifecycle()
                OfflineMapsScreen(
                    state = state,
                    onDownloadArea = {
                        offline.startPicking()
                        navController.popBackStack(MapDestination, inclusive = false)
                    },
                    onResume = offline::resume,
                    onPause = offline::pause,
                    onDelete = offline::delete,
                    onBack = back,
                    problem = problem,
                )
            }
        }
        if (account != null) {
            composable<AccountDestination> {
                val waiting =
                    writes
                        ?.controller
                        ?.signInFirst
                        ?.collectAsStateWithLifecycle()
                        ?.value
                // Going back from the sign-in without signing in drops the write that waited for it; the
                // page leaving the screen for other reasons (the activity recreated while the user is in
                // Pubky Ring) keeps it.
                val leave = {
                    writes?.controller?.leftSignIn()
                    back()
                }
                SystemBack(enabled = waiting != null) { leave() }
                val controller = writes?.controller
                val names =
                    controller
                        ?.own
                        ?.collectAsStateWithLifecycle()
                        ?.value
                        ?.names
                        .orEmpty()
                val placeLabel: (String) -> String =
                    remember(controller, names) {
                        { place -> accountPlaceLabel(place, names, normalize = { controller?.normalize(it) ?: it }) }
                    }
                AccountRoute(account = account, onBack = leave, reason = waiting?.intent?.signInReason, placeLabel = placeLabel)
            }
        }
        if (writes != null) {
            composable<WriteDestination> { entry ->
                val route = entry.toRoute<WriteDestination>()
                val target = WriteTarget(route.uri, route.name, pointPage = route.pointPage)
                val page =
                    places
                        ?.pages
                        ?.state
                        ?.collectAsStateWithLifecycle()
                        ?.value
                val signedIn = (accountState as? AccountState.SignedIn)?.takeUnless { it.signingOut } != null
                when (WriteIntent.entries.firstOrNull { it.name == route.intent }) {
                    WriteIntent.Tag -> {
                        TagRoute(writes.controller, target, page, onClose = back)
                    }

                    WriteIntent.Post -> {
                        PostRoute(writes.controller, target, onClose = back)
                    }

                    WriteIntent.Photo -> {
                        writes.photos?.let { PhotoRoute(writes.controller, it, settings, target, onClose = back) }
                    }

                    WriteIntent.AddToList -> {
                        ListRoute(writes.controller, settings, target, page, signedIn, onClose = back)
                    }

                    WriteIntent.Save, null -> {}
                }
            }
            composable<SavedDestination> {
                val signedIn = accountState as? AccountState.SignedIn
                val pinakes = settings.servers[Service.Pinakes].map { it.url }
                SavedRoute(
                    writes = writes.controller,
                    signedIn = signedIn != null && !signedIn.signingOut,
                    refreshing = signedIn?.refreshing == true,
                    onRefresh = { account?.controller?.refresh() },
                    onOpen = { place, point ->
                        navController.popBackStack(MapDestination, inclusive = false)
                        search?.controller?.close()
                        // A point saved from its own page stays on the phone; any other place is read as its link is.
                        if (point != null) links?.linkedPlaces?.show(point) else links?.linkedPlaces?.open(place, pinakes)
                    },
                    onBack = back,
                )
            }
        }
        composable<ServersDestination> {
            ServersScreen(
                settings = settings,
                onOpenService = { navController.open(ServerListDestination(it.key)) },
                onBack = back,
            )
        }
        composable<ServerListDestination> { entry ->
            val service = Service.fromKey(entry.toRoute<ServerListDestination>().serviceKey)
            if (service != null) {
                ServerListScreen(
                    service = service,
                    settings = settings,
                    onUpdate = update,
                    onAddServer = { navController.open(AddServerDestination(service.key)) },
                    onBack = back,
                )
            }
        }
        composable<AddServerDestination> { entry ->
            val service = Service.fromKey(entry.toRoute<AddServerDestination>().serviceKey)
            if (service != null) {
                AddServerScreen(service = service, settings = settings, onUpdate = update, onDone = back)
            }
        }
        composable<StoredOnlineDestination> {
            StoredOnlineScreen(settings = settings, onUpdate = update, onBack = back)
        }
        composable<OpenInAppsDestination> {
            OpenInAppsScreen(settings = settings, onUpdate = update, onBack = back)
        }
        composable<LicencesDestination> {
            LicencesRoute(loadLicences = loadLicences, onBack = back)
        }
    }
}
