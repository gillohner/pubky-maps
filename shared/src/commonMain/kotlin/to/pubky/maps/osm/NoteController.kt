package to.pubky.maps.osm

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.time.Clock

/** Where a note of one note page is: on its way, taken, waiting for a connection, or not sent and why. */
sealed interface NoteSend {
    data object Sending : NoteSend

    data object Sent : NoteSend

    /** No server could be reached: the note waits on the phone and is tried again once it is online. */
    data object Waiting : NoteSend

    /** Not sent: [answer] says whether a server refused it or none answered. The text stays on the page. */
    data class NotSent(
        val answer: NoteAnswer,
    ) : NoteSend
}

/**
 * The notes that wait on the phone for a connection, oldest first, as the Notes waiting page lists
 * them; [sending] is the one on its way now, which can no longer be deleted.
 */
data class NotesWaiting(
    val notes: List<WaitingNote>,
    val sending: String? = null,
)

/**
 * Sends the notes of the note pages, each named by its page's [pageId] (kept over the page being laid
 * out anew), so a page rotated or reopened while its note is on its way shows how it went, and a new
 * page starts empty. Runs in [scope], which outlives the page: closing the page while the note is
 * sent does not stop it. One note per page: a page sends again only after its note was not sent.
 *
 * A note no server could be reached for ([NoteAnswer.Unreachable]: the request reached none, so it
 * cannot become two) waits in the [outbox] on the phone instead of being lost, and goes when asked
 * ([flush]: the phone has a network again, the app comes to the front or starts, the outbox job
 * runs), oldest first, until the servers still cannot be reached. A waiting note that is not taken
 * otherwise (refused, failed, or no answer in time: it may be on the map) leaves the outbox, and the
 * page, when still open, says why. The first round waits for [ready] (on Android: the user's settings
 * read from the phone, so a note never goes to a server the user removed). Each try is marked in the
 * outbox before it goes: a note still marked when the app starts (it stopped before the answer, or the
 * phone could not delete the note) may be on the map, so it leaves as one with no answer, never going
 * twice; a note that cannot be marked is not tried. While the app runs, a note that reached no server
 * but could not be unmarked is remembered and still waits, and a note that got its answer but could not
 * be deleted is remembered too: the next round only deletes it, and its page keeps the answer it had.
 * The user may delete a waiting note ([withdraw]) until its try starts; a round never tries a note
 * deleted after it read the outbox. Without an outbox such a note is not sent, and the page keeps its text to send again.
 */
