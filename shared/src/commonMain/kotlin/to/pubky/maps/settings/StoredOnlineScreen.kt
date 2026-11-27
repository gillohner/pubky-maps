package to.pubky.maps.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import to.pubky.maps.config.AppSettings
import to.pubky.maps.config.DataKind
import to.pubky.maps.config.StorageChoice

/**
 * "What is stored online": for each kind of data, where it is kept. Kinds that are public by nature
 * say so instead of offering a choice.
 */
@Composable
fun StoredOnlineScreen(
    settings: AppSettings,
    onUpdate: ((AppSettings) -> AppSettings) -> Unit,
    onBack: () -> Unit,
) {
    var choosingKey by rememberSaveable { mutableStateOf<String?>(null) }
    SettingsPage(title = "What is stored online", onBack = onBack) {
        item {
            PageIntro(
                "Choose where each kind of data is kept. Bookmarks, collections, tags, posts and photos follow " +
                    "these choices; Pubky Maps does not store the other kinds yet.",
            )
        }
        item { SectionHeader("You choose") }
        for (kind in DataKind.entries.filter { it.hasChoice }) {
            item {
                val choice = settings.storage(kind)
                ListItem(
                    headlineContent = { Text(kind.title) },
                    supportingContent = { Text("${choice.title}. ${choice.explanation}") },
                    trailingContent = if (choice == StorageChoice.Public) ({ Badge("Public") }) else null,
                    modifier = Modifier.clickable(onClickLabel = "Change") { choosingKey = kind.key },
                )
            }
        }
        item { SectionHeader("Public by nature") }
        for (kind in DataKind.entries.filter { !it.hasChoice }) {
            item {
                ListItem(
                    headlineContent = { Text(kind.title) },
                    supportingContent = { Text(PUBLIC_BY_NATURE) },
                    trailingContent = { Badge("Public") },
                    modifier = Modifier.semantics(mergeDescendants = true) {},
                )
            }
        }
    }
    val choosing = choosingKey?.let { DataKind.fromKey(it) }
    if (choosing != null) {
        StorageChoiceDialog(
            kind = choosing,
            selected = settings.storage(choosing),
            onSelect = { choice ->
                choosingKey = null
                onUpdate { it.withStorage(choosing, choice) }
            },
            onDismiss = { choosingKey = null },
        )
    }
}

/** The places one kind of data can be kept, each with a line saying what it means. */
@Composable
fun StorageChoiceDialog(
    kind: DataKind,
    selected: StorageChoice,
    onSelect: (StorageChoice) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(kind.title) },
        text = {
            Column(Modifier.selectableGroup().verticalScroll(rememberScrollState())) {
                Text(kind.description, modifier = Modifier.padding(bottom = 8.dp))
                for (choice in kind.choices) {
                    Row(
                        verticalAlignment = Alignment.Top,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .selectable(selected = choice == selected, role = Role.RadioButton) { onSelect(choice) }
                                .padding(vertical = 8.dp),
                    ) {
                        RadioButton(selected = choice == selected, onClick = null)
                        Column(Modifier.padding(start = 16.dp)) {
                            Text(choice.title, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                choice.explanation,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
