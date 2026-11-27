package to.pubky.maps.osm

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import to.pubky.maps.config.AppSettings
import to.pubky.maps.config.ServerEntry
import to.pubky.maps.config.Service
import to.pubky.maps.config.loadedServers
import to.pubky.maps.links.GeoPoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Clock
import kotlin.time.Instant

class NoteControllerTest {
    private val target = NoteTarget(GeoPoint(47.3722, 8.5446), "Conditorei Schober")

    /** Answers each note with the next of [answers], once the test lets it through [hold] when one is set. */
    private class FakeNotes(
        vararg answers: NoteAnswer,
    ) : OsmNotes {
        val texts = ArrayList<String>()
        var hold: CompletableDeferred<Unit>? = null
        private val left = answers.toMutableList()

        override suspend fun create(
            target: NoteTarget,
            text: String,
        ): NoteAnswer {
            texts += text
            hold?.await()
            return left.removeAt(0)
        }
    }

    private fun TestScope.controller(notes: OsmNotes) = NoteController(notes, backgroundScope)

    /** The outbox in memory; [failPut], [failMark], [failUnmark] and [failDelete] stand for a phone whose storage refuses the write. */
    private class FakeOutbox(
        vararg kept: WaitingNote,
    ) : NoteOutbox {
        val notes = kept.toMutableList()
        var failPut = false

        override fun waiting(): List<WaitingNote> = notes.sortedBy { it.writtenAt }

        override fun put(note: WaitingNote) {
            if (failPut) error("disk full")
            notes.removeAll { it.id == note.id }
            notes += note
        }

        var failDelete = false

        override fun delete(id: String) {
            if (failDelete) error("disk full")
            notes.removeAll { it.id == id }
        }

        var failMark = false
        var failUnmark = false
        val marks = ArrayList<Pair<String, Boolean>>()

        override fun setOnItsWay(
            id: String,
            onItsWay: Boolean,
        ) {
            if (failMark || (failUnmark && !onItsWay)) error("disk full")
            marks += id to onItsWay
            val i = notes.indexOfFirst { it.id == id }
            if (i >= 0) notes[i] = notes[i].copy(onItsWay = onItsWay)
        }
    }

    private val at =
        object : Clock {
            override fun now() = Instant.fromEpochMilliseconds(1_000)
        }

    private fun TestScope.controller(
        notes: OsmNotes,
        outbox: NoteOutbox,
    ) = NoteController(notes, backgroundScope, outbox, io = StandardTestDispatcher(testScheduler), clock = at)

    private fun waiting(
        id: String,
        text: String,
        writtenAt: Long,
    ) = WaitingNote(id, target, text, writtenAt)

    @Test
    fun aSentNoteShowsOnItsPageOnly() =
        runTest {
            val notes = FakeNotes(NoteAnswer.Sent)
            val controller = controller(notes)
            notes.hold = CompletableDeferred()
            controller.send("a", target, "Closed for good.")
            runCurrent()
            assertEquals(mapOf("a" to NoteSend.Sending), controller.sends.value)
            notes.hold!!.complete(Unit)
            runCurrent()
            assertEquals(NoteSend.Sent, controller.sends.value["a"])
            assertNull(controller.sends.value["b"])
            assertEquals(listOf("Closed for good."), notes.texts)
        }

    @Test
    fun aPageSendsOnceWhileItsNoteIsOnItsWayAndNotAgainOnceSent() =
        runTest {
            val notes = FakeNotes(NoteAnswer.Sent, NoteAnswer.Sent)
            val controller = controller(notes)
            notes.hold = CompletableDeferred()
            controller.send("a", target, "Closed.")
            controller.send("a", target, "Closed.")
            notes.hold!!.complete(Unit)
            runCurrent()
            controller.send("a", target, "Closed.")
            runCurrent()
            assertEquals(1, notes.texts.size)
        }

