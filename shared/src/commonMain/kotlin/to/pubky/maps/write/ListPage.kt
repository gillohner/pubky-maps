package to.pubky.maps.write

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

/** The spec's limit for a list's name. */
const val LIST_NAME_MAX = 100

/** The spec's limit for a list's description (`collection_description_max_length`). */
const val LIST_DESCRIPTION_MAX = 500

/**
 * A description's length as the spec counts it: characters (code points, so an emoji is one) of the
 * text that is sent, trimmed.
 */
internal fun descriptionLength(description: String): Int {
    val sent = description.trim()
    var count = 0
    var i = 0
    while (i < sent.length) {
        i += if (sent[i].isHighSurrogate() && i + 1 < sent.length && sent[i + 1].isLowSurrogate()) 2 else 1
        count++
    }
    return count
}

/** One of the user's lists on the list page. */
data class ListChoice(
    val list: OwnList,
    /** It holds the place. */
    val holds: Boolean,
)

/** What the list page shows. */
data class ListPageState(
    val placeName: String?,
    val lists: List<ListChoice>,
    /** The lists are being read from the homeserver. */
    val loading: Boolean = false,
    /** They could not be read now: the ones shown are as last read. */
    val stale: Boolean = false,
    /** The new list's name as typed. */
    val newName: String = "",
    /** The new list's description as typed; blank for none. */
    val newDescription: String = "",
    /** Where a new list goes: the user's Lists choice until they pick another for it. */
    val newKept: Kept = Kept.Private,
    /** Where a new list may go (signed out: this phone only). */
    val keptChoices: List<Kept> = Kept.entries.toList(),
)

/**
 * Add a place to one of the user's lists, or take it out (each tick is sent at once), or make a new
 * list with the place in it. Where each list is kept is shown beside it; a full list says so.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ListPage(
    state: ListPageState,
    onToggle: (ListChoice) -> Unit,
    onNewNameChange: (String) -> Unit,
    onNewDescriptionChange: (String) -> Unit,
    onNewKeptChange: (Kept) -> Unit,
    onCreate: () -> Unit,
    onClose: () -> Unit,
    snackbarHostState: SnackbarHostState? = null,
) {
    // Each tick is sent at once, so there is nothing to confirm: Close (the X) only, and Undo in a snackbar.
    WritePage(title = "Add to collection", place = state.placeName, onClose = onClose, snackbarHostState = snackbarHostState) {
        if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (state.stale) {
            Text(
                "Your collections could not be read now; these are as they were last time.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (state.lists.isNotEmpty()) {
            WriteSection("Your collections") {
                Column {
                    state.lists.forEach { choice -> ListRow(choice, onToggle) }
                }
            }
        } else if (!state.loading) {
            Text("You have no collections yet. Make one below.", style = MaterialTheme.typography.bodyMedium)
        }
        WriteSection("New collection") {
            OutlinedTextField(
                value = state.newName,
                onValueChange = onNewNameChange,
                label = { Text("Title") },
                singleLine = true,
                isError = state.newName.length > LIST_NAME_MAX,
                supportingText = { Text("${state.newName.length} / $LIST_NAME_MAX") },
                modifier = Modifier.fillMaxWidth(),
            )
            // Title and Description, as pubky.app's New Collection asks; the description shows under the
            // collection's name on the Bookmarks page and on pubky.app's collection cards.
            val descriptionChars = descriptionLength(state.newDescription)
            OutlinedTextField(
                value = state.newDescription,
                onValueChange = onNewDescriptionChange,
                label = { Text("Description (optional)") },
                minLines = 2,
                maxLines = 4,
                isError = descriptionChars > LIST_DESCRIPTION_MAX,
                supportingText = { Text("$descriptionChars / $LIST_DESCRIPTION_MAX") },
                modifier = Modifier.fillMaxWidth(),
            )
            Column(Modifier.selectableGroup()) {
                state.keptChoices.forEach { kept ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .selectable(selected = kept == state.newKept, onClick = { onNewKeptChange(kept) }, role = Role.RadioButton)
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = kept == state.newKept, onClick = null)
                        Column(Modifier.padding(start = 12.dp)) {
                            Text(kept.badgeText, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                kept.explanation,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            // Where the list goes, once more beside the button that sends it (under it when large text
            // leaves no room beside it).
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                Button(
                    onClick = onCreate,
                    enabled =
                        state.newName.isNotBlank() &&
                            state.newName.length <= LIST_NAME_MAX &&
                            descriptionChars <= LIST_DESCRIPTION_MAX,
                ) { Text("Create collection with this place") }
                KeptBadge(state.newKept)
            }
        }
        Text(
            "A collection holds up to ${OwnList.MAX_LIST_ITEMS} items. Each tick is sent at once.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ListRow(
    choice: ListChoice,
    onToggle: (ListChoice) -> Unit,
) {
    val list = choice.list
    // A full list can still lose the place. Changes to a list not sent yet are sent after it.
    val enabled = choice.holds || !list.full
    val count = list.items.size
    val detail =
        when {
            list.full && !choice.holds && list.otherItems > 0 -> "Full: $count places and ${list.otherItemsText}"
            list.full && !choice.holds -> "Full: $count of ${OwnList.MAX_LIST_ITEMS} places"
            count == 1 -> "1 place"
            else -> "$count places"
        }
    ListItem(
        headlineContent = { Text(list.name) },
        supportingContent = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                KeptBadge(list.kept)
                Text(detail + if (list.pending) " · not sent yet" else "")
            }
        },
        leadingContent = { Checkbox(checked = choice.holds, onCheckedChange = null, enabled = enabled) },
        modifier =
            Modifier.toggleable(
                value = choice.holds,
                enabled = enabled,
                role = Role.Checkbox,
                onValueChange = { onToggle(choice) },
            ),
    )
}
