package to.pubky.maps.settings

import androidx.compose.foundation.clickable
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import to.pubky.maps.config.AppSettings
import to.pubky.maps.config.Service
import to.pubky.maps.config.SynonymHosts

private val serviceGroups =
    listOf(
        "Map" to listOf(Service.MapStyleLight, Service.MapStyleDark),
        "Search and places" to listOf(Service.Photon, Service.Pinakes, Service.Overpass),
        "Directions" to listOf(Service.Valhalla),
        "Pubky" to listOf(Service.Nexus, Service.PkarrRelay, Service.AuthRelay),
    )

/** Every service with its server list in short; a tap opens the list. */
@Composable
fun ServersScreen(
    settings: AppSettings,
    onOpenService: (Service) -> Unit,
    onBack: () -> Unit,
) {
    SettingsPage(title = "Server lists", onBack = onBack) {
        item { PageIntro("Each list holds a service's servers in the order they are tried.") }
        if (Service.entries.any { service -> settings.servers[service].any { SynonymHosts.isSynonymServer(it.url) } }) {
            item { PageIntro(SYNONYM_EXPLANATION) }
        }
        for ((group, services) in serviceGroups) {
            item { SectionHeader(group) }
            for (service in services) {
                item { ServiceRow(service, settings, onClick = { onOpenService(service) }) }
            }
        }
    }
}

@Composable
private fun ServiceRow(
    service: Service,
    settings: AppSettings,
    onClick: () -> Unit,
) {
    val list = settings.servers[service]
    val usesSynonym = list.any { SynonymHosts.isSynonymServer(it.url) }
    val summary =
        buildString {
            append(list.first().name)
            if (list.size > 1) append(", then ${list.size - 1} more")
            if (!settings.isDefaultServerList(service)) append(". Changed by you")
        }
    ListItem(
        headlineContent = { Text(service.title) },
        supportingContent = { Text(summary) },
        trailingContent = if (usesSynonym) ({ Badge(SYNONYM_BADGE) }) else null,
        modifier = Modifier.clickable(onClickLabel = "Open", onClick = onClick),
    )
}
