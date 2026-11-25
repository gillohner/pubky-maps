package to.pubky.maps.android

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
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
import to.pubky.maps.settings.ServerListScreen
import to.pubky.maps.settings.StoredOnlineScreen
import to.pubky.maps.ui.PubkyMapsTheme

private const val PHONE = "w412dp-h915dp"
private const val TABLET = "w1280dp-h800dp"
private const val SETTLE_MS = 1_000L

/**
 * The settings dialogs, opened the way a user opens them, on top of their page. Dialogs are windows
 * of their own, so these capture the whole screen. Also checks that the dialogs change the settings.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
@OptIn(ExperimentalRoborazziApi::class)
class DialogScreenshotTest {
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

    private fun capture(name: String) {
        captureScreenRoboImage("screenshots/$name.png")
    }

    // Add a server: a refused address shows why, below the field.

    /**
     * Opens the Add server dialog and types [address]. The test clock is driven by hand here: under
     * Robolectric, the dialog's text field never lets a phone-sized screen report idle on its own.
     */
    private fun openAddServerAndType(address: String) {
        compose.mainClock.autoAdvance = false
        show { onUpdate -> ServerListScreen(Service.Photon, settings, onUpdate, onBack = {}) }
        compose.mainClock.advanceTimeBy(SETTLE_MS)
        compose.onNodeWithText("Add server").performClick()
        compose.mainClock.advanceTimeBy(SETTLE_MS)
        compose.onNode(hasSetTextAction() and hasText("https://")).performTextReplacement(address)
        compose.mainClock.advanceTimeBy(SETTLE_MS)
        compose.onNodeWithText("Add").performClick()
        compose.mainClock.advanceTimeBy(SETTLE_MS)
    }

    private fun addServerWithBadAddress(name: String) {
        openAddServerAndType("http://photon.example.org")
        compose.onNodeWithText("Use https://. Plain http:// works only for localhost and 127.0.0.1.").assertExists()
        capture(name)
    }

    @Test
    @Config(qualifiers = "$PHONE-notnight-mdpi")
    fun addServerPhoneLight() = addServerWithBadAddress("add-server-phone-light")

    @Test
    @Config(qualifiers = "$PHONE-night-mdpi")
    fun addServerPhoneDark() = addServerWithBadAddress("add-server-phone-dark")

    @Test
    @Config(qualifiers = "$TABLET-notnight-mdpi")
    fun addServerTabletLight() = addServerWithBadAddress("add-server-tablet-light")

    @Test
    @Config(qualifiers = "$TABLET-night-mdpi")
    fun addServerTabletDark() = addServerWithBadAddress("add-server-tablet-dark")

    @Test
    @Config(qualifiers = "$PHONE-notnight-mdpi")
    fun aValidServerIsAddedAtTheEnd() {
        openAddServerAndType("https://photon.example.org")

        assertEquals("https://photon.example.org", settings.servers[Service.Photon].last().url)
        compose.onNodeWithText("Reset to defaults").assertExists()
    }

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
