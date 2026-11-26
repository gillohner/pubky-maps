package to.pubky.maps.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import to.pubky.maps.AppInfo
import to.pubky.maps.account.AccountSettingsRow
import to.pubky.maps.account.AccountState
import to.pubky.maps.config.AppSettings
import to.pubky.maps.config.OpenInApp
import to.pubky.maps.config.Service
import to.pubky.maps.config.SynonymHosts
import to.pubky.maps.ui.rememberSafeLinkOpener
import to.pubky.maps.update.UpdateSettingsRows
import to.pubky.maps.update.UpdatesUiState

private const val SOURCE_URL = "https://github.com/gillohner/pubky-maps"
private const val LICENCE_URL = "$SOURCE_URL/blob/main/LICENSE"

/**
 * Settings: a grouped list, one group per topic (the [account] where there is one, offline maps
 * where there are ([offlineMaps], their summary, not null), servers, privacy, other apps, local
 * rules, about, map data). Groups with more to set open their own page. About shows
 * the update rows in builds that update themselves ([updates] not null). Privacy has "Clear search
 * history" where search keeps one ([recentSearchCount] not null).
 */
@Composable
fun SettingsScreen(
    appInfo: AppInfo,
    settings: AppSettings,
    onOpenServers: () -> Unit,
    onOpenStoredOnline: () -> Unit,
    onOpenOpenIn: () -> Unit,
    onFollowLocalRulesChange: (Boolean) -> Unit,
    onOpenLicences: () -> Unit,
    onBack: () -> Unit,
    updates: UpdatesUiState? = null,
    onCheckForUpdates: () -> Unit = {},
    onAutoCheckUpdatesChange: (Boolean) -> Unit = {},
    account: AccountState? = null,
    onOpenAccount: () -> Unit = {},
    recentSearchCount: Int? = null,
    onClearRecentSearches: () -> Unit = {},
    offlineMaps: String? = null,
    onOpenOfflineMaps: () -> Unit = {},
) {
    val openLink = rememberSafeLinkOpener()
    var confirmClear by remember { mutableStateOf(false) }
    val withSynonym = Service.entries.count { service -> settings.servers[service].any { SynonymHosts.isSynonymServer(it.url) } }
    val appsOn = OpenInApp.entries.count { settings.isOpenInEnabled(it) }
    SettingsPage(title = "Settings", onBack = onBack) {
        if (account != null) {
            item { SectionHeader("Account") }
            item { AccountSettingsRow(state = account, onOpen = onOpenAccount) }
        }
        if (offlineMaps != null) {
            item { SectionHeader("Offline") }
            item { LinkRow(title = "Offline maps", summary = offlineMaps, onClick = onOpenOfflineMaps) }
        }
        item { SectionHeader("Servers") }
        item {
            LinkRow(
                title = "Server lists",
                summary =
                    "Map styles, search, places and Pubky services. " +
                        "$withSynonym of ${Service.entries.size} lists use Synonym servers.",
                onClick = onOpenServers,
            )
        }
        item { SectionHeader("Privacy") }
        item {
            LinkRow(
                title = "What is stored online",
                summary = "Phone only, on your homeserver or public, for each kind of data.",
                onClick = onOpenStoredOnline,
            )
        }
        if (recentSearchCount != null) {
            item {
                ListItem(
                    headlineContent = { Text("Clear search history") },
                    supportingContent = {
                        Text(
                            when (recentSearchCount) {
                                0 -> "No recent searches. They are kept on this phone only."
                                1 -> "1 recent search, kept on this phone only."
                                else -> "$recentSearchCount recent searches, kept on this phone only."
                            },
                        )
                    },
                    modifier = Modifier.clickable(enabled = recentSearchCount > 0) { confirmClear = true },
                )
            }
        }
        item { SectionHeader("Other apps") }
        item {
            LinkRow(
                title = "Open in other apps",
                summary = "$appsOn of ${OpenInApp.entries.size} apps are offered for a place.",
                onClick = onOpenOpenIn,
            )
        }
        item { SectionHeader("Local rules") }
        item {
            SwitchRow(
                title = "Follow local rules",
                summary = "Hides features the country you are in forbids, such as speed camera warnings.",
                checked = settings.followLocalRules,
                onCheckedChange = onFollowLocalRulesChange,
            )
        }
        item { SectionHeader("About") }
        item {
            ListItem(
                headlineContent = { Text("Pubky Maps") },
                supportingContent = { Text("Version ${appInfo.versionName} (${appInfo.versionCode})") },
            )
        }
        if (updates != null) {
            item { UpdateSettingsRows(updates, onCheckForUpdates, onAutoCheckUpdatesChange) }
        }
        item {
            ListItem(
                headlineContent = { Text("Licence") },
                supportingContent = { Text("MIT. The source code is open.") },
                modifier = Modifier.clickable { openLink(LICENCE_URL) },
            )
        }
        item {
            ListItem(
                headlineContent = { Text("Source code") },
                supportingContent = { Text(SOURCE_URL.removePrefix("https://")) },
                modifier = Modifier.clickable { openLink(SOURCE_URL) },
            )
        }
        item {
            ListItem(
                headlineContent = { Text("Open-source licences") },
                supportingContent = { Text("The libraries in this app and their licences") },
                modifier = Modifier.clickable(onClick = onOpenLicences),
            )
        }
        item { SectionHeader("Map data") }
        items(settings.servers.allAttribution()) { link ->
            ListItem(
                headlineContent = { Text(link.label) },
                supportingContent = { Text(link.url.removePrefix("https://").removeSuffix("/")) },
                modifier = Modifier.clickable { openLink(link.url) },
            )
        }
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear search history?") },
            text = { Text("Your recent searches are deleted from this phone.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    onClearRecentSearches()
                }) { Text("Clear") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
        )
    }
}
