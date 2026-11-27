package to.pubky.maps.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import to.pubky.maps.config.AppSettings
import to.pubky.maps.config.Service

/**
 * Adds a server at the end of [service]'s list: its address and an optional name. An address that
 * is refused says why under the field; an accepted one is stored and [onDone] goes back to the list.
 * A page of its own rather than a dialog, so the keyboard and large fonts never squeeze the fields.
 */
@Composable
fun AddServerScreen(
    service: Service,
    settings: AppSettings,
    onUpdate: ((AppSettings) -> AppSettings) -> Unit,
    onDone: () -> Unit,
) {
    var url by rememberSaveable { mutableStateOf("https://") }
    var name by rememberSaveable { mutableStateOf("") }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    val submit: () -> Unit = {
        when (val result = settings.withServerAdded(service, url, name)) {
            is AppSettings.AddServerResult.Refused -> {
                error = result.reason
            }

            is AppSettings.AddServerResult.Added -> {
                val (address, label) = url to name
                onUpdate { current ->
                    (current.withServerAdded(service, address, label) as? AppSettings.AddServerResult.Added)?.settings ?: current
                }
                onDone()
            }
        }
    }
    SettingsPage(title = "Add server", onBack = onDone) {
        item { PageIntro("Added at the end of ${service.title}. Move it to the top to use it first.") }
        item {
            OutlinedTextField(
                value = url,
                onValueChange = {
                    url = it
                    error = null
                },
                label = { Text("Address") },
                isError = error != null,
                supportingText = { Text(error ?: "https:// only. http:// works for localhost and 127.0.0.1.") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        item {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Name (optional)") },
                supportingText = { Text("Shown in the list. Without one, the server's host is shown.") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { submit() }),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        item {
            Button(onClick = submit, modifier = Modifier.padding(16.dp)) { Text("Add") }
        }
    }
}