class NoteController(
    private val notes: OsmNotes,
    private val scope: CoroutineScope,
    private val outbox: NoteOutbox? = null,
    private val io: CoroutineDispatcher = Dispatchers.Default,
    private val clock: Clock = Clock.System,
    private val ready: suspend () -> Unit = {},
) {
    private val _sends = MutableStateFlow<Map<String, NoteSend>>(emptyMap())

    /** The note of each page that sent one. */
    val sends: StateFlow<Map<String, NoteSend>> = _sends.asStateFlow()

    private val _waiting = MutableStateFlow(if (outbox == null) false else null)

    /** Whether notes wait in the outbox for a connection; null until the outbox was read. */
    val waiting: StateFlow<Boolean?> = _waiting.asStateFlow()

    private val _notesWaiting = MutableStateFlow(if (outbox == null) NotesWaiting(emptyList()) else null)

    /**
     * The notes the user can see waiting: not those that got their answer or may be on the map from a
     * try the app stopped in (they leave in the next round); null until the outbox was read.
     */
    val notesWaiting: StateFlow<NotesWaiting?> = _notesWaiting.asStateFlow()

    /** One change of the outbox at a time, so [waiting] and [notesWaiting] follow what it holds. */
    private val outboxLock = Mutex()

    /** What the outbox holds, as last read or changed. */
    private var kept = emptyList<WaitingNote>()

    /** The note whose try is under way: its request may reach a server, so it is not deleted. */
    private var trying: String? = null

    /** Notes still marked on their way although their last try reached no server (the unmark failed). */
    private var notTried = emptySet<String>()

    /** Notes still in the outbox although they got their answer (the delete failed); their pages show it. */
    private var settled = emptySet<String>()

    /** Asks for the waiting notes to be sent; asks that come while they are sent make one more round. */
    private val flushes = Channel<Unit>(Channel.CONFLATED)

    init {
        outbox?.let { box ->
            scope.launch {
                outboxLock.withLock {
                    kept = read(box)
                    // A page Android brought back after stopping the app finds how its note is.
                    _sends.update { sends -> kept.associate { it.id to it.shownAtStart() } + sends }
                    publish()
                }
                // Before any note is marked, so waiting here never makes one look maybe sent.
                ready()
                flushes.consumeEach { sendWaiting(box) }
            }
            flush()
        }
    }

    fun send(
        pageId: String,
        target: NoteTarget,
        text: String,
    ) {
        val earlier = _sends.value[pageId]
        if (!canSendNote(target, text) || earlier == NoteSend.Sending || earlier == NoteSend.Sent || earlier == NoteSend.Waiting) {
            return
        }
        _sends.update { it + (pageId to NoteSend.Sending) }
        scope.launch {
            val answer = notes.create(target, text)
            val send =
                when {
                    answer == NoteAnswer.Sent -> {
                        NoteSend.Sent
                    }

                    answer == NoteAnswer.Unreachable && keep(WaitingNote(pageId, target, text, clock.now().toEpochMilliseconds())) -> {
                        NoteSend.Waiting
                    }

                    else -> {
                        NoteSend.NotSent(answer)
                    }
                }
            // A page closed for good while its note was on its way has nothing to show it on.
            _sends.update { if (pageId in it) it + (pageId to send) else it }
        }
    }

    /** Sends the notes that wait for a connection, now; nothing when none waits. */
    fun flush() {
        flushes.trySend(Unit)
    }

    /** The page [pageId] was closed for good: a note on its way still goes, but nothing shows how it went. */
    fun forget(pageId: String) = _sends.update { it - pageId }

    /**
     * Deletes the waiting note [id] from the phone: it never goes. Nothing happens while its try is under
     * way ([NotesWaiting.sending]) or when the phone's storage fails the delete (it stays listed).
     */
    fun withdraw(id: String) {
        val box = outbox ?: return
        scope.launch {
            outboxLock.withLock {
                if (id == trying || kept.none { it.id == id }) return@launch
                if (onDisk { box.delete(id) } == null) return@launch
                kept = kept.filterNot { it.id == id }
                notTried = notTried - id
                settled = settled - id
                publish()
            }
            _sends.update { it - id }
        }
    }

    /** Puts [note] in the outbox; false when there is none or the phone could not store it. */
    private suspend fun keep(note: WaitingNote): Boolean {
        val box = outbox ?: return false
        return outboxLock.withLock {
            val stored = onDisk { box.put(note) } != null
            if (stored) {
                kept = (kept.filterNot { it.id == note.id } + note).sortedBy { it.writtenAt }
                publish()
            }
            stored
        }
    }

    private suspend fun sendWaiting(box: NoteOutbox) {
        val read =
            outboxLock.withLock {
                // What the phone holds now: also notes an earlier read failed to see. A note missing
                // from [kept] below was deleted by the user after this read.
                onDisk { box.waiting() }?.also {
                    kept = it
                    publish()
                } ?: emptyList()
            }
        val (mayBeSent, waiting) = read.partition { it.onItsWay && it.id !in notTried }
        for (note in mayBeSent) settle(box, note, NoteAnswer.NoAnswer.takeUnless { note.id in settled })
        for (note in waiting) {
            val marked =
                outboxLock.withLock {
                    // The user deleted it after this round read the outbox.
                    if (kept.none { it.id == note.id }) return@withLock false
                    (onDisk { box.setOnItsWay(note.id, true) } != null).also { done ->
                        if (done) {
                            notTried = notTried - note.id
                            trying = note.id
                            kept = kept.map { if (it.id == note.id) it.copy(onItsWay = true) else it }
                            publish()
                        }
                    }
                }
            if (!marked) continue
            val answer = notes.create(note.target, note.text)
            if (answer == NoteAnswer.Unreachable) {
                outboxLock.withLock {
                    // A mark left on a note that reached no server would settle it as maybe sent: remember it.
                    if (onDisk { box.setOnItsWay(note.id, false) } == null) notTried = notTried + note.id
                    kept = kept.map { if (it.id == note.id) it.copy(onItsWay = false) else it }
                    trying = null
                    publish()
                }
                // Still no server reached: the later notes would reach none either.
                return
            }
            settle(box, note, answer)
        }
    }

    /**
     * [note] got its [answer]: it leaves the outbox, and its page, when open, shows the answer; a null
     * [answer] only deletes a note that got one in an earlier round.
     */
    private suspend fun settle(
        box: NoteOutbox,
        note: WaitingNote,
        answer: NoteAnswer?,
    ) {
        outboxLock.withLock {
            // A delete the phone's storage failed leaves the note marked: the next round deletes it
            // again, without sending it, and its page keeps this answer.
            if (onDisk { box.delete(note.id) } != null) {
                kept = kept.filterNot { it.id == note.id }
                settled = settled - note.id
            } else {
                settled = settled + note.id
            }
            if (trying == note.id) trying = null
            publish()
        }
        if (answer == null) return
        val send = if (answer == NoteAnswer.Sent) NoteSend.Sent else NoteSend.NotSent(answer)
        _sends.update { if (note.id in it) it + (note.id to send) else it }
    }

    /** Shows what the outbox holds; called with [outboxLock] held. */
    private fun publish() {
        _waiting.value = kept.isNotEmpty()
        val shown = kept.filter { it.id !in settled && (it.id == trying || !it.onItsWay || it.id in notTried) }
        _notesWaiting.value = NotesWaiting(shown, trying)
    }

    /** How a page finds a kept note when the app starts: waiting, or maybe on the map when a try was cut off. */
    private fun WaitingNote.shownAtStart(): NoteSend = if (onItsWay) NoteSend.NotSent(NoteAnswer.NoAnswer) else NoteSend.Waiting

    private suspend fun read(box: NoteOutbox): List<WaitingNote> = onDisk { box.waiting() } ?: emptyList()

    /** [block] on [io]; null when the phone's storage failed it. */
    private suspend fun <T> onDisk(block: () -> T): T? =
        withContext(io) {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
        }
}
