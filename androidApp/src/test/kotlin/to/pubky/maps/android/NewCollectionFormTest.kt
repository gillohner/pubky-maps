package to.pubky.maps.android

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import to.pubky.maps.ui.PubkyMapsTheme
import to.pubky.maps.write.Kept
import to.pubky.maps.write.ListChoice
import to.pubky.maps.write.ListPage
import to.pubky.maps.write.ListPageState
import to.pubky.maps.write.OwnList

/**
 * Add to collection lists the collections first, under a "New collection" row that opens the form for
 * a new one (Google Maps' "New list", pubky.app's New Collection card); the form shows at once only
 * when there is no collection to tick. Cancel and the system back close an opened form.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp")
class NewCollectionFormTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val walks = ListChoice(OwnList("phone:1", Kept.Phone, "Sunday walks", emptyList()), holds = false)
    private val coffee = ListChoice(OwnList("phone:2", Kept.Phone, "Coffee", listOf("geo:1,2")), holds = true)

    private val title = hasText("Title") and hasSetTextAction()
    private val create = hasText("Create collection with this place") and hasClickAction()

    private fun show(
        initial: ListPageState,
        onClose: () -> Unit = {},
    ): () -> ListPageState {
        var state by mutableStateOf(initial)
        compose.setContent {
            PubkyMapsTheme(darkTheme = false) {
                ListPage(
                    state = state,
                    onToggle = {},
                    onNewOpenChange = { state = state.copy(newOpen = it) },
                    onNewNameChange = { state = state.copy(newName = it) },
                    onNewDescriptionChange = {},
                    onNewKeptChange = {},
                    onCreate = {},
                    onClose = onClose,
                )
            }
        }
        return { state }
    }

    @Test
    fun withCollectionsTheFormWaitsBehindTheNewCollectionRowAboveThem() {
        val state = show(ListPageState(placeName = "Conditorei Schober", lists = listOf(walks, coffee)))
        compose.onAllNodes(title).assertCountEquals(0)
        compose.onAllNodes(create).assertCountEquals(0)
        val row = compose.onNode(hasText("New collection") and hasClickAction()).fetchSemanticsNode().boundsInRoot
        val first = compose.onNodeWithText("Sunday walks").fetchSemanticsNode().boundsInRoot
        assertTrue("New collection ($row) is not above the first collection ($first)", row.bottom <= first.top)

        compose.onNode(hasText("New collection") and hasClickAction()).performClick()
        assertTrue(state().newOpen)
        compose.onNode(title).assertIsFocused()
        compose.onNode(create).assertExists()
        // The row gave way to the form; the collections stay under it to tick.
        compose.onAllNodes(hasText("New collection") and hasClickAction()).assertCountEquals(0)
        compose.onNodeWithText("Sunday walks").assertExists()
        compose.onNodeWithText("Coffee").assertExists()
    }

    @Test
    fun cancelClosesTheOpenedForm() {
        val state = show(ListPageState(placeName = "Conditorei Schober", lists = listOf(walks), newOpen = true))
        compose.onNode(hasText("Cancel") and hasClickAction()).performClick()
        assertEquals(false, state().newOpen)
        compose.onAllNodes(title).assertCountEquals(0)
        compose.onNode(hasText("New collection") and hasClickAction()).assertExists()
    }

    @Test
    fun systemBackClosesTheOpenedFormBeforeThePage() {
        var closed = 0
        val state = show(ListPageState(placeName = "Conditorei Schober", lists = listOf(walks), newOpen = true), onClose = { closed++ })
        compose.waitForIdle()
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        assertEquals(false, state().newOpen)
        assertEquals(0, closed)
        assertFalse("the back meant for the form left the page", compose.activity.isFinishing)
    }

    @Test
    fun withNoCollectionTheFormIsOpenAtOnceWithoutCancelOrFocus() {
        show(ListPageState(placeName = "Conditorei Schober", lists = emptyList()))
        compose.onNodeWithText("You have no collections yet.").assertExists()
        compose.onNode(title).assertExists()
        compose.onNode(create).assertExists()
        compose.onAllNodesWithText("Cancel").assertCountEquals(0)
        compose.onAllNodes(hasText("New collection") and hasClickAction()).assertCountEquals(0)
        compose.onAllNodesWithText("Your collections").assertCountEquals(0)
        // The keyboard does not come up over a page the user has not typed on yet.
        compose.onNode(title).assertIsNotFocused()
    }

    @Test
    fun withNoneTheFormStaysWhileTheyAreReadSoThePageDoesNotJump() {
        var state by mutableStateOf(ListPageState(placeName = "Conditorei Schober", lists = emptyList()))
        compose.setContent {
            PubkyMapsTheme(darkTheme = false) {
                ListPage(state, {}, {}, {}, {}, {}, {}, {})
            }
        }
        val before = compose.onNode(create).fetchSemanticsNode().boundsInRoot
        // The page's read starts a moment after it opens and finds none.
        state = state.copy(loading = true)
        compose.onNode(title).assertExists()
        compose.onAllNodes(hasText("New collection") and hasClickAction()).assertCountEquals(0)
        compose.onAllNodesWithText("You have no collections yet.").assertCountEquals(0)
        state = state.copy(loading = false)
        val after = compose.onNode(create).fetchSemanticsNode().boundsInRoot
        assertEquals(before, after)
    }

    @Test
    fun theTitleTakesFocusOnceSoATurnOfThePhoneLeavesTheCursorWhereItIs() {
        val restore = StateRestorationTester(compose)
        var state by mutableStateOf(ListPageState(placeName = "Conditorei Schober", lists = listOf(walks), newOpen = true))
        restore.setContent {
            PubkyMapsTheme(darkTheme = false) {
                ListPage(state, {}, { state = state.copy(newOpen = it) }, {}, {}, {}, {}, {})
            }
        }
        compose.onNode(title).assertIsFocused()
        val description = hasText("Description (optional)") and hasSetTextAction()
        compose.onNode(description).performClick()
        compose.onNode(description).assertIsFocused()
        restore.emulateSavedInstanceStateRestore()
        compose.onNode(title).assertIsNotFocused()
    }
}
