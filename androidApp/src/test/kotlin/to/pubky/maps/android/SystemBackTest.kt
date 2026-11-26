package to.pubky.maps.android

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import to.pubky.maps.ui.SystemBack

/**
 * The system back action on a real activity, as the map's search, place sheet, area picker and the
 * sign-in that a write waits for take it: the last enabled one composed gets it, a disabled or
 * removed one, or one whose page is no longer started, lets it through to the activity.
 */
@RunWith(RobolectricTestRunner::class)
class SystemBackTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun pressBack() {
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    @Test
    fun anEnabledOneTakesBackAndTheActivityStays() {
        var backs = 0
        compose.setContent { SystemBack { backs++ } }

        pressBack()
        pressBack()

        assertEquals(2, backs)
        assertFalse(compose.activity.isFinishing)
    }

    @Test
    fun aDisabledOneLetsBackThroughToTheActivity() {
        var backs = 0
        compose.setContent { SystemBack(enabled = false) { backs++ } }

        pressBack()

        assertEquals(0, backs)
        assertTrue(compose.activity.isFinishing)
    }

    @Test
    fun theLastComposedTakesItAndTheOneBelowOnceItIsOff() {
        var searchOpen by mutableStateOf(true)
        val taken = mutableListOf<String>()
        compose.setContent {
            SystemBack { taken += "place" }
            SystemBack(enabled = searchOpen) {
                taken += "search"
                searchOpen = false
            }
        }

        pressBack()
        pressBack()

        assertEquals(listOf("search", "place"), taken)
        assertFalse(compose.activity.isFinishing)
    }

    @Test
    fun oneThatLeavesTheScreenNoLongerTakesBack() {
        var shown by mutableStateOf(true)
        var backs = 0
        compose.setContent { if (shown) SystemBack { backs++ } }

        pressBack()
        shown = false
        compose.waitForIdle()
        pressBack()

        assertEquals(1, backs)
        assertTrue(compose.activity.isFinishing)
    }

    @Test
    fun theNewestActionRunsAfterARecomposition() {
        var place by mutableStateOf("Bern")
        val closed = mutableListOf<String>()
        compose.setContent {
            val current = place
            SystemBack { closed += current }
        }

        place = "Zürich"
        compose.waitForIdle()
        pressBack()

        assertEquals(listOf("Zürich"), closed)
    }

    @Test
    fun aPageNoLongerStartedLetsBackThrough() {
        // As a NavHost page being left: still composed during the transition, but stopped.
        val page =
            object : LifecycleOwner {
                override val lifecycle = LifecycleRegistry(this)
            }
        compose.runOnUiThread { page.lifecycle.currentState = Lifecycle.State.RESUMED }
        var backs = 0
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides page) { SystemBack { backs++ } }
        }

        pressBack()
        compose.runOnUiThread { page.lifecycle.currentState = Lifecycle.State.CREATED }
        compose.waitForIdle()
        pressBack()

        assertEquals(1, backs)
        assertTrue(compose.activity.isFinishing)
    }
}
