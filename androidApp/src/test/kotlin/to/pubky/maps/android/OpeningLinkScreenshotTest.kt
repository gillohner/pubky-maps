package to.pubky.maps.android

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import to.pubky.maps.place.OpeningLinkCard
import to.pubky.maps.ui.PubkyMapsTheme

private const val PHONE_LIGHT = "w412dp-h915dp-notnight-mdpi"
private const val PHONE_DARK = "w412dp-h915dp-night-mdpi"
private const val TABLET_LIGHT = "w1280dp-h800dp-notnight-mdpi"
private const val TABLET_DARK = "w1280dp-h800dp-night-mdpi"

/**
 * The map while a link is being opened. The card's spinner runs forever, so Roborazzi's own capture
 * would wait for an idle screen that never comes (minutes per shot, and out of memory on slow
 * machines); these are taken with the Compose clock stopped one second in, as SignInScreenshotTest
 * does. Sizes and themes match LinkedPlaceScreenshotTest.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OpeningLinkScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private fun capture(file: String) {
        compose.mainClock.autoAdvance = false
        compose.setContent { PubkyMapsTheme { PlaceholderMap { OpeningLinkCard() } } }
        compose.mainClock.advanceTimeBy(1_000)
        compose.onRoot().captureRoboImage("screenshots/$file.png")
    }

    @Test
    @Config(qualifiers = PHONE_LIGHT)
    fun openingLinkPhoneLight() = capture("map-opening-link-phone-light")

    @Test
    @Config(qualifiers = PHONE_DARK)
    fun openingLinkPhoneDark() = capture("map-opening-link-phone-dark")

    @Test
    @Config(qualifiers = TABLET_LIGHT)
    fun openingLinkTabletLight() = capture("map-opening-link-tablet-light")

    @Test
    @Config(qualifiers = TABLET_DARK)
    fun openingLinkTabletDark() = capture("map-opening-link-tablet-dark")
}
