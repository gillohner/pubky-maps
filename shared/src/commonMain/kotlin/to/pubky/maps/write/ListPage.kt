package to.pubky.maps.write

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import to.pubky.maps.ui.AppIcons
import to.pubky.maps.ui.SystemBack

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

/**
 * The New collection form in the route: open or not, and what is typed. Closing it (Cancel, back, or
 * Create, after which the new collection shows ticked among the others) drops what was typed, as
 * pubky.app's Cancel does.
 */
data class NewCollectionDraft(
    val open: Boolean = false,
    val name: String = "",
    val description: String = "",
) {
    fun withOpen(open: Boolean): NewCollectionDraft = if (open) copy(open = true) else NewCollectionDraft()

    /** After Create: closed and empty. */
    fun created(): NewCollectionDraft = NewCollectionDraft()

    companion object {
        val Saver: Saver<NewCollectionDraft, Any> =
            listSaver(
                save = { listOf(it.open, it.name, it.description) },
                restore = { NewCollectionDraft(it[0] as Boolean, it[1] as String, it[2] as String) },
            )
    }
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
    /**
     * The New collection form is open: the user opened it, or typed in the one shown because they had
     * none, until Cancel, back or Create. With no collections it shows anyway (there is nothing to
     * tick), see [newFormShown].
     */
    val newOpen: Boolean = false,
    /** The new list's name as typed. */
    val newName: String = "",
    /** The new list's description as typed; blank for none. */
    val newDescription: String = "",
    /** Where a new list goes: the user's Lists choice until they pick another for it. */
    val newKept: Kept = Kept.Private,
    /** Where a new list may go (signed out: this phone only). */
    val keptChoices: List<Kept> = Kept.entries.toList(),
) {
    /**
     * The New collection form is on the page: opened, or the user has no collections to tick. Also
     * while they are read: the read starts a moment after the page opens, and a form that left and came
     * back with it would make the page jump on every open for a user with none.
     */
    val newFormShown: Boolean get() = newOpen || lists.isEmpty()

    /** The form can be closed back to the collections (with none, the page's Close is the way out). */
    val newFormClosable: Boolean get() = newOpen && lists.isNotEmpty()
}

/**
 * Add a place to one of the user's lists, or take it out (each tick is sent at once), or make a new
 * list with the place in it. Where each list is kept is shown beside it; a full list says so.
 *
 * The collections come first, under a "New collection" row that opens the form for a new one, as
 * Google Maps' Save sheet has "New list" above the lists and pubky.app opens New Collection as its own
 * card: always open, the form (title, description, where it goes, about 450 dp) would take half a
 * phone screen beside the collections. With no collections the form is open from the start, since
 * there is nothing else to do on the page.
 */
