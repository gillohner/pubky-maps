package to.pubky.maps.update

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import to.pubky.maps.ui.AppIcons

/**
 * The card at the top of Settings while an update is ready: its version, size and the installed
 * version, and Install, which starts the same download and checks as the "Update available" dialog.
 * Install waits while that download runs ([downloading]); the dialog shows its progress.
 */
@Composable
fun UpdateReadyCard(
    update: AvailableUpdate,
    installedVersionName: String,
    downloading: Boolean,
    onInstall: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
    ) {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp)) {
            Row {
                Icon(AppIcons.SystemUpdate, contentDescription = null)
                Spacer(Modifier.width(16.dp))
                // One TalkBack stop for the words, then Install.
                Column(Modifier.semantics(mergeDescendants = true) {}) {
                    Text(
                        "Pubky Maps ${update.versionName} is ready",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        "${formatMegabytes(update.sizeBytes)}. You have $installedVersionName.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            Button(
                onClick = onInstall,
                enabled = !downloading,
                modifier = Modifier.align(Alignment.End).padding(top = 8.dp),
            ) { Text("Install") }
        }
    }
}

/** "Check for updates" and "Check automatically", the update rows in Settings > About. */
@Composable
fun UpdateSettingsRows(
    state: UpdatesUiState,
    onCheckNow: () -> Unit,
    onAutoCheckChange: (Boolean) -> Unit,
) {
    val checking = state.status == UpdateStatus.Checking
    val busy = checking || state.dialog is UpdateDialog.Downloading
    ListItem(
        headlineContent = { Text("Check for updates") },
        supportingContent = { Text(statusText(state)) },
        trailingContent =
            if (checking) {
                { CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp) }
            } else {
                null
            },
        modifier = Modifier.clickable(enabled = !busy, onClick = onCheckNow),
    )
    ListItem(
        headlineContent = { Text("Check automatically") },
        supportingContent = { Text("Once a day, when the app starts") },
        trailingContent = { Switch(checked = state.autoCheck, onCheckedChange = null) },
        modifier =
            Modifier.toggleable(
                value = state.autoCheck,
                role = Role.Switch,
                onValueChange = onAutoCheckChange,
            ),
    )
}

private fun statusText(state: UpdatesUiState): String =
    when (val status = state.status) {
        UpdateStatus.NotChecked -> "${state.channel.label} channel"
        UpdateStatus.Checking -> "Checking…"
        is UpdateStatus.UpToDate -> "Up to date (${state.channel.label.lowercase()} channel)"
        is UpdateStatus.Available -> "Version ${status.update.versionName} is available"
        is UpdateStatus.Failed -> status.message
    }

/** The update dialog for [dialog], over whatever screen is open. */
@Composable
fun UpdateDialogHost(
    dialog: UpdateDialog,
    onInstall: () -> Unit,
    onOpenSettings: () -> Unit,
    onCancelDownload: () -> Unit,
    onDismiss: () -> Unit,
) {
    when (dialog) {
        is UpdateDialog.Offer -> {
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text("Update available") },
                text = {
                    Text(
                        "Pubky Maps ${dialog.update.versionName} is ready to install " +
                            "(${formatMegabytes(dialog.update.sizeBytes)}). " +
                            "You have ${dialog.installedVersionName}.",
                    )
                },
                confirmButton = { TextButton(onClick = onInstall) { Text("Install") } },
                dismissButton = { TextButton(onClick = onDismiss) { Text("Later") } },
            )
        }

        is UpdateDialog.Downloading -> {
            AlertDialog(
                onDismissRequest = {},
                properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
                title = { Text("Downloading update") },
                text = { DownloadProgress(dialog) },
                confirmButton = {},
                dismissButton = { TextButton(onClick = onCancelDownload) { Text("Cancel") } },
            )
        }

        is UpdateDialog.NeedsPermission -> {
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text("Allow updates") },
                text = {
                    Text(
                        "Android asks once before Pubky Maps may install its updates. In the settings " +
                            "that open, turn on \"Allow from this source\" and come back.",
                    )
                },
                confirmButton = { TextButton(onClick = onOpenSettings) { Text("Open settings") } },
                dismissButton = { TextButton(onClick = onDismiss) { Text("Not now") } },
            )
        }

        is UpdateDialog.NotInstalled -> {
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text("Update not installed") },
                text = { Text(dialog.message) },
                confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } },
            )
        }
    }
}

@Composable
private fun DownloadProgress(dialog: UpdateDialog.Downloading) {
    val total = dialog.update.sizeBytes
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Pubky Maps ${dialog.update.versionName}")
        LinearProgressIndicator(
            progress = { (dialog.downloadedBytes.toFloat() / total).coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            "${formatMegabytes(dialog.downloadedBytes)} of ${formatMegabytes(total)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