    @Test
    fun aNoteNotSentKeepsWhyAndCanBeSentAgain() =
        runTest {
            val notes = FakeNotes(NoteAnswer.Unreachable, NoteAnswer.Sent)
            val controller = controller(notes)
            controller.send("a", target, "Closed.")
            runCurrent()
            assertEquals(NoteSend.NotSent(NoteAnswer.Unreachable), controller.sends.value["a"])
            controller.send("a", target, "Closed.")
            runCurrent()
            assertEquals(NoteSend.Sent, controller.sends.value["a"])
        }

    @Test
    fun anEmptyOrTooLongNoteIsNotSent() =
        runTest {
            val notes = FakeNotes()
            val controller = controller(notes)
            controller.send("a", target, "  ")
            controller.send("a", target, "a".repeat(NOTE_MAX))
            runCurrent()
            assertEquals(emptyList(), notes.texts)
            assertEquals(emptyMap(), controller.sends.value)
        }

    @Test
    fun aPageClosedWhileItsNoteIsOnItsWayStillSendsItAndShowsNothing() =
        runTest {
            val notes = FakeNotes(NoteAnswer.Sent)
            val controller = controller(notes)
            notes.hold = CompletableDeferred()
            controller.send("a", target, "Closed.")
            runCurrent()
            controller.forget("a")
            notes.hold!!.complete(Unit)
            runCurrent()
            assertEquals(listOf("Closed."), notes.texts)
            assertEquals(emptyMap(), controller.sends.value)
        }

    @Test
    fun aNoteNoServerCouldBeReachedForWaitsOnThePhoneAndCannotBeSentTwice() =
        runTest {
            val notes = FakeNotes(NoteAnswer.Unreachable)
            val outbox = FakeOutbox()
            val controller = controller(notes, outbox)
            runCurrent()
            assertEquals(false, controller.waiting.value)
            controller.send("a", target, "Closed.")
            runCurrent()
            assertEquals(NoteSend.Waiting, controller.sends.value["a"])
            assertEquals(listOf(WaitingNote("a", target, "Closed.", 1_000)), outbox.notes)
            assertEquals(true, controller.waiting.value)
            controller.send("a", target, "Closed.")
            runCurrent()
            assertEquals(1, notes.texts.size)
        }

    @Test
    fun aWaitingNoteGoesWhenThePhoneIsOnlineAndItsOpenPageThanks() =
        runTest {
            val notes = FakeNotes(NoteAnswer.Unreachable, NoteAnswer.Unreachable, NoteAnswer.Sent)
            val outbox = FakeOutbox()
            val controller = controller(notes, outbox)
            // The start's round has nothing to send.
            runCurrent()
            controller.send("a", target, "Closed.")
            runCurrent()
            // Still offline: the note stays.
            controller.flush()
            runCurrent()
            assertEquals(NoteSend.Waiting, controller.sends.value["a"])
            assertEquals(1, outbox.notes.size)
            controller.flush()
            runCurrent()
            assertEquals(NoteSend.Sent, controller.sends.value["a"])
            assertEquals(emptyList(), outbox.notes)
            assertEquals(false, controller.waiting.value)
            assertEquals(listOf("Closed.", "Closed.", "Closed."), notes.texts)
        }

    @Test
    fun waitingNotesGoAtTheStartOldestFirstAndStopWhileNoServerCanBeReached() =
        runTest {
            val notes = FakeNotes(NoteAnswer.Sent, NoteAnswer.Unreachable)
            val outbox = FakeOutbox(waiting("new", "Second.", 20), waiting("old", "First.", 10), waiting("newest", "Third.", 30))
            val controller = controller(notes, outbox)
            assertNull(controller.waiting.value)
            runCurrent()
            assertEquals(listOf("First.", "Second."), notes.texts)
            assertEquals(listOf("new", "newest"), outbox.notes.map { it.id })
            assertEquals(true, controller.waiting.value)
        }

    @Test
    fun aPageBroughtBackAfterTheAppWasStoppedFindsItsNoteWaitingThenSent() =
        runTest {
            val notes = FakeNotes(NoteAnswer.Sent)
            notes.hold = CompletableDeferred()
            val controller = controller(notes, FakeOutbox(waiting("a", "Closed.", 10)))
            runCurrent()
            assertEquals(mapOf("a" to NoteSend.Waiting), controller.sends.value)
            notes.hold!!.complete(Unit)
            runCurrent()
            assertEquals(mapOf("a" to NoteSend.Sent), controller.sends.value)
        }