@Composable
fun ListPage(
    state: ListPageState,
    onToggle: (ListChoice) -> Unit,
    onNewOpenChange: (Boolean) -> Unit,
    onNewNameChange: (String) -> Unit,
    onNewDescriptionChange: (String) -> Unit,
    onNewKeptChange: (Kept) -> Unit,
    onCreate: () -> Unit,
    onClose: () -> Unit,
    snackbarHostState: SnackbarHostState? = null,
) {
    // Cancel and back put the focus on the New collection row that takes the form's place, so TalkBack
    // stays where the user was instead of starting again at the top of the page.
    var focusNewRow by remember { mutableStateOf(false) }
    val closeForm = {
        focusNewRow = true
        onNewOpenChange(false)
    }
    // Back closes an opened form before it closes the page.
    SystemBack(enabled = state.newFormClosable, onBack = closeForm)
    // Each tick is sent at once, so there is nothing to confirm: Close (the X) only, and Undo in a snackbar.
    WritePage(title = "Add to collection", place = state.placeName, onClose = onClose, snackbarHostState = snackbarHostState) {
        // With none, the bar stands in the "no collections" line's place at its height while they are
        // read, so the form under it does not move up and back down as the read starts and ends.
        if (state.loading && state.lists.isNotEmpty()) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (state.stale) {
            Text(
                "Your collections could not be read now; these are as they were last time.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (state.lists.isEmpty()) NoCollectionsLine(state.loading)
        if (state.newFormShown) {
            // Typing opens the form: one shown because the user had none stays, Cancel and all, when the
            // read then finds collections (made on another device), so what they typed and the keyboard do
            // not vanish under their fingers, also when they clear the text to start again.
            val typed = { if (!state.newOpen) onNewOpenChange(true) }
            NewCollectionForm(
                state,
                onNewNameChange = {
                    typed()
                    onNewNameChange(it)
                },
                onNewDescriptionChange = {
                    typed()
                    onNewDescriptionChange(it)
                },
                onNewKeptChange = onNewKeptChange,
                onCreate = onCreate,
                onCancel = closeForm,
            )
        }
        if (state.lists.isNotEmpty()) {
            WriteSection("Your collections") {
                Column {
                    if (!state.newFormShown) {
                        NewCollectionRow(
                            takeFocus = focusNewRow,
                            onFocusTaken = { focusNewRow = false },
                            onOpen = { onNewOpenChange(true) },
                        )
                    }
                    state.lists.forEach { choice -> ListRow(choice, onToggle) }
                }
            }
        }
        Text(
            "A collection holds up to ${OwnList.MAX_LIST_ITEMS} items. Each tick is sent at once.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** "You have no collections yet.", or the bar in its place (and at its height) while they are read. */
@Composable
private fun NoCollectionsLine(loading: Boolean) {
    Box(contentAlignment = Alignment.CenterStart) {
        Text(
            "You have no collections yet.",
            style = MaterialTheme.typography.bodyMedium,
            // Kept for its height only while they are read: not seen, not read out.
            modifier = if (loading) Modifier.alpha(0f).clearAndSetSemantics {} else Modifier,
        )
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
    }
}

/**
 * The row that opens the New collection form, first among the collections. With [takeFocus] (the form
 * was just closed) it takes the focus as it comes back; [onFocusTaken] then clears that.
 */
@Composable
private fun NewCollectionRow(
    takeFocus: Boolean,
    onFocusTaken: () -> Unit,
    onOpen: () -> Unit,
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        if (takeFocus) {
            focus.requestFocus()
            onFocusTaken()
        }
    }
    ListItem(
        headlineContent = { Text("New collection") },
        leadingContent = {
            // In the checkboxes' column, so the names line up under it.
            Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                Icon(AppIcons.Add, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            }
        },
        // A clickable takes no focus in touch mode (where Cancel was tapped), so the row says it can:
        // still one focus target, so a keyboard stops on it once.
        modifier = Modifier.focusRequester(focus).focusProperties { canFocus = true }.clickable(role = Role.Button, onClick = onOpen),
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NewCollectionForm(
    state: ListPageState,
    onNewNameChange: (String) -> Unit,
    onNewDescriptionChange: (String) -> Unit,
    onNewKeptChange: (Kept) -> Unit,
    onCreate: () -> Unit,
    onCancel: () -> Unit,
) {
    val title = remember { FocusRequester() }
    // Opened by the user: the title is what they type first. Not when the form is simply there because
    // there are no collections, so the keyboard does not cover the page as it opens; not when it opens
    // because they typed in a field of it; and once only, so a turn of the phone does not pull the
    // cursor back from the description.
    var focused by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(state.newOpen) {
        if (state.newOpen && !focused) {
            title.requestFocus()
            focused = true
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "New collection",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            if (state.newFormClosable) TextButton(onClick = onCancel) { Text("Cancel") }
        }
        OutlinedTextField(
            value = state.newName,
            onValueChange = onNewNameChange,
            label = { Text("Title") },
            singleLine = true,
            isError = state.newName.length > LIST_NAME_MAX,
            supportingText = { Text("${state.newName.length} / $LIST_NAME_MAX") },
            modifier = Modifier.fillMaxWidth().focusRequester(title).onFocusChanged { if (it.isFocused) focused = true },
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
            modifier = Modifier.fillMaxWidth().onFocusChanged { if (it.isFocused) focused = true },
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
