package to.pubky.maps.android

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import to.pubky.maps.place.LocalRemoteImage
import to.pubky.maps.place.SheetStop
import to.pubky.maps.ui.PubkyMapsTheme

/**
 * The rich place page when every image fails to load (as offline or with Synonym's hosts blocked):
 * the card photo and the photos row are left out, avatars show their initials, and the sheet shrinks
 * to the page. Captured through a Compose test rule that runs frames until idle, because the
 * failures arrive after the first layout and resize the sheet on later frames.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h2300dp")
class PlaceFailedImagesScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun imagesFailTallPhoneLight() {
        val pages = PlaceScreenshotTest()
        compose.setContent {
            PubkyMapsTheme {
                CompositionLocalProvider(LocalRemoteImage provides FailingImage) { pages.Page(pages.rich, SheetStop.Full) }
            }
        }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("screenshots/place-no-photo-sections-tallphone-light.png")
    }
}
