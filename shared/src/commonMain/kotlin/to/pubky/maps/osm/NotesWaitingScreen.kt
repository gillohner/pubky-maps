package to.pubky.maps.osm

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import to.pubky.maps.place.savedText
import to.pubky.maps.settings.PageIntro
import to.pubky.maps.settings.SettingsPage
import to.pubky.maps.ui.AppIcons
import kotlin.time.Instant

/** The Settings row of the notes waiting on the phone; null when none waits, so the row is not shown. */
fun notesWaitingSummary(waiting: NotesWaiting?): String? =
    when (val count = waiting?.notes?.size ?: 0) {
        0 -> null
        1 -> "1 note to OpenStreetMap waits on this phone for a connection."
        else -> "$count notes to OpenStreetMap wait on this phone for a connection."
    }

/** Where a waiting note is about, in words: the place's name, else its point. */
private fun WaitingNote.placeText(): String = target.name ?: target.pointText()

/**
 * Notes waiting: the notes to OpenStreetMap that no server could be reached for, oldest first, each
 * with its place, its text and when it was written. [onTryNow] sends them now; a note can be deleted
 * ([onDelete], after asking) until it is on its way. [zone] gives the times; [waiting] is null until
 * the phone's outbox was read.
 */
@Composable
fun NotesWaitingScreen(
    waiting: NotesWaiting?,
    onTryNow: () -> Unit,
    onDelete: (String) -> Unit,
    onBack: () -> Unit,
    zone: TimeZone = TimeZone.currentSystemDefault(),
) {
    var deleting by rememberSaveable { mutableStateOf<String?>(null) }
    SettingsPage(title = "Notes waiting", onBack = onBack) {
        val notes = waiting?.notes
        when {
            notes == null -> {
                item { PageIntro("Reading the notes on this phone…") }
            }

            notes.isEmpty() -> {
                item { PageIntro("No notes wait on this phone. Those that went are on the map for OpenStreetMap's mappers.") }
            }

            else -> {
                item {
                    PageIntro(
                        "No OpenStreetMap server could be reached when you sent these notes. They wait on this phone and go " +
                            "by themselves once one can be, also when the app is closed.",
                    )
                }
                item {
                    OutlinedButton(
                        onClick = onTryNow,
                        enabled = waiting.sending == null,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    ) { Text(if (waiting.sending == null) "Try now" else "Sending…") }
                }
                for (note in notes) {
                    item(key = note.id) {
                        WaitingNoteRow(note, sending = note.id == waiting.sending, zone = zone, onDelete = { deleting = note.id })
                    }
                }
            }
        }
    }
    val confirming = waiting?.notes?.firstOrNull { it.id == deleting && it.id != waiting.sending }
    // A note that went on its way or left while asked about is no longer asked about: the question
    // does not come back by itself once its try reached no server.
    LaunchedEffect(confirming == null) { if (confirming == null) deleting = null }
    if (confirming != null) {
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete this note?") },
            text = { Text("Your note about ${confirming.placeText()} is deleted from this phone and never goes to OpenStreetMap.") },
            confirmButton = {
                TextButton(onClick = {
                    deleting = null
                    onDelete(confirming.id)
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun WaitingNoteRow(
    note: WaitingNote,
    sending: Boolean,
    zone: TimeZone,
    onDelete: () -> Unit,
) {
    val written = savedText(Instant.fromEpochMilliseconds(note.writtenAt).toLocalDateTime(zone))
    ListItem(
        headlineContent = { Text(note.placeText(), maxLines = 2, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Column {
                Text(note.text, maxLines = 4, overflow = TextOverflow.Ellipsis)
                Text(
                    if (sending) "Sending…" else "Written $written · waits for a connection",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        },
        // A note on its way may reach a server: it can no longer be called back.
        trailingContent =
            if (sending) {
                null
            } else {
                {
                    IconButton(onClick = onDelete) {
                        Icon(AppIcons.Delete, contentDescription = "Delete the note about ${note.placeText()}")
                    }
                }
            },
    )
}

/** The Notes waiting page on the live [notes]. */
@Composable
fun NotesWaitingRoute(
    notes: NoteController,
    onBack: () -> Unit,
) {
    val waiting by notes.notesWaiting.collectAsStateWithLifecycle()
    NotesWaitingScreen(waiting = waiting, onTryNow = notes::flush, onDelete = notes::withdraw, onBack = onBack)
}
