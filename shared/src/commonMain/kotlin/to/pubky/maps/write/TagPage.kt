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
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp

/** What the tag page shows: the user's own tags on the place (some maybe not sent yet) and labels to add with one tap. */
data class TagPageState(
    val placeName: String?,
    val mine: List<OwnTag>,
    val suggestions: List<String>,
    /** What the user is typing. */
    val text: String = "",
)

/**
 * Tag a place: one tap on a suggestion tags it, a tap on one of the user's own tags removes it,
 * and the field adds any word as a label (folded to the spec's rules, shown before adding). Tags
 * are public, which the page says first.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TagPage(
    state: TagPageState,
    onTextChange: (String) -> Unit,
    onAdd: (String) -> Unit,
    onRemove: (String) -> Unit,
    onClose: () -> Unit,
) {
    WritePage(title = "Tag this place", place = state.placeName, onClose = onClose, action = {
        TextButton(onClick = onClose) { Text("Done") }
    }) {
        KeptNote(Kept.Public)
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
                        FilterChip(
                            selected = true,
                            onClick = { onRemove(tag.label) },
                            label = { Text(tag.label) },
                            leadingIcon = {
                                Icon(
                                    if (tag.pending) WriteIcons.Pending else WriteIcons.Check,
                                    contentDescription = if (tag.pending) "Not sent yet" else null,
                                    modifier = Modifier.size(FilterChipDefaults.IconSize),
                                )
                            },
                        )
                    }
                }
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
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                WriteIcons.Tag,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                "Tags help others find places on Pubky. Each tap is sent at once.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
