package to.pubky.maps.write

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp

/** The spec's limit for a note post, in characters. */
const val POST_MAX = 2000

/** How long [text] is for the spec (code points). */
internal fun postLength(text: String): Int = text.trim().let { trimmed -> trimmed.codePointCountCompat() }

private fun String.codePointCountCompat(): Int {
    var count = 0
    var i = 0
    while (i < length) {
        i += if (this[i].isHighSurrogate() && i + 1 < length && this[i + 1].isLowSurrogate()) 2 else 1
        count++
    }
    return count
}

/** True when [text] can be posted: something to say, within the spec's limit. */
fun canPost(text: String): Boolean = postLength(text) in 1..POST_MAX

/** Write a post about a place (a review): public, up to 2000 characters. */
@Composable
fun PostPage(
    placeName: String?,
    text: String,
    onTextChange: (String) -> Unit,
    onPost: () -> Unit,
    onClose: () -> Unit,
) {
    WritePage(title = "Write a post", place = placeName, onClose = onClose, action = {
        Button(onClick = onPost, enabled = canPost(text)) { Text("Post") }
    }) {
        KeptNote(Kept.Public)
        val length = postLength(text)
        OutlinedTextField(
            value = text,
            onValueChange = onTextChange,
            label = { Text("What is it like?") },
            placeholder = { Text("Share what you liked, what to know before going, a tip for others.") },
            supportingText = { Text("$length / $POST_MAX") },
            isError = length > POST_MAX,
            minLines = 6,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp),
        )
        Text(
            "Your post is a reply to this place on Pubky.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
