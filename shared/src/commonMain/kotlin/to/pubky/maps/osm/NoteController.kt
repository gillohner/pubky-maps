package to.pubky.maps.osm

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Where a note of one note page is: on its way, taken, or not sent and why. */
sealed interface NoteSend {
    data object Sending : NoteSend

    data object Sent : NoteSend

    /** Not sent: [answer] says whether a server refused it or none answered. The text stays on the page. */
    data class NotSent(
        val answer: NoteAnswer,
    ) : NoteSend
}

/**
 * Sends the notes of the note pages, each named by its page's [pageId] (kept over the page being laid
 * out anew), so a page rotated or reopened while its note is on its way shows how it went, and a new
 * page starts empty. Runs in [scope], which outlives the page: closing the page while the note is
 * sent does not stop it. One note per page: a page sends again only after its note was not sent.
 */
class NoteController(
    private val notes: OsmNotes,
    private val scope: CoroutineScope,
) {
    private val _sends = MutableStateFlow<Map<String, NoteSend>>(emptyMap())

    /** The note of each page that sent one. */
    val sends: StateFlow<Map<String, NoteSend>> = _sends.asStateFlow()

    fun send(
        pageId: String,
        target: NoteTarget,
        text: String,
    ) {
        val earlier = _sends.value[pageId]
        if (!canSendNote(target, text) || earlier == NoteSend.Sending || earlier == NoteSend.Sent) return
        _sends.update { it + (pageId to NoteSend.Sending) }
        scope.launch {
            val answer = notes.create(target, text)
            val send = if (answer == NoteAnswer.Sent) NoteSend.Sent else NoteSend.NotSent(answer)
            // A page closed for good while its note was on its way has nothing to show it on.
            _sends.update { if (pageId in it) it + (pageId to send) else it }
        }
    }

    /** The page [pageId] was closed for good: a note on its way still goes, but nothing shows how it went. */
    fun forget(pageId: String) = _sends.update { it - pageId }
}