    @Test
    fun aWaitingNoteRefusedOrUnansweredLeavesThePhoneAndItsPageSaysWhy() =
        runTest {
            val notes = FakeNotes(NoteAnswer.Refused("Anonymous notes are off"), NoteAnswer.NoAnswer)
            val outbox = FakeOutbox(waiting("a", "First.", 10), waiting("b", "Second.", 20))
            val controller = controller(notes, outbox)
            runCurrent()
            assertEquals(emptyList(), outbox.notes)
            assertEquals(false, controller.waiting.value)
            assertEquals(NoteSend.NotSent(NoteAnswer.Refused("Anonymous notes are off")), controller.sends.value["a"])
            assertEquals(NoteSend.NotSent(NoteAnswer.NoAnswer), controller.sends.value["b"])
        }

    @Test
    fun aPageClosedBeforeItsWaitingNoteWentShowsNothingButTheNoteGoes() =
        runTest {
            val notes = FakeNotes(NoteAnswer.Unreachable, NoteAnswer.Sent)
            val outbox = FakeOutbox()
            val controller = controller(notes, outbox)
            runCurrent()
            controller.send("a", target, "Closed.")
            runCurrent()
            controller.forget("a")
            controller.flush()
            runCurrent()
            assertEquals(emptyMap(), controller.sends.value)
            assertEquals(emptyList(), outbox.notes)
        }

    @Test
    fun aNoteThePhoneCouldNotKeepIsNotSentAndKeepsItsText() =
        runTest {
            val outbox = FakeOutbox().apply { failPut = true }
            val controller = controller(FakeNotes(NoteAnswer.Unreachable), outbox)
            controller.send("a", target, "Closed.")
            runCurrent()
            assertEquals(NoteSend.NotSent(NoteAnswer.Unreachable), controller.sends.value["a"])
            assertEquals(false, controller.waiting.value)
        }

    @Test
    fun eachTryIsMarkedFirstAndANoteNoServerReachedWaitsUnmarked() =
        runTest {
            val notes = FakeNotes(NoteAnswer.Unreachable)
            val outbox = FakeOutbox(waiting("a", "Closed.", 10))
            controller(notes, outbox)
            runCurrent()
            assertEquals(listOf("a" to true, "a" to false), outbox.marks)
            assertEquals(listOf(false), outbox.notes.map { it.onItsWay })
        }

    @Test
    fun aNoteWhoseTryWasCutOffIsNotSentAgainAndItsPageSaysItMayBeOnTheMap() =
        runTest {
            val notes = FakeNotes(NoteAnswer.Sent)
            val outbox = FakeOutbox(waiting("a", "Cut off.", 10).copy(onItsWay = true), waiting("b", "Waiting.", 20))
            val controller = controller(notes, outbox)
            runCurrent()
            assertEquals(listOf("Waiting."), notes.texts)
            assertEquals(emptyList(), outbox.notes)
            assertEquals(NoteSend.NotSent(NoteAnswer.NoAnswer), controller.sends.value["a"])
            assertEquals(NoteSend.Sent, controller.sends.value["b"])
        }

    @Test
    fun aNoteNoServerReachedThatCannotBeUnmarkedStillWaitsAndGoesNextTime() =
        runTest {
            val notes = FakeNotes(NoteAnswer.Unreachable, NoteAnswer.Sent)
            val outbox = FakeOutbox(waiting("a", "Closed.", 10)).apply { failUnmark = true }
            val controller = controller(notes, outbox)
            runCurrent()
            assertEquals(listOf(true), outbox.notes.map { it.onItsWay })
            assertEquals(NoteSend.Waiting, controller.sends.value["a"])
            controller.flush()
            runCurrent()
            assertEquals(listOf("Closed.", "Closed."), notes.texts)
            assertEquals(NoteSend.Sent, controller.sends.value["a"])
            assertEquals(emptyList(), outbox.notes)
        }

