package to.pubky.maps.android

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performCustomAccessibilityActionWithLabel
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureScreenRoboImage
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import to.pubky.maps.config.AppSettings
import to.pubky.maps.config.DataKind
import to.pubky.maps.config.Service
import to.pubky.maps.config.StorageChoice
import to.pubky.maps.settings.AddServerScreen
import to.pubky.maps.settings.DRAG_HANDLE
import to.pubky.maps.settings.ServerListScreen
import to.pubky.maps.settings.StoredOnlineScreen
import to.pubky.maps.ui.PubkyMapsTheme

private const val PHONE = "w412dp-h915dp"
private const val TABLET = "w1280dp-h800dp"

/** Share of pixels that may differ: a text cursor is about 60 of 377,000 pixels on a phone. */
private const val CURSOR_TOLERANCE = 0.001f

/** Finger moves per drag: the list moves a row once the dragged one passes its middle. */
private const val STEPS = 20

/**
 * Settings pages driven the way a user drives them: adding a server (a refused address, an accepted
 * one) and choosing where a kind of data is kept (a dialog over its page). Dialogs are windows of
 * their own, so these capture the whole screen.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalRoborazziApi::class, ExperimentalTestApi::class)
class SettingsInteractionTest {
    @get:Rule
    val compose = createComposeRule()

    private var settings by mutableStateOf(AppSettings())

    private fun show(content: @Composable (onUpdate: ((AppSettings) -> AppSettings) -> Unit) -> Unit) {
        compose.setContent {
            PubkyMapsTheme {
                content { transform -> settings = transform(settings) }
            }
        }
    }

    /** [textCursor]: a text field has focus, and its blinking cursor may differ by a few pixels. */
    private fun capture(
        name: String,
        textCursor: Boolean = false,
    ) {
        val tolerance = if (textCursor) CURSOR_TOLERANCE else 0f
        captureScreenRoboImage(
            "screenshots/$name.png",
            RoborazziOptions(compareOptions = RoborazziOptions.CompareOptions(changeThreshold = tolerance)),
        )
    }

    // Add a server: a refused address shows why, under the field.

    private fun openAddServerAndType(address: String) {
        show { onUpdate ->
            var adding by remember { mutableStateOf(false) }
            if (adding) {
                AddServerScreen(Service.Photon, settings, onUpdate, onDone = { adding = false })
            } else {
                ServerListScreen(Service.Photon, settings, onUpdate, onAddServer = { adding = true }, onBack = {})
            }
        }
        compose.onNodeWithText("Add server").performClick()
        compose.onNode(hasSetTextAction() and hasText("https://")).performTextReplacement(address)
        compose.onNodeWithText("Add").performClick()
    }

    private fun addServerWithBadAddress(name: String) {
        openAddServerAndType("http://photon.example.org")
        compose.onNodeWithText("Use https://. Plain http:// works only for localhost and 127.0.0.1.").assertExists()
        capture(name, textCursor = true)
    }

    @Test
    @Config(qualifiers = "$PHONE-notnight-mdpi")
    fun addServerPhoneLight() = addServerWithBadAddress("add-server-error-phone-light")

    @Test
    @Config(qualifiers = "$PHONE-night-mdpi")
    fun addServerPhoneDark() = addServerWithBadAddress("add-server-error-phone-dark")

    @Test
    @Config(qualifiers = "$TABLET-notnight-mdpi")
    fun addServerTabletLight() = addServerWithBadAddress("add-server-error-tablet-light")

    @Test
    @Config(qualifiers = "$TABLET-night-mdpi")
    fun addServerTabletDark() = addServerWithBadAddress("add-server-error-tablet-dark")

    @Test
    @Config(qualifiers = "$PHONE-notnight-mdpi")
    fun aValidServerIsAddedAtTheEnd() {
        openAddServerAndType("https://photon.example.org")

        assertEquals("https://photon.example.org", settings.servers[Service.Photon].last().url)
        compose.onNodeWithText("Reset to defaults").assertExists()
    }

    // Reorder a server list: drag a row by its handle or after a long press; TalkBack moves it by actions.

    private val light = Service.MapStyleLight
    private val ownTiles = "https://tiles.example.org/styles/bright.json"

    /** Stored changes of the list, to tell one save on drop from a save per row passed. */
    private var saves = 0

    private fun showLightStylesWithOwnTilesLast() {
        settings = (AppSettings().withServerAdded(light, ownTiles, "My tiles") as AppSettings.AddServerResult.Added).settings
        show { onUpdate ->
            ServerListScreen(light, settings, { transform ->
                saves++
                onUpdate(transform)
            }, onAddServer = {}, onBack = {})
        }
    }

    private fun urls() = settings.servers[light].map { it.url }

    private val defaultLight = AppSettings().servers[Service.MapStyleLight].map { it.url }

    /**
     * Presses My tiles' handle (the [handleIndex]th row's) and moves the finger up by [rows] rows in
     * small steps, without lifting it.
     */
    private fun dragOwnTilesUp(
        rows: Int,
        longPressOnRow: Boolean = false,
        handleIndex: Int = 2,
    ) {
        val rowHeight =
            compose
                .onNodeWithText("My tiles")
                .fetchSemanticsNode()
                .size.height
                .toFloat()
        val target =
            if (longPressOnRow) {
                compose.onNodeWithText("My tiles")
            } else {
                compose.onAllNodesWithTag(DRAG_HANDLE, useUnmergedTree = true)[handleIndex]
            }
        target.performTouchInput {
            down(center)
            if (longPressOnRow) advanceEventTime(viewConfiguration.longPressTimeoutMillis + 100)
            repeat(STEPS) {
                moveBy(Offset(0f, -rows * rowHeight * 1.2f / STEPS))
                advanceEventTime(16)
            }
        }
        compose.waitForIdle()
    }

    private fun lift() {
        compose.onNodeWithText("My tiles").performTouchInput { up() }
        compose.waitForIdle()
    }

    @Test
    @Config(qualifiers = "$PHONE-notnight-mdpi")
    fun aServerDraggedByItsHandleIsSavedWhereItIsDropped() {
        showLightStylesWithOwnTilesLast()

        dragOwnTilesUp(rows = 2)
        assertEquals("nothing is saved while the finger is down", 0, saves)
        assertEquals(defaultLight + ownTiles, urls())
        // The rows already make room: My tiles is numbered 1 on screen.
        compose.onNodeWithText("My tiles").assert(hasText("1"))

        lift()
        assertEquals(listOf(ownTiles) + defaultLight, urls())
        assertEquals("one save for the whole drag", 1, saves)
        compose.onNodeWithText("Reset to defaults").assertExists()
    }

    @Test
    @Config(qualifiers = "$PHONE-notnight-mdpi")
    fun theSameRowDraggedAgainIsSavedWhereItIsDroppedTheSecondTime() {
        showLightStylesWithOwnTilesLast()
        dragOwnTilesUp(rows = 1)
        lift()
        assertEquals(listOf(defaultLight[0], ownTiles, defaultLight[1]), urls())

        dragOwnTilesUp(rows = 1, handleIndex = 1)
        lift()

        assertEquals(listOf(ownTiles) + defaultLight, urls())
        compose.onNodeWithText("My tiles").assert(hasText("1"))
    }

    @Test
    @Config(qualifiers = "$PHONE-notnight-mdpi")
    fun aDragCutShortByLeavingThePageIsSavedWhereTheRowWas() {
        settings = (AppSettings().withServerAdded(light, ownTiles, "My tiles") as AppSettings.AddServerResult.Added).settings
        var onPage by mutableStateOf(true)
        show { onUpdate ->
            if (onPage) {
                ServerListScreen(light, settings, { transform ->
                    saves++
                    onUpdate(transform)
                }, onAddServer = {}, onBack = {})
            }
        }

        dragOwnTilesUp(rows = 2)
        assertEquals(0, saves)
        // The page goes away under the finger (Back), so the drag never ends with a lift.
        onPage = false
        compose.waitForIdle()

        assertEquals(listOf(ownTiles) + defaultLight, urls())
        assertEquals("one save for the whole drag", 1, saves)
    }

    @Test
    @Config(qualifiers = "$PHONE-notnight-mdpi")
    fun aDragAfterARemoveSavesTheOrderOfTheListLeft() {
        showLightStylesWithOwnTilesLast()
        compose.onNodeWithText("OpenFreeMap Liberty").performCustomAccessibilityActionWithLabel("Remove")
        assertEquals(listOf(defaultLight[1], ownTiles), urls())

        dragOwnTilesUp(rows = 1, handleIndex = 1)
        lift()

        assertEquals(listOf(ownTiles, defaultLight[1]), urls())
    }

    @Test
    @Config(qualifiers = "$PHONE-notnight-mdpi")
    fun aResetAfterADragShowsTheDefaultOrder() {
        settings = AppSettings()
        show { onUpdate -> ServerListScreen(light, settings, onUpdate, onAddServer = {}, onBack = {}) }
        val rowHeight =
            compose
                .onNodeWithText("VersaTiles Colorful")
                .fetchSemanticsNode()
                .size.height
        compose.onAllNodesWithTag(DRAG_HANDLE, useUnmergedTree = true)[1].performTouchInput {
            down(center)
            repeat(STEPS) {
                moveBy(Offset(0f, -rowHeight * 1.2f / STEPS))
                advanceEventTime(16)
            }
            up()
        }
        compose.waitForIdle()
        assertEquals(defaultLight.reversed(), settings.servers[light].map { it.url })

        compose.onNodeWithText("Reset to defaults").performClick()
        compose.onNodeWithText("Reset").performClick()

        assertEquals(defaultLight, settings.servers[light].map { it.url })
        compose.onNodeWithText("OpenFreeMap Liberty").assert(hasText("1"))
    }

    @Test
    @Config(qualifiers = "$PHONE-notnight-mdpi")
    fun aLongPressOnTheRowDragsItToo() {
        showLightStylesWithOwnTilesLast()

        dragOwnTilesUp(rows = 1, longPressOnRow = true)
        lift()

        assertEquals(listOf(defaultLight[0], ownTiles, defaultLight[1]), urls())
    }

    @Test
    @Config(qualifiers = "$PHONE-notnight-mdpi")
    fun talkBackMovesAServerUpAndDownByItsActions() {
        showLightStylesWithOwnTilesLast()

        compose.onNodeWithText("My tiles").performCustomAccessibilityActionWithLabel("Move up")
        assertEquals(listOf(defaultLight[0], ownTiles, defaultLight[1]), urls())

        compose.onNodeWithText("My tiles").performCustomAccessibilityActionWithLabel("Move down")
        assertEquals(defaultLight + ownTiles, urls())

        // The last row cannot go further down, the first not further up.
        val last = compose.onNodeWithText("My tiles").fetchSemanticsNode()
        assertEquals(listOf("Move up", "Remove"), last.config[SemanticsActions.CustomActions].map { it.label })
        val first = compose.onNodeWithText("OpenFreeMap Liberty").fetchSemanticsNode()
        assertEquals(listOf("Move down", "Remove"), first.config[SemanticsActions.CustomActions].map { it.label })
    }

    @Test
    @Config(qualifiers = "$PHONE-notnight-mdpi")
    fun theOptionsMenuOnlyRemovesTheOrderIsChangedByDragging() {
        showLightStylesWithOwnTilesLast()

        compose.onNodeWithContentDescription("Options for My tiles").performClick()
        compose.onNodeWithText("Move up").assertDoesNotExist()
        compose.onNodeWithText("Move down").assertDoesNotExist()
        compose.onNodeWithText("Remove").performClick()

        assertEquals(defaultLight, urls())
    }

    @Test
    @Config(qualifiers = "$PHONE-notnight-mdpi")
    fun aListOfOneServerHasNoHandle() {
        show { onUpdate -> ServerListScreen(Service.AuthRelay, settings, onUpdate, onAddServer = {}, onBack = {}) }
        assertEquals(1, settings.servers[Service.AuthRelay].size)

        compose.onAllNodesWithTag(DRAG_HANDLE, useUnmergedTree = true).assertCountEquals(0)
        compose.onNodeWithText("Drag a server", substring = true).assertDoesNotExist()
    }

    private fun draggingShot(name: String) {
        showLightStylesWithOwnTilesLast()
        dragOwnTilesUp(rows = 1)
        capture(name)
        lift()
    }

    @Test
    @Config(qualifiers = "$PHONE-notnight-mdpi")
    fun serverDraggedPhoneLight() = draggingShot("server-list-dragging-phone-light")

    @Test
    @Config(qualifiers = "$PHONE-night-mdpi")
    fun serverDraggedPhoneDark() = draggingShot("server-list-dragging-phone-dark")

    @Test
    @Config(qualifiers = "$TABLET-notnight-mdpi")
    fun serverDraggedTabletLight() = draggingShot("server-list-dragging-tablet-light")

    @Test
    @Config(qualifiers = "$TABLET-night-mdpi")
    fun serverDraggedTabletDark() = draggingShot("server-list-dragging-tablet-dark")

    // Where check-ins are kept: the choices with their one-line explanations.

    private fun chooseCheckIns(name: String) {
        show { onUpdate -> StoredOnlineScreen(settings, onUpdate, onBack = {}) }
        compose.onNodeWithText("Check-ins").performClick()
        compose.onNodeWithText("Homeserver, private").assertExists()
        capture(name)
    }

    @Test
    @Config(qualifiers = "$PHONE-notnight-mdpi")
    fun storageChoicePhoneLight() = chooseCheckIns("storage-choice-phone-light")

    @Test
    @Config(qualifiers = "$PHONE-night-mdpi")
    fun storageChoicePhoneDark() = chooseCheckIns("storage-choice-phone-dark")

    @Test
    @Config(qualifiers = "$TABLET-notnight-mdpi")
    fun storageChoiceTabletLight() = chooseCheckIns("storage-choice-tablet-light")

    @Test
    @Config(qualifiers = "$TABLET-night-mdpi")
    fun storageChoiceTabletDark() = chooseCheckIns("storage-choice-tablet-dark")

    @Test
    @Config(qualifiers = "$PHONE-notnight-mdpi")
    fun choosingAnOptionStoresItAndClosesTheDialog() {
        show { onUpdate -> StoredOnlineScreen(settings, onUpdate, onBack = {}) }
        compose.onNodeWithText("Recorded tracks").performClick()
        compose.onNode(hasText("Public") and isSelectable()).performClick()

        assertEquals(StorageChoice.Public, settings.storage(DataKind.RecordedTracks))
        compose.onNodeWithText("Cancel").assertDoesNotExist()
    }
}
