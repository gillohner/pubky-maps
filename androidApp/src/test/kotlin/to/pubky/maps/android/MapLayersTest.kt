package to.pubky.maps.android

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureScreenRoboImage
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import to.pubky.maps.config.ServerDefaults
import to.pubky.maps.map.MapLayersUi
import to.pubky.maps.map.MapScreen
import to.pubky.maps.map.MapSearch
import to.pubky.maps.search.SearchActions
import to.pubky.maps.search.SearchState
import to.pubky.maps.ui.PubkyMapsTheme

/**
 * The map's layers: a Layers button above my location opens a panel at the bottom whose switch turns
 * the street-level photo layer on and off; Close, Back or a tap on the shaded map closes it. While no
 * Panoramax server answers for a layer that is on, the panel says so and offers Try again.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w412dp-h915dp-notnight-mdpi")
@OptIn(ExperimentalRoborazziApi::class)
class MapLayersTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private var streetPhotos by mutableStateOf(false)
    private var unreachable by mutableStateOf(false)
    private val switched = mutableListOf<Boolean>()
    private var retries = 0

    private fun show(layers: Boolean = true) {
        compose.setContent {
            PubkyMapsTheme {
                val dark = isSystemInDarkTheme()
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
                    search = MapSearch(SearchState(), recents = emptyList(), userLocation = null, actions = SearchActions()),
                    layers =
                        if (layers) {
                            MapLayersUi(
                                streetPhotos = streetPhotos,
                                streetPhotosUnreachable = unreachable,
                                onStreetPhotosChange = {
                                    switched += it
                                    streetPhotos = it
                                },
                                onRetryStreetPhotos = { retries++ },
                            )
                        } else {
                            null
                        },
                ) {
                    Box(Modifier.fillMaxSize().background(placeholderMapColor(dark)), contentAlignment = Alignment.Center) {
                        Text("Map", color = if (dark) Color(0xFF8A8D93) else Color(0xFF9A968E))
                    }
                }
            }
        }
    }

    private fun openLayers() {
        compose.onNodeWithContentDescription("Map layers").performClick()
        compose.waitForIdle()
    }

    private val layerSwitch get() = compose.onNode(isToggleable() and hasText("Street-level photos"), useUnmergedTree = false)

    @Test
    fun theLayersButtonOpensThePanelAndItsSwitchTurnsStreetPhotosOnAndOff() {
        show()
        compose.onNodeWithText("Map layers").assertDoesNotExist()
        openLayers()
        compose.onNodeWithText("Map layers").assertIsDisplayed()
        compose.onNodeWithText("Where Panoramax has photos of the streets").assertIsDisplayed()
        layerSwitch.assertIsOff()
        captureScreenRoboImage("screenshots/map-layers-off-phone-light.png")
        layerSwitch.performClick()
        layerSwitch.assertIsOn()
        layerSwitch.performClick()
        layerSwitch.assertIsOff()
        assertEquals(listOf(true, false), switched)
    }

    @Test
    fun talkBackReadsTheLayerAsASwitchAndThePanelByItsTitle() {
        show()
        openLayers()
        layerSwitch.assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch))
        compose.onNode(SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, "Map layers")).assertIsDisplayed()
        compose.onNodeWithContentDescription("Close").assertIsDisplayed()
        // As under a modal sheet, the map's controls behind the shade are out of TalkBack's reach.
        for (control in listOf("Settings", "Map layers", "Show my location")) {
            compose.onNodeWithContentDescription(control).assertDoesNotExist()
        }
        compose.onNodeWithText("Search here").assertDoesNotExist()
        compose.onNodeWithContentDescription("Close").performClick()
        compose.onNodeWithContentDescription("Show my location").assertIsDisplayed()
    }

    @Test
    fun talkBackHearsWhetherALayerIsOnFromTheLayersButton() {
        show()
        compose.onNodeWithContentDescription("Map layers").assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.StateDescription))
        streetPhotos = true
        compose.waitForIdle()
        compose
            .onNodeWithContentDescription("Map layers")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Street-level photos on"))
    }

    @Test
    fun closeBackAndATapOnTheShadedMapCloseThePanel() {
        show()
        openLayers()
        compose.onNodeWithContentDescription("Close").performClick()
        compose.onNodeWithText("Map layers").assertDoesNotExist()
        openLayers()
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        compose.onNodeWithText("Map layers").assertDoesNotExist()
        openLayers()
        compose.onRoot().performTouchInput { click(Offset(centerX, height * 0.3f)) }
        compose.waitForIdle()
        compose.onNodeWithText("Map layers").assertDoesNotExist()
        assertEquals(emptyList<Boolean>(), switched)
    }

    @Test
    fun aLayerNoServerAnswersForSaysSoAndTriesAgain() {
        streetPhotos = true
        unreachable = true
        show()
        openLayers()
        compose.onNodeWithText("Can't reach Panoramax").assertIsDisplayed()
        captureScreenRoboImage("screenshots/map-layers-unreachable-phone-light.png")
        compose.onNodeWithText("Try again").performClick()
        assertEquals(1, retries)
        // Switched off, nothing is missing: no Try again.
        layerSwitch.performClick()
        compose.onNodeWithText("Can't reach Panoramax").assertDoesNotExist()
        compose.onNodeWithText("Try again").assertDoesNotExist()
    }

    @Test
    fun noLayersButtonWhereTheMapHasNoLayers() {
        show(layers = false)
        compose.onNodeWithContentDescription("Map layers").assertDoesNotExist()
        compose.onNodeWithContentDescription("Show my location").assertIsDisplayed()
    }

    @Test
    fun theLayersPhoneLight() {
        streetPhotos = true
        show()
        openLayers()
        captureScreenRoboImage("screenshots/map-layers-phone-light.png")
    }

    @Test
    @Config(qualifiers = "+night")
    fun theLayersPhoneDark() {
        streetPhotos = true
        show()
        openLayers()
        captureScreenRoboImage("screenshots/map-layers-phone-dark.png")
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp-notnight-mdpi")
    fun theLayersTabletLight() {
        streetPhotos = true
        show()
        openLayers()
        captureScreenRoboImage("screenshots/map-layers-tablet-light.png")
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp-night-mdpi")
    fun theLayersTabletDark() {
        streetPhotos = true
        show()
        openLayers()
        captureScreenRoboImage("screenshots/map-layers-tablet-dark.png")
    }

    @Test
    fun theLargestFontKeepsTheSwitchAndItsWords() {
        RuntimeEnvironment.setFontScale(2f)
        streetPhotos = true
        unreachable = true
        show()
        openLayers()
        layerSwitch.assertIsDisplayed()
        compose.onNodeWithText("Try again").assertIsDisplayed()
        captureScreenRoboImage("screenshots/map-layers-unreachable-large-font-phone-light.png")
    }
}