    @Test
    fun aNoteThatCannotBeMarkedIsNotTried() =
        runTest {
            val notes = FakeNotes()
            val outbox = FakeOutbox(waiting("a", "Closed.", 10)).apply { failMark = true }
            val controller = controller(notes, outbox)
            runCurrent()
            assertEquals(emptyList(), notes.texts)
            assertEquals(1, outbox.notes.size)
            assertEquals(true, controller.waiting.value)
        }

    @Test
    fun aNoteAServerMayHaveIsNotKeptToGoAgain() =
        runTest {
            val outbox = FakeOutbox()
            val controller = controller(FakeNotes(NoteAnswer.Failed), outbox)
            controller.send("a", target, "Closed.")
            runCurrent()
            assertEquals(NoteSend.NotSent(NoteAnswer.Failed), controller.sends.value["a"])
            assertEquals(emptyList(), outbox.notes)
        }

    @Test
    fun aWaitingNoteThatGotItsAnswerButCouldNotBeDeletedKeepsItAndIsOnlyDeletedNextTime() =
        runTest {
            val notes = FakeNotes(NoteAnswer.Sent)
            val outbox = FakeOutbox(waiting("a", "Closed.", 10)).apply { failDelete = true }
            val controller = controller(notes, outbox)
            runCurrent()
            assertEquals(NoteSend.Sent, controller.sends.value["a"])
            assertEquals(true, controller.waiting.value)
            outbox.failDelete = false
            controller.flush()
            runCurrent()
            assertEquals(NoteSend.Sent, controller.sends.value["a"])
            assertEquals(listOf("Closed."), notes.texts)
            assertEquals(emptyList(), outbox.notes)
            assertEquals(false, controller.waiting.value)
        }

