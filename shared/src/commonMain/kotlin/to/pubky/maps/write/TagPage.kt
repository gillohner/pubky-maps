package to.pubky.maps.write

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import to.pubky.maps.ui.AppIcons
import to.pubky.maps.ui.NoteWithAction

/** What the tag page shows: the user's own tags on the place (some maybe not sent yet) and labels to add with one tap. */
data class TagPageState(
    val placeName: String?,
    val mine: List<OwnTag>,
    val suggestions: List<String>,
    /** What the user is typing. */
    val text: String = "",
    /** Writes wait for the homeserver (no connection, or it is busy): pending tags say so. */
    val waiting: Boolean = false,
    /** The homeserver was reached but is busy (429, 5xx). */
    val busy: Boolean = false,
)

/** How a tag that is not sent yet is described: on screen beside the clock, and to screen readers. */
internal fun pendingText(
    waiting: Boolean,
    busy: Boolean,
): String =
    when {
        waiting && busy -> "Waiting: your homeserver is busy"
        waiting -> "Waiting for a connection"
        else -> "Sending…"
    }

/**
 * Tag a place: one tap on a suggestion tags it, the × on one of the user's own tags removes it, and
 * the field adds any word as a label (folded to the spec's rules, shown before adding). Every tap is
 * sent at once, which the Public notice at the top says; a snackbar offers Undo.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TagPage(
    state: TagPageState,
    onTextChange: (String) -> Unit,
    onAdd: (String) -> Unit,
    onRemove: (String) -> Unit,
    onRetry: () -> Unit,
    onClose: () -> Unit,
    snackbarHostState: SnackbarHostState? = null,
) {
    WritePage(title = "Tag this place", place = state.placeName, onClose = onClose, snackbarHostState = snackbarHostState) {
        KeptNote(Kept.Public, more = "Each tap is sent at once.")
        val label = state.text.takeIf { it.isNotBlank() }?.let(::tagLabel)
        val valid = (label as? LabelResult.Valid)?.label
        OutlinedTextField(
            value = state.text,
            onValueChange = onTextChange,
            label = { Text("Add a tag") },
            singleLine = true,
            supportingText = {
                when {
                    label is LabelResult.Invalid -> Text(label.reason)
                    valid != null && valid != state.text.trim() -> Text("Tagged as “$valid”")
                    else -> Text("One word or a few, up to $TAG_LABEL_MAX characters.")
                }
            },
            isError = label is LabelResult.Invalid,
            trailingIcon = {
                TextButton(onClick = { valid?.let(onAdd) }, enabled = valid != null && state.mine.none { it.label == valid }) {
                    Text("Add")
                }
            },
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { valid?.let(onAdd) }),
            modifier = Modifier.fillMaxWidth(),
        )
        if (state.mine.isNotEmpty()) {
            WriteSection("Your tags (tap to remove)") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    state.mine.forEach { tag ->
                        val pending = if (tag.pending) pendingText(state.waiting, state.busy) else null
                        InputChip(
                            selected = false,
                            onClick = { onRemove(tag.label) },
                            label = { Text(tag.label) },
                            leadingIcon =
                                if (tag.pending) {
                                    {
                                        Icon(
                                            WriteIcons.Pending,
                                            contentDescription = null,
                                            modifier = Modifier.size(InputChipDefaults.IconSize),
                                        )
                                    }
                                } else {
                                    null
                                },
                            trailingIcon = {
                                Icon(
                                    AppIcons.Close,
                                    contentDescription = null,
                                    modifier = Modifier.size(InputChipDefaults.IconSize),
                                )
                            },
                            modifier =
                                Modifier.semantics {
                                    contentDescription = listOfNotNull("Remove your tag ${tag.label}", pending).joinToString(". ")
                                },
                        )
                    }
                }
                if (state.mine.any { it.pending }) PendingLegend(state.waiting, state.busy, onRetry)
            }
        }
        if (state.suggestions.isNotEmpty()) {
            WriteSection("Suggested") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    state.suggestions.forEach { suggestion ->
                        FilterChip(selected = false, onClick = { onAdd(suggestion) }, label = { Text(suggestion) })
                    }
                }
            }
        }
        NoteText("Tags help others find places on Pubky.")
    }
}

/**
 * What the clock on a tag means, and a way to try again now when the writes wait. Try now sits beside
 * the note when the note fits on one line beside it, else under it at the end: squeezed beside a
 * wrapped note, the note gets a narrow column (at 200 % font three lines of two words; at the normal
 * phone font one word alone on its second line); see [NoteWithAction].
 */
@Composable
private fun PendingLegend(
    waiting: Boolean,
    busy: Boolean,
    onRetry: () -> Unit,
) {
    NoteWithAction(
        note = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    WriteIcons.Pending,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                NoteText(if (waiting) "${pendingText(true, busy)}: sent when it can be." else "Sending…")
            }
        },
        action = if (waiting) ({ TextButton(onClick = onRetry) { Text("Try now") } }) else null,
    )
}
