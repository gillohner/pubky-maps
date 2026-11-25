package to.pubky.maps.write

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

/**
 * Before the first one-tap tag on this phone: tags are public and each tap is sent at once. Shown
 * once; after it a tap on a tag chip tags straight away (with Undo in a snackbar).
 */
@Composable
fun PublicTagDialog(
    label: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(WriteIcons.Public, contentDescription = null) },
        title = { Text("Tags are public") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Tagging this place “$label” puts the tag on Pubky with your name: anyone can read it.",
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    "From now on a tap on a tag sends it at once. Tap your tag again, or Undo, to remove it.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Tag it") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
