package to.pubky.maps.android

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import to.pubky.maps.settings.AddServerScreen
import to.pubky.maps.settings.ServerListScreen
import to.pubky.maps.settings.StoredOnlineScreen
import to.pubky.maps.ui.PubkyMapsTheme

private const val PHONE = "w412dp-h915dp"
private const val TABLET = "w1280dp-h800dp"

/**
 * Settings pages driven the way a user drives them: adding a server (a refused address, an accepted
 * one) and choosing where a kind of data is kept (a dialog over its page). Dialogs are windows of
 * their own, so these capture the whole screen.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
@OptIn(ExperimentalRoborazziApi::class)
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

    private fun capture(name: String) {
        captureScreenRoboImage("screenshots/$name.png")
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
        capture(name)
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
