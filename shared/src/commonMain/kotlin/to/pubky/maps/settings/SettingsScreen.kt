package to.pubky.maps.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import to.pubky.maps.AppInfo
import to.pubky.maps.config.ServerConfig
import to.pubky.maps.ui.AppIcons

private const val SOURCE_URL = "https://github.com/gillohner/pubky-maps"
private const val LICENCE_URL = "$SOURCE_URL/blob/main/LICENSE"

/** Settings. For now only "About": version, licence and the credits for the map data. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    appInfo: AppInfo,
    serverConfig: ServerConfig,
    onBack: () -> Unit,
) {
    val uriHandler = LocalUriHandler.current
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(AppIcons.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(contentPadding = padding) {
            item { SectionHeader("About") }
            item {
                ListItem(
                    headlineContent = { Text("Pubky Maps") },
                    supportingContent = { Text("Version ${appInfo.versionName} (${appInfo.versionCode})") },
                )
            }
            item {
                ListItem(
                    headlineContent = { Text("Licence") },
                    supportingContent = { Text("MIT. The source code is open.") },
                    modifier = Modifier.clickable { uriHandler.openUri(LICENCE_URL) },
                )
            }
            item {
                ListItem(
                    headlineContent = { Text("Source code") },
                    supportingContent = { Text(SOURCE_URL.removePrefix("https://")) },
                    modifier = Modifier.clickable { uriHandler.openUri(SOURCE_URL) },
                )
            }
            item { SectionHeader("Map data") }
            items(serverConfig.mapStyles.allAttribution()) { link ->
                ListItem(
                    headlineContent = { Text(link.label) },
                    supportingContent = { Text(link.url.removePrefix("https://").removeSuffix("/")) },
                    modifier = Modifier.clickable { uriHandler.openUri(link.url) },
                )
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 8.dp),
    )
}
