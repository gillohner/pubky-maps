package to.pubky.maps.android

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RoborazziComposeOptions
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.fontScale
import com.github.takahirom.roborazzi.size
import com.github.takahirom.roborazzi.uiMode
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import to.pubky.maps.AppInfo
import to.pubky.maps.account.AccountScreen
import to.pubky.maps.account.AccountState
import to.pubky.maps.account.AccountUser
import to.pubky.maps.account.Connection
import to.pubky.maps.cache.CachedAccount
import to.pubky.maps.cache.CachedBookmark
import to.pubky.maps.cache.CachedTag
import to.pubky.maps.config.AppSettings
import to.pubky.maps.config.DataKind
import to.pubky.maps.config.OpenInApp
import to.pubky.maps.config.ServerDefaults
import to.pubky.maps.config.Service
import to.pubky.maps.config.StorageChoice
import to.pubky.maps.map.MapScreen
import to.pubky.maps.map.MapSearch
import to.pubky.maps.search.SearchActions
import to.pubky.maps.search.SearchState
import to.pubky.maps.settings.LicencesScreen
import to.pubky.maps.settings.OpenInAppsScreen
import to.pubky.maps.settings.ServerListScreen
import to.pubky.maps.settings.ServersScreen
import to.pubky.maps.settings.SettingsScreen
import to.pubky.maps.settings.StoredOnlineScreen
import to.pubky.maps.settings.parseLicences
import to.pubky.maps.status.ServicePart
import to.pubky.maps.ui.PubkyMapsTheme
import to.pubky.maps.update.AvailableUpdate
import to.pubky.maps.update.UpdateChannel
import to.pubky.maps.update.UpdateDialog
import to.pubky.maps.update.UpdateDialogHost
import to.pubky.maps.update.UpdateStatus
import to.pubky.maps.update.UpdatesUiState

