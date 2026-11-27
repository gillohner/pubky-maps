package to.pubky.maps.android

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import to.pubky.maps.links.GeoPoint
import to.pubky.maps.links.OsmId
import to.pubky.maps.links.OsmType
import to.pubky.maps.osm.NoteAnswer
import to.pubky.maps.osm.NoteController
import to.pubky.maps.osm.NoteRoute
import to.pubky.maps.osm.NoteTarget
import to.pubky.maps.osm.OsmNotes
import to.pubky.maps.place.PlacePageActions
import to.pubky.maps.place.PlacePageContext
import to.pubky.maps.place.PlacePageState
import to.pubky.maps.place.PlacePanel
import to.pubky.maps.place.PlaceRef
import to.pubky.maps.place.PlaceSource
import to.pubky.maps.ui.PubkyMapsTheme

/** The place page opens a note about the place; the note page sends what the user wrote and says how it went. */
@RunWith(RobolectricTestRunner::class)
class NotePageTest {
    @get:Rule
    val compose = createComposeRule()

    private val schober = NoteTarget(GeoPoint(47.3722, 8.5446), "Conditorei Schober", OsmId(OsmType.Node, 270803095))

    /** Answers every note with [answer], once [hold] lets it through when one is set. */
    private class FakeNotes(
        private val answer: NoteAnswer,
        private val hold: CompletableDeferred<Unit>? = null,
    ) : OsmNotes {
        val sent = ArrayList<Pair<NoteTarget, String>>()

        override suspend fun create(
            target: NoteTarget,
            text: String,
        ): NoteAnswer {
            sent += target to text
            hold?.await()
            return answer
        }
    }

    @Test
    fun aWrittenNoteIsSentAndThePageThanksForIt() {
        val notes = FakeNotes(NoteAnswer.Sent)
        var closed = 0
        compose.setContent {
            PubkyMapsTheme { NoteRoute(NoteController(notes, CoroutineScope(Dispatchers.Unconfined)), schober, onClose = { closed++ }) }
        }
        compose.onNodeWithText("Send").assertIsNotEnabled()
        compose.onNodeWithText("What is wrong or missing?").performTextInput("Closed for good.")
        compose.onNodeWithText("Send").assertIsEnabled().performClick()
        compose.onNodeWithText("Thank you, your note is on the map").assertExists()
        assertEquals(listOf(schober to "Closed for good."), notes.sent)
        compose.onNodeWithText("Done").performClick()
        assertEquals(1, closed)
    }

    @Test
    fun aNoteNoServerTookKeepsItsTextAndSaysSo() {
        val notes = FakeNotes(NoteAnswer.Unreachable)
        compose.setContent {
            PubkyMapsTheme { NoteRoute(NoteController(notes, CoroutineScope(Dispatchers.Unconfined)), schober, onClose = {}) }
        }
        compose.onNodeWithText("What is wrong or missing?").performTextInput("Closed for good.")
        compose.onNodeWithText("Send").performClick()
        compose.onNodeWithText("Not sent: no server could be reached. Check your connection and send it again.").assertExists()
        compose.onNodeWithText("Closed for good.").assertExists()
        compose.onNodeWithText("Send").assertIsEnabled()
    }

    @Test
    fun aSentNotePageRestoredAfterTheAppWasStoppedStillThanksAndOffersNoSecondNote() {
        val notes = FakeNotes(NoteAnswer.Sent)
        // Android stopping the app takes the controller with it: the restored page meets a new one.
        var controller = NoteController(notes, CoroutineScope(Dispatchers.Unconfined))
        val restoration = StateRestorationTester(compose)
        restoration.setContent { PubkyMapsTheme { NoteRoute(controller, schober, onClose = {}) } }
        compose.onNodeWithText("What is wrong or missing?").performTextInput("Closed for good.")
        compose.onNodeWithText("Send").performClick()
        compose.onNodeWithText("Thank you, your note is on the map").assertExists()
        controller = NoteController(notes, CoroutineScope(Dispatchers.Unconfined))
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("Thank you, your note is on the map").assertExists()
        compose.onNodeWithText("Send").assertDoesNotExist()
        assertEquals(1, notes.sent.size)
    }

    @Test
    fun aNotePageRestoredWhileItsNoteWasOnItsWaySaysItMayBeOnTheMapAlready() {
        val notes = FakeNotes(NoteAnswer.Sent, hold = CompletableDeferred())
        var controller = NoteController(notes, CoroutineScope(Dispatchers.Unconfined))
        val restoration = StateRestorationTester(compose)
        restoration.setContent { PubkyMapsTheme { NoteRoute(controller, schober, onClose = {}) } }
        compose.onNodeWithText("What is wrong or missing?").performTextInput("Closed for good.")
        compose.onNodeWithText("Send").performClick()
        compose.onNodeWithText("Sending").assertIsNotEnabled()
        controller = NoteController(notes, CoroutineScope(Dispatchers.Unconfined))
        restoration.emulateSavedInstanceStateRestore()
        compose
            .onNodeWithText("No answer in time: the note may be on the map already. Send it again only if it is not there tomorrow.")
            .assertExists()
        compose.onNodeWithText("Closed for good.").assertExists()
    }

    @Test
    fun aNotePageRestoredAfterNoAnswerStillSaysTheNoteMayBeOnTheMap() {
        val notes = FakeNotes(NoteAnswer.NoAnswer)
        var controller = NoteController(notes, CoroutineScope(Dispatchers.Unconfined))
        val restoration = StateRestorationTester(compose)
        restoration.setContent { PubkyMapsTheme { NoteRoute(controller, schober, onClose = {}) } }
        compose.onNodeWithText("What is wrong or missing?").performTextInput("Closed for good.")
        compose.onNodeWithText("Send").performClick()
        val noAnswer = "No answer in time: the note may be on the map already. Send it again only if it is not there tomorrow."
        compose.onNodeWithText(noAnswer).assertExists()
        controller = NoteController(notes, CoroutineScope(Dispatchers.Unconfined))
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText(noAnswer).assertExists()
    }

    private val page =
        PlacePageState(
            place =
                PlaceRef(
                    "https://www.openstreetmap.org/node/270803095",
                    "Conditorei Schober",
                    GeoPoint(47.3722, 8.5446),
                    OsmId(OsmType.Node, 270803095),
                    PlaceSource.Map,
                ),
            loading = emptySet(),
        )

    private val context =
        PlacePageContext(
            now = LocalDateTime(LocalDate.parse("2026-11-25"), LocalTime(12, 0)),
            nowMillis = 1_795_608_000_000,
            timeZone = TimeZone.UTC,
            language = "en",
            distance = null,
        )

    private fun actions(onReportProblem: (() -> Unit)?) =
        PlacePageActions(
            onClose = {},
            onShare = null,
            openIn = emptyList(),
            onOpenIn = {},
            onOpenLink = {},
            onLoadMorePosts = {},
            onReportProblem = onReportProblem,
        )

    @Test
    fun thePlacePageEndsWithReportAMapProblem() {
        var opened = 0
        compose.setContent { PubkyMapsTheme { PlacePanel(page, context, actions { opened++ }) } }
        compose.onNodeWithText("Report a map problem").performScrollTo().performClick()
        assertEquals(1, opened)
    }

    @Test
    fun aPlacePageWithoutNotesHasNoReportButton() {
        compose.setContent { PubkyMapsTheme { PlacePanel(page, context, actions(null)) } }
        compose.onNodeWithText("Report a map problem").assertDoesNotExist()
    }
}
