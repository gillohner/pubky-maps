package to.pubky.maps.offline

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import to.pubky.maps.settings.PageIntro
import to.pubky.maps.settings.SectionHeader
import to.pubky.maps.settings.SettingsPage
import to.pubky.maps.ui.AppIcons
import to.pubky.maps.update.formatMegabytes

/**
 * Offline maps: the areas downloaded to the phone, each with its state and size, a way to download
 * another (it opens the area picker on the map), to pause and continue a download and to delete one.
 */
@Composable
fun OfflineMapsScreen(
    state: OfflineMapsState,
    onDownloadArea: () -> Unit,
    onResume: (String) -> Unit,
    onDelete: (String) -> Unit,
    onBack: () -> Unit,
    onPause: (String) -> Unit = {},
    problem: String? = null,
) {
    var deleting by rememberSaveable { mutableStateOf<String?>(null) }
    SettingsPage(title = "Offline maps", onBack = onBack) {
        item {
            PageIntro(
                "A downloaded area shows on the map without a connection, in light and dark. " +
                    "One download covers about a city; download a larger region in parts.",
            )
        }
        item {
            Button(
                onClick = onDownloadArea,
                contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Icon(AppIcons.Add, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                Text("Download an area")
            }
        }
        if (problem != null) {
            item {
                Text(
                    problem,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }
        when (state) {
            OfflineMapsState.Loading -> {
                item { PageIntro("Loading the offline maps…") }
            }

            is OfflineMapsState.Failed -> {
                item { PageIntro("The offline maps could not be read: ${state.message}") }
            }

            is OfflineMapsState.Ready -> {
                item { SectionHeader("On this phone") }
                if (state.maps.isEmpty()) {
                    item { PageIntro("No offline maps yet.") }
                }
                state.maps.forEach { map ->
                    item(key = map.id) {
                        OfflineMapRow(map, onPause = { onPause(map.id) }, onResume = { onResume(map.id) }, onDelete = { deleting = map.id })
                    }
                }
            }
        }
    }
    val confirming = (state as? OfflineMapsState.Ready)?.maps?.firstOrNull { it.id == deleting }
    if (confirming != null) {
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete this offline map?") },
            text = { Text("${confirming.name} is deleted from this phone and no longer shows without a connection.") },
            confirmButton = {
                TextButton(onClick = {
                    deleting = null
                    onDelete(confirming.id)
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun OfflineMapRow(
    map: OfflineMap,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onDelete: () -> Unit,
) {
    val status = map.status
    ListItem(
        headlineContent = { Text(map.name) },
        supportingContent = {
            Column {
                Text(statusText(map))
                if (status is OfflineMapStatus.Downloading) {
                    LinearProgressIndicator(
                        progress = { status.percent / 100f },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                }
                // In line with the text above it.
                if (status is OfflineMapStatus.Downloading) {
                    TextButton(onClick = onPause, contentPadding = PaddingValues(0.dp)) { Text("Pause") }
                }
                if (status is OfflineMapStatus.Paused) {
                    TextButton(onClick = onResume, contentPadding = PaddingValues(0.dp)) { Text("Resume") }
                }
            }
        },
        trailingContent = {
            IconButton(onClick = onDelete) {
                Icon(AppIcons.Delete, contentDescription = "Delete ${map.name}")
            }
        },
    )
}

internal fun statusText(map: OfflineMap): String {
    // A size never breaks between its number and its unit.
    val size = formatMegabytes(map.bytes).replace(' ', '\u00A0')
    return when (val status = map.status) {
        OfflineMapStatus.Ready -> {
            "Ready · $size"
        }

        is OfflineMapStatus.Downloading -> {
            "${if (status.update) "Updating" else "Downloading"} · ${status.percent}% · $size" +
                (status.retrying?.let { "\nTrying again: $it" } ?: "")
        }

        is OfflineMapStatus.Paused -> {
            "Paused at ${status.percent}%"
        }

        OfflineMapStatus.TooLarge -> {
            "Stopped: this area has more map data than one download may take. Delete it and download a smaller area."
        }
    }
}

/** The Settings row of offline maps: how many there are. */
internal fun offlineMapsSummary(state: OfflineMapsState): String =
    when (state) {
        is OfflineMapsState.Ready -> {
            when (val count = state.maps.size) {
                0 -> "Download areas to see the map without a connection."
                1 -> "1 area on this phone."
                else -> "$count areas on this phone."
            }
        }

        else -> {
            "Download areas to see the map without a connection."
        }
    }
