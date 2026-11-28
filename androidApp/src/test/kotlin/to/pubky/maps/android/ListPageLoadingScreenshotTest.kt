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
import to.pubky.maps.ui.PubkyMapsTheme
import to.pubky.maps.write.Kept
import to.pubky.maps.write.ListPage
import to.pubky.maps.write.ListPageState

private const val PHONE_LIGHT = "w412dp-h915dp-notnight-mdpi"
private const val PHONE_DARK = "w412dp-h915dp-night-mdpi"
private const val TABLET_LIGHT = "w1280dp-h800dp-notnight-mdpi"
private const val TABLET_DARK = "w1280dp-h800dp-night-mdpi"

/**
 * Add to collection while the collections are read, for a user with none so far: the bar stands where
 * "You have no collections yet." goes, so the form sits where it does in write-lists-empty. Its bar runs
 * forever, so these are taken with the Compose clock stopped, as SignInScreenshotTest does.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ListPageLoadingScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private fun capture(file: String) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            PubkyMapsTheme {
                ListPage(
                    state = ListPageState(placeName = "Conditorei Schober", lists = emptyList(), loading = true, newKept = Kept.Private),
                    onToggle = {},
                    onNewOpenChange = {},
                    onNewNameChange = {},
                    onNewDescriptionChange = {},
                    onNewKeptChange = {},
                    onCreate = {},
                    onClose = {},
                )
            }
        }
        compose.mainClock.advanceTimeBy(1_000)
        compose.onRoot().captureRoboImage("screenshots/$file.png")
    }

    @Test
    @Config(qualifiers = PHONE_LIGHT)
    fun phoneLight() = capture("write-lists-empty-loading-phone-light")

    @Test
    @Config(qualifiers = PHONE_DARK)
    fun phoneDark() = capture("write-lists-empty-loading-phone-dark")

    @Test
    @Config(qualifiers = TABLET_LIGHT)
    fun tabletLight() = capture("write-lists-empty-loading-tablet-light")

    @Test
    @Config(qualifiers = TABLET_DARK)
    fun tabletDark() = capture("write-lists-empty-loading-tablet-dark")
}
