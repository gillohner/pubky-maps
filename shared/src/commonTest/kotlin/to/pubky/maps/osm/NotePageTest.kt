package to.pubky.maps.osm

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** What a note page shows when Android brought it back after stopping the app. */
class NotePageTest {
    @Test
    fun aRestoredPageShowsTheLiveSendElseWhatItSawBeforeTheAppWasStopped() {
        assertEquals(NoteSend.Sending, shownSend(NoteSend.Sending, SeenSend.Sent))
        assertEquals(NoteSend.Sent, shownSend(null, SeenSend.Sent))
        assertEquals(NoteSend.NotSent(NoteAnswer.NoAnswer), shownSend(null, SeenSend.MaybeSent))
        assertEquals(NoteSend.Waiting, shownSend(null, SeenSend.Waiting))
        assertEquals(NoteSend.Sent, shownSend(NoteSend.Sent, SeenSend.Waiting))
        assertNull(shownSend(null, null))
    }

    @Test
    fun aNoteThatMayHaveArrivedIsKeptAsMaybeSentAndOneThatDidNotGoIsNot() {
        assertEquals(SeenSend.MaybeSent, NoteSend.Sending.seen())
        assertEquals(SeenSend.MaybeSent, NoteSend.NotSent(NoteAnswer.NoAnswer).seen())
        assertEquals(SeenSend.Sent, NoteSend.Sent.seen())
        assertEquals(SeenSend.Waiting, NoteSend.Waiting.seen())
        assertNull(NoteSend.NotSent(NoteAnswer.Unreachable).seen())
        assertNull(NoteSend.NotSent(NoteAnswer.Refused("Bad text")).seen())
    }
}
