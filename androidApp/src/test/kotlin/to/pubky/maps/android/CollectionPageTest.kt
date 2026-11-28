package to.pubky.maps.android

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
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
import to.pubky.maps.write.OwnList
import to.pubky.maps.write.SavedRow
import to.pubky.maps.write.SavedScreen
import to.pubky.maps.write.SavedScreenState

/**
 * A collection on the Bookmarks page opens as its own page, as Google Maps, CoMaps and OsmAnd open a
 * list: its name in the top bar, its places without the bookmarks or other collections; the back arrow and the
 * system back return to the Bookmarks page, and the next system back leaves it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp")
class CollectionPageTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val schober = "https://www.openstreetmap.org/node/270803095"
    private val lindenhof = "https://www.openstreetmap.org/way/4"
    private val coffee =
        OwnList("phone:1", Kept.Phone, "Coffee in Zürich", listOf(schober), description = "Where to sit with a book")
    private val walks = OwnList("phone:2", Kept.Phone, "Sunday walks", emptyList())

    private fun state(lists: List<OwnList> = listOf(coffee, walks)) =
        SavedScreenState(
            places = listOf(SavedRow(lindenhof, "Lindenhof", Kept.Private)),
            lists = lists,
            names = mapOf(schober to "Conditorei Schober"),
            signedIn = true,
        )

    private fun pressBack() {
        // The page takes back once it has composed with the collection open.
        compose.waitForIdle()
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    @Test
    fun aCollectionOpensAsItsOwnPageAndBackReturnsToTheBookmarks() {
        var backs = 0
        compose.setContent {
            PubkyMapsTheme(darkTheme = false) {
                SavedScreen(state(), onOpen = { _, _ -> }, onBack = { backs++ })
            }
        }
        compose.onNode(hasText("Coffee in Zürich") and hasClickAction()).performClick()
        // Its page: the name once (the top bar, a heading), its place, none of the bookmarks or other collections.
        compose.onAllNodesWithText("Coffee in Zürich").assertCountEquals(1)
        compose.onNode(hasText("Coffee in Zürich") and isHeading()).assertExists()
        compose.onNodeWithText("Where to sit with a book").assertExists()
        compose.onNodeWithText("Conditorei Schober").assertExists()
        compose.onAllNodesWithText("Lindenhof").assertCountEquals(0)
        compose.onAllNodesWithText("Sunday walks").assertCountEquals(0)
        compose.onAllNodesWithText("Your collections").assertCountEquals(0)

        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithText("Bookmarks").assertExists()
        compose.onNodeWithText("Bookmarked places").assertExists()
        assertEquals("the arrow on a collection's page goes back to Bookmarks, not out of it", 0, backs)

        compose.onNode(hasText("Coffee in Zürich") and hasClickAction()).performClick()
        pressBack()
        compose.onNodeWithText("Bookmarked places").assertExists()
        assertFalse(compose.activity.isFinishing)

        // On the Bookmarks page, back is no longer the page's: it goes on to the app.
        pressBack()
        assertTrue(compose.activity.isFinishing)
    }

    @Test
    fun aCollectionWithoutPlacesSaysHowToAddOne() {
        compose.setContent {
            PubkyMapsTheme(darkTheme = false) {
                SavedScreen(state(), onOpen = { _, _ -> }, onBack = {})
            }
        }
        compose.onNodeWithText("Sunday walks").performClick()
        compose.onNodeWithText("0 places").assertExists()
        compose.onNodeWithText("No places in it yet. Tap Add to collection on a place's page.").assertExists()
    }

    @Test
    fun aCollectionHoldingALinkTwiceOpens() {
        val twice = coffee.copy(items = listOf(schober, schober))
        compose.setContent {
            PubkyMapsTheme(darkTheme = false) {
                SavedScreen(state(listOf(twice)), onOpen = { _, _ -> }, onBack = {})
            }
        }
        compose.onNode(hasText("Coffee in Zürich") and hasClickAction()).performClick()
        compose.onAllNodesWithText("Conditorei Schober").assertCountEquals(2)
    }

    @Test
    fun aCollectionRemovedWhileItsPageIsOpenLeavesItForTheBookmarks() {
        var lists by mutableStateOf(listOf(coffee, walks))
        compose.setContent {
            PubkyMapsTheme(darkTheme = false) {
                SavedScreen(state(lists), onOpen = { _, _ -> }, onBack = {})
            }
        }
        compose.onNode(hasText("Coffee in Zürich") and hasClickAction()).performClick()
        compose.onAllNodesWithText("Lindenhof").assertCountEquals(0)

        // Removed on another device; the next read of the homeserver drops it.
        lists = listOf(walks)
        compose.waitForIdle()
        compose.onNodeWithText("Bookmarks").assertExists()
        compose.onNodeWithText("Bookmarked places").assertExists()
        compose.onAllNodesWithText("Coffee in Zürich").assertCountEquals(0)
        compose.onAllNodesWithText("Conditorei Schober").assertCountEquals(0)
        // The page is forgotten: a later read that has the collection again does not open it by itself.
        lists = listOf(coffee, walks)
        compose.waitForIdle()
        compose.onNodeWithText("Bookmarked places").assertExists()
        compose.onAllNodesWithText("Conditorei Schober").assertCountEquals(0)
    }

    @Test
    fun backFromACollectionFindsTheBookmarksPageWhereItWas() {
        val many = (1..30).map { OwnList("phone:$it", Kept.Phone, "Collection $it", emptyList()) }
        compose.setContent {
            PubkyMapsTheme(darkTheme = false) {
                SavedScreen(state(many + coffee), onOpen = { _, _ -> }, onBack = {})
            }
        }
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Coffee in Zürich") and hasClickAction())
        compose.onNode(hasText("Coffee in Zürich") and hasClickAction()).performClick()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNode(hasText("Coffee in Zürich") and hasClickAction()).assertIsDisplayed()
        compose.onAllNodesWithText("Collection 1").assertCountEquals(0)
    }
}
