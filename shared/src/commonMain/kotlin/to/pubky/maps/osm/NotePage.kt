package to.pubky.maps.osm

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import to.pubky.maps.place.formatCoordinate
import to.pubky.maps.ui.SystemBack
import to.pubky.maps.write.Kept
import to.pubky.maps.write.KeptBadge
import to.pubky.maps.write.WritePage
import kotlin.random.Random

/** What the page says when a note was not sent. */
fun notSentText(answer: NoteAnswer): String =
    when (answer) {
        is NoteAnswer.Refused -> {
            "The server did not take the note: ${answer.reason.trimEnd('.')}."
        }

        NoteAnswer.NoAnswer -> {
            "No answer in time: the note may be on the map already. Send it again only if it is not there tomorrow."
        }

        NoteAnswer.Sent, NoteAnswer.Unreachable -> {
            "Not sent: no server could be reached. Check your connection and send it again."
        }
    }

/** Where the note goes on the map, in words. */
internal fun NoteTarget.pointText(): String = "${formatCoordinate(point.lat)}, ${formatCoordinate(point.lon)}"

/**
 * Report a problem with the map at a place: a note to OpenStreetMap's mappers, public and without the
 * user's name, at the place's point. [send] is how the page's note went, null before Send.
 */
@Composable
fun NotePage(
    target: NoteTarget,
    text: String,
    onTextChange: (String) -> Unit,
    send: NoteSend?,
    onSend: () -> Unit,
    onClose: () -> Unit,
) {
    val sending = send == NoteSend.Sending
    WritePage(
        title = "Report a map problem",
        place = target.name ?: target.pointText(),
        onClose = onClose,
        action =
            if (send == NoteSend.Sent) {
                null
            } else {
                { Button(onClick = onSend, enabled = !sending && canSendNote(target, text)) { Text(if (sending) "Sending" else "Send") } }
            },
    ) {
        if (send == NoteSend.Sent) {
            Sent(onClose)
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                KeptBadge(Kept.Public)
                Text(
                    "Anyone can read it on OpenStreetMap. It goes without your name, at ${target.pointText()}.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            val left = noteCharactersLeft(target, text)
            OutlinedTextField(
                value = text,
                onValueChange = onTextChange,
                enabled = !sending,
                label = { Text("What is wrong or missing?") },
                placeholder = { Text("For example: it closed, it moved, the opening hours changed, the entrance is round the back.") },
                supportingText = { Text(if (left < 0) "${-left} characters too long" else "$left characters left") },
                isError = left < 0,
                minLines = 6,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp),
            )
            (send as? NoteSend.NotSent)?.let {
                Text(
                    notSentText(it.answer),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
            Text(
                "Volunteers who map OpenStreetMap read notes and fix the map for every app that uses it." +
                    if (target.name != null || target.osm != null) " The note names the place for them." else "",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Sent(onDone: () -> Unit) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Text(
            "Thank you, your note is on the map",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            "OpenStreetMap's mappers see it at the place and fix the map there. That can take a few days.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Button(onClick = onDone) { Text("Done") }
}

/** The note page on the live [notes]: the text and the page's name kept over the page being laid out anew. */
@Composable
fun NoteRoute(
    notes: NoteController,
    target: NoteTarget,
    onClose: () -> Unit,
) {
    val pageId = rememberSaveable { Random.nextLong().toULong().toString(36) }
    var text by rememberSaveable { mutableStateOf("") }
    val sends by notes.sends.collectAsStateWithLifecycle()
    val close = {
        notes.forget(pageId)
        onClose()
    }
    SystemBack(onBack = close)
    NotePage(
        target = target,
        text = text,
        onTextChange = { text = it },
        send = sends[pageId],
        onSend = { notes.send(pageId, target, text) },
        onClose = close,
    )
}
