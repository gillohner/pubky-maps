package to.pubky.maps.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import to.pubky.maps.config.AppSettings
import to.pubky.maps.config.ServerEntry
import to.pubky.maps.config.Service
import to.pubky.maps.config.SynonymHosts
import to.pubky.maps.ui.AppIcons

/**
 * One service's servers in the order the app tries them: move, remove, add, and reset to the
 * defaults. [onUpdate] applies a change to the stored settings.
 */
@Composable
fun ServerListScreen(
    service: Service,
    settings: AppSettings,
    onUpdate: ((AppSettings) -> AppSettings) -> Unit,
    onBack: () -> Unit,
) {
    val list = settings.servers[service]
    var adding by rememberSaveable { mutableStateOf(false) }
    var confirmingReset by rememberSaveable { mutableStateOf(false) }
    SettingsPage(title = service.title, onBack = onBack) {
        item { PageIntro("${service.description} The app tries them from the top.") }
        list.forEachIndexed { index, entry ->
            item(key = entry.url) {
                ServerRow(
                    position = index + 1,
                    entry = entry,
                    canMoveUp = index > 0,
                    canMoveDown = index < list.lastIndex,
                    canRemove = list.size > 1,
                    onMoveUp = { onUpdate { it.withServerMoved(service, entry.url, -1) } },
                    onMoveDown = { onUpdate { it.withServerMoved(service, entry.url, 1) } },
                    onRemove = { onUpdate { it.withServerRemoved(service, entry.url) } },
                )
            }
        }
        item {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(16.dp),
            ) {
                FilledTonalButton(onClick = { adding = true }) {
                    Icon(AppIcons.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(8.dp))
                    Text("Add server")
                }
                if (!settings.isDefaultServerList(service)) {
                    TextButton(onClick = { confirmingReset = true }) { Text("Reset to defaults") }
                }
            }
        }
        item { PageIntro(SYNONYM_EXPLANATION) }
    }
    if (adding) {
        AddServerDialog(
            service = service,
            check = { url, name -> settings.withServerAdded(service, url, name) },
            onAdd = { url, name ->
                adding = false
                onUpdate { current ->
                    (current.withServerAdded(service, url, name) as? AppSettings.AddServerResult.Added)?.settings ?: current
                }
            },
            onDismiss = { adding = false },
        )
    }
    if (confirmingReset) {
        AlertDialog(
            onDismissRequest = { confirmingReset = false },
            title = { Text("Reset to the default servers?") },
            text = { Text("Servers you added to this list are removed and the order goes back to the start.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmingReset = false
                    onUpdate { it.withServersReset(service) }
                }) { Text("Reset") }
            },
            dismissButton = { TextButton(onClick = { confirmingReset = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ServerRow(
    position: Int,
    entry: ServerEntry,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    canRemove: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onRemove: () -> Unit,
) {
    val isSynonym = SynonymHosts.isSynonymServer(entry.url)
    var menuOpen by remember { mutableStateOf(false) }
    // The same actions as the menu, offered to TalkBack directly on the row.
    val actions =
        buildList {
            if (canMoveUp) add(accessibilityAction("Move up", onMoveUp))
            if (canMoveDown) add(accessibilityAction("Move down", onMoveDown))
            if (canRemove) add(accessibilityAction("Remove", onRemove))
        }
    ListItem(
        leadingContent = {
            Text("$position", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        },
        headlineContent = { Text(entry.name) },
        supportingContent = {
            Column {
                Text(entry.url.removePrefix("https://"))
                if (isSynonym) Badge(SYNONYM_BADGE, Modifier.padding(top = 4.dp))
            }
        },
        trailingContent = {
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(AppIcons.MoreVert, contentDescription = "Options for ${entry.name}")
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("Move up") },
                        enabled = canMoveUp,
                        onClick = {
                            menuOpen = false
                            onMoveUp()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Move down") },
                        enabled = canMoveDown,
                        onClick = {
                            menuOpen = false
                            onMoveDown()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(if (canRemove) "Remove" else "Remove (a list needs one server)") },
                        enabled = canRemove,
                        onClick = {
                            menuOpen = false
                            onRemove()
                        },
                    )
                }
            }
        },
        // One TalkBack stop per server (position, name, address, badge), with the actions on it; the
        // options button stays a stop of its own.
        modifier = Modifier.semantics(mergeDescendants = true) { customActions = actions },
    )
}

private fun accessibilityAction(
    label: String,
    run: () -> Unit,
) = CustomAccessibilityAction(label) {
    run()
    true
}

/**
 * Asks for a new server's address and an optional name. [check] says whether the address would be
 * accepted; the dialog shows its reason when not, and calls [onAdd] when it is.
 */
@Composable
fun AddServerDialog(
    service: Service,
    check: (url: String, name: String) -> AppSettings.AddServerResult,
    onAdd: (url: String, name: String) -> Unit,
    onDismiss: () -> Unit,
    initialUrl: String = "https://",
    initialError: String? = null,
) {
    var url by rememberSaveable { mutableStateOf(initialUrl) }
    var name by rememberSaveable { mutableStateOf("") }
    var error by rememberSaveable { mutableStateOf(initialError) }
    val submit: () -> Unit = {
        when (val result = check(url, name)) {
            is AppSettings.AddServerResult.Added -> onAdd(url, name)
            is AppSettings.AddServerResult.Refused -> error = result.reason
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add a server") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("Added at the end of ${service.title}. Move it up to use it first.")
                OutlinedTextField(
                    value = url,
                    onValueChange = {
                        url = it
                        error = null
                    },
                    label = { Text("Address") },
                    isError = error != null,
                    supportingText = { Text(error ?: "https:// only. http:// works for localhost.") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name (optional)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = { TextButton(onClick = submit) { Text("Add") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