/**
 * Screenshots of every screen on a phone (412x915 dp) and a tablet (1280x800 dp), light and dark.
 * MapLibre cannot draw on the JVM, so the map screen shows a flat placeholder under its controls.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalRoborazziApi::class)
class ScreenshotTest {
    private enum class Device(
        val widthDp: Int,
        val heightDp: Int,
    ) {
        Phone(412, 915),
        Tablet(1280, 800),
    }

    /** [largeFont] captures at Android's largest font size (200 %) to check that nothing is cut off. */
    private fun capture(
        name: String,
        device: Device,
        dark: Boolean,
        largeFont: Boolean = false,
        content: @Composable () -> Unit,
    ) {
        val theme = if (dark) "dark" else "light"
        val variant = if (largeFont) "-large-font" else ""
        captureRoboImage(
            filePath = "screenshots/$name$variant-${device.name.lowercase()}-$theme.png",
            roborazziComposeOptions =
                RoborazziComposeOptions {
                    size(widthDp = device.widthDp, heightDp = device.heightDp)
                    uiMode(if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO)
                    if (largeFont) fontScale(2f)
                },
        ) {
            PubkyMapsTheme { content() }
        }
    }

    @Composable
    private fun MapPlaceholder() {
        val dark = isSystemInDarkTheme()
        Box(
            modifier = Modifier.fillMaxSize().background(if (dark) Color(0xFF2B2D31) else Color(0xFFF2EFE9)),
            contentAlignment = Alignment.Center,
        ) {
            Text("Map", color = if (dark) Color(0xFF8A8D93) else Color(0xFF9A968E))
        }
    }

    @Composable
    private fun Map(
        mapUnavailable: Boolean = false,
        missing: Set<ServicePart> = emptySet(),
    ) {
        MapScreen(
            attribution =
                ServerDefaults.lists
                    .mapStyles(darkTheme = false)
                    .first()
                    .attribution,
            mapUnavailable = mapUnavailable,
            onRetryMap = {},
            onMyLocation = {},
            onOpenSettings = {},
            missing = missing,
            search = MapSearch(SearchState(), recents = emptyList(), userLocation = null, actions = SearchActions()),
        ) { MapPlaceholder() }
    }

    /** Settings as a user might have them: an own light map style first, Waze off, lists public. */
    private val edited =
        (
            AppSettings().withServerAdded(Service.MapStyleLight, "https://tiles.example.org/styles/bright.json", "My tiles") as
                AppSettings.AddServerResult.Added
        ).settings
            .withServerMoved(Service.MapStyleLight, "https://tiles.example.org/styles/bright.json", -2)
            .withOpenIn(OpenInApp.Waze, false)
            .withStorage(DataKind.Lists, StorageChoice.Public)

    @Composable
    private fun Settings(account: AccountState = AccountState.SignedOut()) {
        SettingsScreen(
            appInfo = AppInfo(versionName = "0.1.0-edge.11", versionCode = 1_000_011),
            settings = AppSettings(),
            onOpenServers = {},
            onOpenStoredOnline = {},
            onOpenOpenIn = {},
            onFollowLocalRulesChange = {},
            onOpenLicences = {},
            onBack = {},
            updates =
                UpdatesUiState(
                    channel = UpdateChannel.Edge,
                    autoCheck = true,
                    status = UpdateStatus.UpToDate("0.1.0-edge.11"),
                    dialog = null,
                ),
            account = account,
            recentSearchCount = 12,
        )
    }

    private val user = AccountUser("8pinxxgqs41n4aididenw5apqp1urfmzdztr8jt4abrkdn435ewo", "Ada Lovelace")
    private val cached =
        CachedAccount(
            publicKey = user.publicKey,
            name = user.name,
            refreshedAt = 1,
            tags =
                listOf(
                    CachedTag("/pub/social/v1/tags/1.json", "https://www.openstreetmap.org/node/2405123", "great coffee", 3),
                    CachedTag("/pub/social/v1/tags/2.json", "https://www.openstreetmap.org/way/48210334", "quiet", 2),
                    CachedTag("/pub/social/v1/tags/3.json", "geo:47.37689,8.54169", "bench with a view", 1),
                ),
            bookmarks =
                listOf(
                    CachedBookmark("/priv/social/v1/bookmarks/a.json", "https://www.openstreetmap.org/node/2405123"),
                    CachedBookmark("/priv/social/v1/bookmarks/b.json", "https://www.openstreetmap.org/relation/1682248"),
                ),
        )

    @Composable
    private fun Account(state: AccountState) {
        AccountScreen(
            state = state,
            ringOnThisPhone = false,
            onSignIn = {},
            onOpenRing = {},
            onCancelSignIn = {},
            onRefresh = {},
            onSignOut = {},
            onBack = {},
            placeName = placeNames::get,
        )
    }

    /** Names the app stored for two of the places; the relation and the geo point have none. */
    private val placeNames =
        mapOf(
            "https://www.openstreetmap.org/node/2405123" to "Café Henrici",
            "https://www.openstreetmap.org/way/48210334" to "Lindenhof",
        )

    private val signedOut = AccountState.SignedOut()
    private val signedIn = AccountState.SignedIn(user, cached, Connection.Online)
    private val offline = AccountState.SignedIn(user, cached, Connection.Offline)
    private val homeserverMissing = setOf(ServicePart.Homeserver)

    @Test
    fun accountPhoneLight() = capture("account", Device.Phone, dark = false) { Account(signedOut) }

    @Test
    fun accountPhoneDark() = capture("account", Device.Phone, dark = true) { Account(signedOut) }

    @Test
    fun accountTabletLight() = capture("account", Device.Tablet, dark = false) { Account(signedOut) }

    @Test
    fun accountTabletDark() = capture("account", Device.Tablet, dark = true) { Account(signedOut) }

    @Test
    fun accountNoticePhoneLight() =
        capture("account-notice", Device.Phone, dark = false) {
            Account(AccountState.SignedOut("The sign-in was cancelled in Pubky Ring."))
        }

    @Test
    fun signedInPhoneLight() = capture("signed-in", Device.Phone, dark = false) { Account(signedIn) }

    @Test
    fun signedInPhoneDark() = capture("signed-in", Device.Phone, dark = true) { Account(signedIn) }

    @Test
    fun signedInTabletLight() = capture("signed-in", Device.Tablet, dark = false) { Account(signedIn) }

    @Test
    fun signedInTabletDark() = capture("signed-in", Device.Tablet, dark = true) { Account(signedIn) }

    @Test
    fun signedInOfflinePhoneLight() = capture("signed-in-offline", Device.Phone, dark = false) { Account(offline) }

    @Test
    fun signedInOfflinePhoneDark() = capture("signed-in-offline", Device.Phone, dark = true) { Account(offline) }

    @Test
    fun mapDegradedPhoneLight() = capture("map-degraded", Device.Phone, dark = false) { Map(missing = homeserverMissing) }

    @Test
    fun mapDegradedPhoneDark() = capture("map-degraded", Device.Phone, dark = true) { Map(missing = homeserverMissing) }

    @Test
    fun mapDegradedTabletLight() = capture("map-degraded", Device.Tablet, dark = false) { Map(missing = homeserverMissing) }

    @Test
    fun mapDegradedTabletDark() = capture("map-degraded", Device.Tablet, dark = true) { Map(missing = homeserverMissing) }

    @Test
    fun settingsSignedInPhoneLight() = capture("settings-signed-in", Device.Phone, dark = false) { Settings(offline) }

    @Composable
    private fun Licences() {
        LicencesScreen(libraries = parseLicences(SAMPLE_LICENCES_JSON), onBack = {})
    }

    @Composable
    private fun Servers() = ServersScreen(settings = edited, onOpenService = {}, onBack = {})

    @Composable
    private fun ServerList(service: Service = Service.MapStyleLight) =
        ServerListScreen(service = service, settings = edited, onUpdate = {}, onAddServer = {}, onBack = {})

    @Composable
    private fun StoredOnline() = StoredOnlineScreen(settings = edited, onUpdate = {}, onBack = {})

    @Composable
    private fun OpenInApps() = OpenInAppsScreen(settings = edited, onUpdate = {}, onBack = {})

    @Test
    fun mapPhoneLight() = capture("map", Device.Phone, dark = false) { Map() }

    @Test
    fun mapPhoneDark() = capture("map", Device.Phone, dark = true) { Map() }

    @Test
    fun mapTabletLight() = capture("map", Device.Tablet, dark = false) { Map() }

    @Test
    fun mapTabletDark() = capture("map", Device.Tablet, dark = true) { Map() }

    @Test
    fun mapUnavailablePhoneLight() = capture("map-unavailable", Device.Phone, dark = false) { Map(mapUnavailable = true) }

    @Test
    fun settingsPhoneLight() = capture("settings", Device.Phone, dark = false) { Settings() }

    @Test
    fun settingsPhoneDark() = capture("settings", Device.Phone, dark = true) { Settings() }

    @Test
    fun settingsTabletLight() = capture("settings", Device.Tablet, dark = false) { Settings() }

    @Test
    fun settingsTabletDark() = capture("settings", Device.Tablet, dark = true) { Settings() }

    @Test
    fun licencesPhoneLight() = capture("licences", Device.Phone, dark = false) { Licences() }

    @Test
    fun licencesPhoneDark() = capture("licences", Device.Phone, dark = true) { Licences() }

    @Test
    fun licencesTabletLight() = capture("licences", Device.Tablet, dark = false) { Licences() }

    @Test
    fun licencesTabletDark() = capture("licences", Device.Tablet, dark = true) { Licences() }

    @Test
    fun settingsLargeFontPhoneLight() = capture("settings", Device.Phone, dark = false, largeFont = true) { Settings() }

    @Test
    fun serversPhoneLight() = capture("servers", Device.Phone, dark = false) { Servers() }

    @Test
    fun serversPhoneDark() = capture("servers", Device.Phone, dark = true) { Servers() }

    @Test
    fun serversTabletLight() = capture("servers", Device.Tablet, dark = false) { Servers() }

    @Test
    fun serversTabletDark() = capture("servers", Device.Tablet, dark = true) { Servers() }

    @Test
    fun serverListPhoneLight() = capture("server-list", Device.Phone, dark = false) { ServerList() }

    @Test
    fun serverListPhoneDark() = capture("server-list", Device.Phone, dark = true) { ServerList() }

    @Test
    fun serverListTabletLight() = capture("server-list", Device.Tablet, dark = false) { ServerList() }

    @Test
    fun serverListTabletDark() = capture("server-list", Device.Tablet, dark = true) { ServerList() }

    @Test
    fun serverListLargeFontPhoneLight() = capture("server-list", Device.Phone, dark = false, largeFont = true) { ServerList() }

    @Test
    fun serverListSynonymPhoneLight() = capture("server-list-synonym", Device.Phone, dark = false) { ServerList(Service.PkarrRelay) }

    @Test
    fun storedOnlinePhoneLight() = capture("stored-online", Device.Phone, dark = false) { StoredOnline() }

    @Test
    fun storedOnlinePhoneDark() = capture("stored-online", Device.Phone, dark = true) { StoredOnline() }

    @Test
    fun storedOnlineTabletLight() = capture("stored-online", Device.Tablet, dark = false) { StoredOnline() }

    @Test
    fun storedOnlineTabletDark() = capture("stored-online", Device.Tablet, dark = true) { StoredOnline() }

    @Test
    fun storedOnlineLargeFontPhoneLight() = capture("stored-online", Device.Phone, dark = false, largeFont = true) { StoredOnline() }

    @Test
    fun openInPhoneLight() = capture("open-in", Device.Phone, dark = false) { OpenInApps() }

    @Test
    fun openInPhoneDark() = capture("open-in", Device.Phone, dark = true) { OpenInApps() }

    @Test
    fun openInTabletLight() = capture("open-in", Device.Tablet, dark = false) { OpenInApps() }

    @Test
    fun openInTabletDark() = capture("open-in", Device.Tablet, dark = true) { OpenInApps() }

    private val update = AvailableUpdate(versionCode = 1_000_012, versionName = "0.1.0-edge.12", sizeBytes = 14_236_512)

    /** The update dialog over the map, as it shows after the start-up check. */
    @Composable
    private fun Update(dialog: UpdateDialog = UpdateDialog.Offer(update, installedVersionName = "0.1.0-edge.11")) {
        Map()
        UpdateDialogHost(dialog = dialog, onInstall = {}, onOpenSettings = {}, onCancelDownload = {}, onDismiss = {})
    }

    @Test
    fun updatePhoneLight() = capture("update", Device.Phone, dark = false) { Update() }

    @Test
    fun updatePhoneDark() = capture("update", Device.Phone, dark = true) { Update() }

    @Test
    fun updateTabletLight() = capture("update", Device.Tablet, dark = false) { Update() }

    @Test
    fun updateTabletDark() = capture("update", Device.Tablet, dark = true) { Update() }

    private val downloading = UpdateDialog.Downloading(update, downloadedBytes = 6_100_000)
    private val permission = UpdateDialog.NeedsPermission(update)
    private val refused =
        UpdateDialog.NotInstalled(
            "The downloaded file is not the one the signed update information names (its SHA-256 differs). " +
                "Nothing was installed.",
        )

    @Test
    fun updateDownloadingPhoneLight() = capture("update-downloading", Device.Phone, dark = false) { Update(downloading) }

    @Test
    fun updateDownloadingPhoneDark() = capture("update-downloading", Device.Phone, dark = true) { Update(downloading) }

    @Test
    fun updateDownloadingTabletLight() = capture("update-downloading", Device.Tablet, dark = false) { Update(downloading) }

    @Test
    fun updateDownloadingTabletDark() = capture("update-downloading", Device.Tablet, dark = true) { Update(downloading) }

    @Test
    fun updatePermissionPhoneLight() = capture("update-permission", Device.Phone, dark = false) { Update(permission) }

    @Test
    fun updatePermissionPhoneDark() = capture("update-permission", Device.Phone, dark = true) { Update(permission) }

    @Test
    fun updatePermissionTabletLight() = capture("update-permission", Device.Tablet, dark = false) { Update(permission) }

    @Test
    fun updatePermissionTabletDark() = capture("update-permission", Device.Tablet, dark = true) { Update(permission) }

    @Test
    fun updateRefusedPhoneLight() = capture("update-refused", Device.Phone, dark = false) { Update(refused) }

    @Test
    fun updateRefusedPhoneDark() = capture("update-refused", Device.Phone, dark = true) { Update(refused) }

    @Test
    fun updateRefusedTabletLight() = capture("update-refused", Device.Tablet, dark = false) { Update(refused) }

    @Test
    fun updateRefusedTabletDark() = capture("update-refused", Device.Tablet, dark = true) { Update(refused) }
}
