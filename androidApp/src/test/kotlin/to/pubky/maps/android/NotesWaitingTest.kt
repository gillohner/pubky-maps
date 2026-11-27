package to.pubky.maps.android

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RoborazziComposeOptions
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import com.github.takahirom.roborazzi.fontScale
import com.github.takahirom.roborazzi.size
import com.github.takahirom.roborazzi.uiMode
import kotlinx.datetime.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import to.pubky.maps.AppInfo
import to.pubky.maps.config.AppSettings
import to.pubky.maps.links.GeoPoint
import to.pubky.maps.links.OsmId
import to.pubky.maps.links.OsmType
import to.pubky.maps.osm.NoteTarget
import to.pubky.maps.osm.NotesWaiting
import to.pubky.maps.osm.NotesWaitingScreen
import to.pubky.maps.osm.WaitingNote
import to.pubky.maps.osm.notesWaitingSummary
import to.pubky.maps.settings.SettingsScreen
import to.pubky.maps.ui.PubkyMapsTheme

/** 27 Nov 2026, 14:02 and 14:20 in Zürich (UTC+1). */
private const val FIRST_AT = 1_795_784_520_000L
private const val SECOND_AT = 1_795_785_600_000L

private val zurich = TimeZone.of("Europe/Zurich")

private val schober =
    WaitingNote(
        "a",
        NoteTarget(GeoPoint(47.3722, 8.5446), "Conditorei Schober", OsmId(OsmType.Node, 270803095)),
        "Closed for good since October, the shop is empty.",
        FIRST_AT,
    )
private val pin =
    WaitingNote(
        "b",
        NoteTarget(GeoPoint(47.37689, 8.54169)),
        "A new footpath goes from here down to the river; the map has none. It is signposted and paved, open day and night.",
        SECOND_AT,
    )