    @Test
    fun waitingNotesWaitForTheSettingsAndGoOnlyToTheUsersOwnServers() =
        runTest {
            val hosts = ArrayList<String>()
            val engine =
                MockEngine { request ->
                    hosts += request.url.host
                    respond(
                        """{"type":"Feature","properties":{"id":4711}}""",
                        HttpStatusCode.OK,
                        headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            val settings = MutableStateFlow<AppSettings?>(null)
            val notes = HttpOsmNotes(HttpClient(engine)) { settings.loadedServers(Service.OsmApi) }
            val controller = controller(notes, FakeOutbox(waiting("a", "Closed.", 10)))
            runCurrent()
            // The settings are still being read: the built-in list may hold a server the user removed.
            assertEquals(emptyList(), hosts)
            assertEquals(NoteSend.Waiting, controller.sends.value["a"])
            settings.value = AppSettings(serverOverrides = mapOf(Service.OsmApi to listOf(ServerEntry("Own", "https://api.own.test"))))
            // The mock engine answers on its own threads.
            controller.sends.first { it["a"] == NoteSend.Sent }
            assertEquals(listOf("api.own.test"), hosts)
        }

    @Test
    fun waitingNotesAreNotMarkedOrSentBeforeTheControllerIsReady() =
        runTest {
            val notes = FakeNotes(NoteAnswer.Sent)
            val outbox = FakeOutbox(waiting("a", "Closed.", 10))
            val ready = CompletableDeferred<Unit>()
            val controller =
                NoteController(notes, backgroundScope, outbox, io = StandardTestDispatcher(testScheduler), clock = at) { ready.await() }
            runCurrent()
            // The page finds its note waiting, but nothing is marked: a stop now leaves it waiting.
            assertEquals(NoteSend.Waiting, controller.sends.value["a"])
            assertEquals(emptyList(), outbox.marks)
            assertEquals(emptyList(), notes.texts)
            ready.complete(Unit)
            runCurrent()
            assertEquals(listOf("Closed."), notes.texts)
            assertEquals(NoteSend.Sent, controller.sends.value["a"])
        }

    @Test
    fun theNotesWaitingListShowsWaitingNotesOldestFirstAndTheOneOnItsWay() =
        runTest {
            val notes = FakeNotes(NoteAnswer.Unreachable, NoteAnswer.Unreachable)
            notes.hold = CompletableDeferred()
            val cutOff = waiting("cut", "Cut off.", 5).copy(onItsWay = true)
            val outbox = FakeOutbox(waiting("b", "Second.", 20), waiting("a", "First.", 10), cutOff)
            val controller = controller(notes, outbox)
            assertNull(controller.notesWaiting.value)
            runCurrent()
            // A note whose try the app stopped in may be on the map: it is not listed as waiting.
            assertEquals(
                NotesWaiting(listOf(waiting("a", "First.", 10), waiting("b", "Second.", 20)), sending = "a"),
                controller.notesWaiting.value,
            )
            notes.hold!!.complete(Unit)
            runCurrent()
            assertEquals(NotesWaiting(listOf(waiting("a", "First.", 10), waiting("b", "Second.", 20))), controller.notesWaiting.value)
            controller.send("c", target, "Third.")
            runCurrent()
            assertEquals(
                listOf("a", "b", "c"),
                controller.notesWaiting.value!!
                    .notes
                    .map { it.id },
            )
        }

    @Test
    fun aWithdrawnNoteLeavesThePhoneAndNeverGoes() =
        runTest {
            val notes = FakeNotes(NoteAnswer.Unreachable, NoteAnswer.Sent)
            val outbox = FakeOutbox(waiting("a", "First.", 10), waiting("b", "Second.", 20))
            val controller = controller(notes, outbox)
            runCurrent()
            controller.withdraw("a")
            runCurrent()
            assertEquals(listOf("b"), outbox.notes.map { it.id })
            assertEquals(
                listOf("b"),
                controller.notesWaiting.value!!
                    .notes
                    .map { it.id },
            )
            assertNull(controller.sends.value["a"])
            controller.flush()
            runCurrent()
            assertEquals(listOf("First.", "Second."), notes.texts)
            assertEquals(emptyList(), outbox.notes)
            assertEquals(false, controller.waiting.value)
        }

    @Test
    fun aNoteOnItsWayCannotBeWithdrawn() =
        runTest {
            val notes = FakeNotes(NoteAnswer.Sent)
            notes.hold = CompletableDeferred()
            val outbox = FakeOutbox(waiting("a", "Closed.", 10))
            val controller = controller(notes, outbox)
            runCurrent()
            controller.withdraw("a")
            runCurrent()
            assertEquals(listOf("a"), outbox.notes.map { it.id })
            assertEquals("a", controller.notesWaiting.value!!.sending)
            notes.hold!!.complete(Unit)
            runCurrent()
            assertEquals(NoteSend.Sent, controller.sends.value["a"])
        }

    @Test
    fun aNoteWithdrawnWhileTheRoundSendsAnEarlierOneIsNotTried() =
        runTest {
            val notes = FakeNotes(NoteAnswer.Sent, NoteAnswer.Sent)
            notes.hold = CompletableDeferred()
            val outbox = FakeOutbox(waiting("a", "First.", 10), waiting("b", "Second.", 20))
            val controller = controller(notes, outbox)
            runCurrent()
            // The round read both notes and is sending "a".
            controller.withdraw("b")
            runCurrent()
            notes.hold!!.complete(Unit)
            runCurrent()
            assertEquals(listOf("First."), notes.texts)
            assertEquals(emptyList(), outbox.marks.filter { it.first == "b" })
            assertEquals(NotesWaiting(emptyList()), controller.notesWaiting.value)
        }

    @Test
    fun aNoteThePhoneCouldNotDeleteStaysListed() =
        runTest {
            val outbox = FakeOutbox(waiting("a", "Closed.", 10))
            val controller = controller(FakeNotes(NoteAnswer.Unreachable), outbox)
            runCurrent()
            outbox.failDelete = true
            controller.withdraw("a")
            runCurrent()
            assertEquals(
                listOf("a"),
                controller.notesWaiting.value!!
                    .notes
                    .map { it.id },
            )
            assertEquals(true, controller.waiting.value)
        }
}
