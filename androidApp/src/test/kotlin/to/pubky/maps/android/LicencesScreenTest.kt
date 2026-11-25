package to.pubky.maps.android

import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToIndex
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import to.pubky.maps.settings.LicencesScreen
import to.pubky.maps.settings.parseLicences
import to.pubky.maps.ui.PubkyMapsTheme

/** The licence screen with the real list this build ships, scrolled from the first entry to the last. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w412dp-h915dp-notnight-mdpi")
class LicencesScreenTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun theWholeRealListShowsWithoutCrashing() {
        val libraries = parseLicences(readLicenceList(RuntimeEnvironment.getApplication().resources))
        compose.setContent { PubkyMapsTheme { LicencesScreen(libraries = libraries, onBack = {}) } }
        compose.waitForIdle()

        val list = compose.onNode(hasScrollToIndexAction())
        // Every row is composed once on the way: rows with equal keys would throw here.
        for (index in libraries.libraries.indices step 4) list.performScrollToIndex(index)
        list.performScrollToIndex(libraries.libraries.lastIndex)

        val last = libraries.libraries.last()
        compose.onNodeWithText(last.name, useUnmergedTree = true).assertExists()
    }
}