/**
 * The Notes waiting page in Settings: the notes to OpenStreetMap that wait on the phone, each deleted
 * only after asking and never while it is on its way, Try now, and the Settings row that opens it
 * (shown only while notes wait). Screenshots on a phone (412x915 dp) and a tablet (1280x800 dp), light
 * and dark.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalRoborazziApi::class)
class NotesWaitingTest {
    @get:Rule
    val compose = createComposeRule()

    private val deleted = mutableListOf<String>()
    private var tries = 0

    private fun show(waiting: NotesWaiting?) {
        compose.setContent {
            PubkyMapsTheme {
                NotesWaitingScreen(waiting, onTryNow = { tries++ }, onDelete = { deleted += it }, onBack = {}, zone = zurich)
            }
        }
    }

    @Test
    @Config(qualifiers = "w412dp-h915dp")
    fun aNoteIsDeletedOnlyAfterTheUserSaysSo() {
        show(NotesWaiting(listOf(schober, pin)))
        compose.onNodeWithContentDescription("Delete the note about Conditorei Schober").performClick()
        compose.onNodeWithText("Your note about Conditorei Schober", substring = true).assertExists()
        captureScreenRoboImage("screenshots/notes-waiting-delete-phone-light.png")
        compose.onNodeWithText("Cancel").performClick()
        assertEquals(emptyList<String>(), deleted)
        compose.onNodeWithContentDescription("Delete the note about Conditorei Schober").performClick()
        compose.onNodeWithText("Delete").performClick()
        assertEquals(listOf("a"), deleted)
    }

    @Test
    fun theQuestionGoesWhenItsNoteGoesOnItsWayAndDoesNotComeBack() {
        var waiting by mutableStateOf(NotesWaiting(listOf(schober, pin)))
        compose.setContent {
            PubkyMapsTheme {
                NotesWaitingScreen(waiting, onTryNow = {}, onDelete = { deleted += it }, onBack = {}, zone = zurich)
            }
        }
        compose.onNodeWithContentDescription("Delete the note about Conditorei Schober").performClick()
        compose.onNodeWithText("Delete this note?").assertExists()
        waiting = waiting.copy(sending = "a")
        compose.waitForIdle()
        compose.onNodeWithText("Delete this note?").assertDoesNotExist()
        // Its try reached no server: it waits again, and nobody asked to delete it.
        waiting = waiting.copy(sending = null)
        compose.waitForIdle()
        compose.onNodeWithText("Delete this note?").assertDoesNotExist()
        assertEquals(emptyList<String>(), deleted)
    }

    @Test
    fun thePageSaysWhileTheNotesAreReadAndWhenNoneWaits() {
        var waiting by mutableStateOf<NotesWaiting?>(null)
        compose.setContent {
            PubkyMapsTheme { NotesWaitingScreen(waiting, onTryNow = {}, onDelete = {}, onBack = {}, zone = zurich) }
        }
        compose.onNodeWithText("Reading the notes on this phone…").assertExists()
        compose.onNodeWithText("Try now").assertDoesNotExist()
        waiting = NotesWaiting(emptyList())
        compose.onNodeWithText("No notes wait on this phone.", substring = true).assertExists()
    }

    @Test
    fun tryNowSendsTheNotesNow() {
        show(NotesWaiting(listOf(schober)))
        compose.onNodeWithText("Try now").performClick()
        assertEquals(1, tries)
        compose.onNodeWithText("Written 27 Nov, 14:02 · waits for a connection").assertExists()
    }

    @Test
    fun aNoteOnItsWayCannotBeDeleted() {
        show(NotesWaiting(listOf(schober, pin), sending = "a"))
        compose.onNodeWithContentDescription("Delete the note about Conditorei Schober").assertDoesNotExist()
        // The row and the button, which waits for the round.
        compose.onAllNodesWithText("Sending…").assertCountEquals(2)
        compose.onNodeWithText("Try now").assertDoesNotExist()
        compose.onNodeWithContentDescription("Delete the note about 47.37689, 8.54169").assertExists()
    }

    @Test
    fun settingsHasAnOfflineGroupForWaitingNotesAlsoWithoutOfflineMaps() {
        var notes by mutableStateOf<String?>(null)
        compose.setContent {
            PubkyMapsTheme { Settings(notes, offlineMaps = null) }
        }
        compose.onNodeWithText("Notes waiting").assertDoesNotExist()
        compose.onNodeWithText("Offline").assertDoesNotExist()
        notes = notesWaitingSummary(NotesWaiting(listOf(schober)))
        compose.onNodeWithText("Offline").assertExists()
        compose.onNodeWithText("Notes waiting").assertExists()
    }

    @Test
    fun settingsListsTheWaitingNotesOnlyWhileSomeWait() {
        assertEquals(null, notesWaitingSummary(null))
        assertEquals(null, notesWaitingSummary(NotesWaiting(emptyList())))
        assertEquals("1 note to OpenStreetMap waits on this phone for a connection.", notesWaitingSummary(NotesWaiting(listOf(schober))))
        var opened = 0
        compose.setContent {
            PubkyMapsTheme { Settings(notesWaitingSummary(NotesWaiting(listOf(schober, pin))), onOpen = { opened++ }) }
        }
        compose.onNodeWithText("2 notes to OpenStreetMap wait on this phone for a connection.").performClick()
        assertEquals(1, opened)
    }

    @Composable
    private fun Settings(
        notesWaiting: String?,
        onOpen: () -> Unit = {},
        offlineMaps: String? = "2 areas on this phone.",
    ) = SettingsScreen(
        appInfo = AppInfo(versionName = "0.1.0-edge.11", versionCode = 1_000_011),
        settings = AppSettings(),
        onOpenServers = {},
        onOpenStoredOnline = {},
        onOpenOpenIn = {},
        onFollowLocalRulesChange = {},
        onOpenLicences = {},
        onBack = {},
        offlineMaps = offlineMaps,
        notesWaiting = notesWaiting,
        onOpenNotesWaiting = onOpen,
    )

    private enum class Device(
        val widthDp: Int,
        val heightDp: Int,
    ) {
        Phone(412, 915),
        Tablet(1280, 800),
    }

    private fun capture(
        name: String,
        device: Device,
        dark: Boolean,
        largeFont: Boolean = false,
        content: @Composable () -> Unit,
    ) {
        val theme = if (dark) "dark" else "light"
        val variant = if (largeFont) "-large-font" else ""
        captureRoboImage(
            filePath = "screenshots/$name$variant-${device.name.lowercase()}-$theme.png",
            roborazziComposeOptions =
                RoborazziComposeOptions {
                    size(widthDp = device.widthDp, heightDp = device.heightDp)
                    uiMode(if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO)
                    if (largeFont) fontScale(2f)
                },
        ) {
            PubkyMapsTheme { content() }
        }
    }

    @Composable
    private fun Page(waiting: NotesWaiting? = NotesWaiting(listOf(schober, pin))) =
        NotesWaitingScreen(waiting, onTryNow = {}, onDelete = {}, onBack = {}, zone = zurich)

    @Test
    fun notesWaitingPhoneLight() = capture("notes-waiting", Device.Phone, dark = false) { Page() }

    @Test
    fun notesWaitingPhoneDark() = capture("notes-waiting", Device.Phone, dark = true) { Page() }

    @Test
    fun notesWaitingTabletLight() = capture("notes-waiting", Device.Tablet, dark = false) { Page() }

    @Test
    fun notesWaitingTabletDark() = capture("notes-waiting", Device.Tablet, dark = true) { Page() }

    @Test
    fun notesWaitingLargeFontPhoneLight() = capture("notes-waiting", Device.Phone, dark = false, largeFont = true) { Page() }

    @Test
    fun notesWaitingSendingPhoneLight() =
        capture("notes-waiting-sending", Device.Phone, dark = false) { Page(NotesWaiting(listOf(schober, pin), sending = "a")) }

    @Test
    fun notesWaitingEmptyPhoneLight() = capture("notes-waiting-empty", Device.Phone, dark = false) { Page(NotesWaiting(emptyList())) }

    @Test
    fun settingsNotesWaitingPhoneLight() =
        capture("settings-notes-waiting", Device.Phone, dark = false) {
            Settings(notesWaitingSummary(NotesWaiting(listOf(schober, pin))))
        }

    @Test
    fun settingsNotesWaitingPhoneDark() =
        capture("settings-notes-waiting", Device.Phone, dark = true) {
            Settings(notesWaitingSummary(NotesWaiting(listOf(schober, pin))))
        }
}
