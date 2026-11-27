package to.pubky.maps.osm

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import to.pubky.maps.links.GeoPoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

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
}
