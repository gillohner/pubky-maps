package to.pubky.maps.android

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureScreenRoboImage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import to.pubky.maps.config.ServerDefaults
import to.pubky.maps.map.MapScreen
import to.pubky.maps.ui.PubkyMapsTheme
import to.pubky.maps.update.AvailableUpdate
import to.pubky.maps.update.UpdatePrompt
import to.pubky.maps.update.makeRoom

/**
 * An update the automatic check found is offered once on the map, in a snackbar with Install that
 * stays until the user acts: only while nothing is open on the map, taken away when a page or another
 * message comes and back after, and reported as shown once the user tapped Install or closed it.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w412dp-h915dp-notnight-mdpi")
@OptIn(ExperimentalRoborazziApi::class)
class UpdatePromptTest {
    @get:Rule
    val compose = createComposeRule()

    private val update = AvailableUpdate(versionCode = 1_000_012, versionName = "0.1.0-edge.12", sizeBytes = 14_236_512)
    private val message = "Update ready: Pubky Maps 0.1.0-edge.12"
    private var installs = 0
    private var shown = 0
    private var prompt: AvailableUpdate? by mutableStateOf(update)
    private var mapFree by mutableStateOf(true)
    private var otherMessage: String? = null
    private val locationOff = "Your location is not available. Is location turned on?"
    private lateinit var host: SnackbarHostState
    private lateinit var scope: CoroutineScope

    private fun show() {
        compose.setContent {
            PubkyMapsTheme {
                host = remember { SnackbarHostState() }
                scope = rememberCoroutineScope()
                LaunchedEffect(Unit) { otherMessage?.let { host.showSnackbar(it) } }
                UpdatePrompt(
                    prompt = prompt,
                    mapFree = mapFree,
                    snackbars = host,
                    onInstall = { installs++ },
                    onShown = {
                        shown++
                        prompt = null
                    },
                )
                MapScreen(
                    attribution =
                        ServerDefaults.lists
                            .mapStyles(darkTheme = false)
                            .first()
                            .attribution,
                    mapUnavailable = false,
                    onRetryMap = {},
                    onMyLocation = {},
                    onOpenSettings = {},
                    updateReady = true,
                    snackbarHostState = host,
                ) { Box(Modifier.fillMaxSize()) }
            }
        }
        quietMoment()
    }

    /** The prompt shows after a quarter of a second without another message. */
    private fun quietMoment() {
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(300)
    }

    @Test
    fun theMapOffersTheUpdateAndInstallStartsIt() {
        show()
        compose.onNodeWithText(message).assertIsDisplayed()
        captureScreenRoboImage("screenshots/map-update-prompt-phone-light.png")
        compose.onNodeWithText("Install").performClick()
        compose.waitForIdle()
        assertEquals(1, installs)
        assertEquals(1, shown)
        compose.onNodeWithText(message).assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = "+night")
    fun theMapOffersTheUpdatePhoneDark() {
        show()
        compose.onNodeWithText(message).assertIsDisplayed()
        captureScreenRoboImage("screenshots/map-update-prompt-phone-dark.png")
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp-notnight-mdpi")
    fun theMapOffersTheUpdateTabletLight() {
        show()
        compose.onNodeWithText(message).assertIsDisplayed()
        captureScreenRoboImage("screenshots/map-update-prompt-tablet-light.png")
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp-night-mdpi")
    fun theMapOffersTheUpdateTabletDark() {
        show()
        compose.onNodeWithText(message).assertIsDisplayed()
        captureScreenRoboImage("screenshots/map-update-prompt-tablet-dark.png")
    }

    @Test
    fun theLargestFontKeepsInstallAndClose() {
        RuntimeEnvironment.setFontScale(2f)
        show()
        compose.onNodeWithText(message).assertIsDisplayed()
        compose.onNodeWithText("Install").assertIsDisplayed()
        compose.onNodeWithContentDescription("Dismiss").assertIsDisplayed()
        captureScreenRoboImage("screenshots/map-update-prompt-large-font-phone-light.png")
    }

    @Test
    fun closingItInstallsNothingAndIsNotOfferedAgain() {
        show()
        compose.onNodeWithContentDescription("Dismiss").performClick()
        compose.waitForIdle()
        assertEquals(0, installs)
        assertEquals(1, shown)
        compose.onNodeWithText(message).assertDoesNotExist()
    }

    @Test
    fun itStaysUntilTheUserActs() {
        show()
        compose.mainClock.advanceTimeBy(60_000)
        compose.onNodeWithText(message).assertIsDisplayed()
        assertEquals(0, shown)
    }

    @Test
    fun aMessageThatTakesItsPlaceIsShownAndThenItComesBack() {
        show()
        compose.onNodeWithText(message).assertIsDisplayed()
        compose.runOnIdle {
            scope.launch {
                host.makeRoom()
                host.showSnackbar(locationOff)
            }
        }
        compose.onNodeWithText(locationOff).assertIsDisplayed()
        compose.onNodeWithText(message).assertDoesNotExist()
        assertEquals("replaced is not seen", 0, shown)
        compose.mainClock.advanceTimeBy(5_000)
        compose.onNodeWithText(message).assertIsDisplayed()
    }

    @Test
    fun itDoesNotQueueAheadOfAMessageThatReplacesTheOneOnScreen() {
        otherMessage = "A link could not be opened."
        show()
        compose.onNodeWithText(otherMessage!!).assertIsDisplayed()
        compose.runOnIdle {
            scope.launch {
                host.makeRoom()
                host.showSnackbar(locationOff)
            }
        }
        compose.onNodeWithText(locationOff).assertIsDisplayed()
        compose.onNodeWithText(message).assertDoesNotExist()
        compose.mainClock.advanceTimeBy(5_000)
        compose.onNodeWithText(message).assertIsDisplayed()
    }

    @Test
    fun nothingIsOfferedOverAnOpenPage() {
        mapFree = false
        show()
        compose.onNodeWithText(message).assertDoesNotExist()
        mapFree = true
        quietMoment()
        compose.onNodeWithText(message).assertIsDisplayed()
        assertEquals(0, shown)
    }

    @Test
    fun aPageThatOpensTakesItAwayUntilTheMapIsFreeAgain() {
        show()
        compose.onNodeWithText(message).assertIsDisplayed()
        mapFree = false
        compose.waitForIdle()
        compose.onNodeWithText(message).assertDoesNotExist()
        assertEquals("taken away is not shown", 0, shown)
        mapFree = true
        quietMoment()
        compose.onNodeWithText(message).assertIsDisplayed()
    }

    @Test
    fun itWaitsBehindAMessageAlreadyShowing() {
        otherMessage = locationOff
        show()
        compose.onNodeWithText(otherMessage!!).assertIsDisplayed()
        compose.onNodeWithText(message).assertDoesNotExist()
        compose.mainClock.advanceTimeBy(5_000)
        compose.onNodeWithText(message).assertIsDisplayed()
    }

    @Test
    fun noPromptNoSnackbar() {
        prompt = null
        show()
        compose.onNodeWithText("Update ready", substring = true).assertDoesNotExist()
    }
}
