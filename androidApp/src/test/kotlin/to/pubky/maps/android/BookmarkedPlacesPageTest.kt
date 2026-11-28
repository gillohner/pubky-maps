package to.pubky.maps.android

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import to.pubky.maps.links.GeoPoint
import to.pubky.maps.ui.PubkyMapsTheme
import to.pubky.maps.write.Kept
import to.pubky.maps.write.OwnList
import to.pubky.maps.write.SavedRow
import to.pubky.maps.write.SavedScreen
import to.pubky.maps.write.SavedScreenState

/**
 * The bookmarks are one row of the Bookmarks page, above the collections, as pubky.app's collections
 * page has its Bookmarks card first and Google Maps, CoMaps and OsmAnd keep starred places, bookmarks
 * and favourites as a list among the lists; the row opens them as their own page, and back returns.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BookmarkedPlacesPageTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val schober = "https://www.openstreetmap.org/node/270803095"
    private val coffee =
        OwnList(
            "phone:1",
            Kept.Phone,
            "Coffee in Zürich",
            listOf(schober),
            names = mapOf(schober to "Conditorei Schober"),
            description = "Quiet cafés with a seat by the window.",
        )
    private val walks = OwnList("phone:2", Kept.Phone, "Sunday walks", emptyList())

    private val places =
        listOf(
            SavedRow(schober, "Conditorei Schober", Kept.Private, summary = "Café · Napfgasse 4, 8001 Zürich"),
            SavedRow("https://maps.app.goo.gl/x1", "Lindenhof", Kept.Private, pending = true, summary = "Park · Altstadt, Zürich"),
            SavedRow("geo:47.36667,8.55", null, Kept.Phone),
            SavedRow(
                "https://www.openstreetmap.org/node/1",
                "Coop",
                Kept.Private,
                point = GeoPoint(47.37586, 8.54253),
                summary = "Supermarket · Bahnhofbrücke 1, 8001 Zürich",
            ),
            SavedRow("https://www.openstreetmap.org/node/2", "Coop", Kept.Phone, point = GeoPoint(47.36722, 8.54493)),
        )

    private fun show(
        places: List<SavedRow> = this.places,
        onBack: () -> Unit = {},
    ) {
        compose.setContent {
            PubkyMapsTheme {
                SavedScreen(
                    SavedScreenState(places = places, lists = listOf(coffee, walks), signedIn = true),
                    onOpen = { _, _ -> },
                    onBack = onBack,
                )
            }
        }
    }

    private fun pressBack() {
        compose.waitForIdle()
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    @Test
    fun manyBookmarksLeaveTheCollectionsOnScreen() {
        show(places = (1..30).map { SavedRow("https://www.openstreetmap.org/node/$it", "Place $it", Kept.Private) })
        compose.onNode(hasText("Bookmarked places") and hasClickAction()).assertIsDisplayed()
        compose.onNodeWithText("30 places").assertIsDisplayed()
        // Without scrolling: the bookmarks no longer stand between the top and the collections.
        compose.onNodeWithText("Coffee in Zürich").assertIsDisplayed()
        compose.onNodeWithText("Sunday walks").assertIsDisplayed()
        compose.onAllNodesWithText("Place 1").assertCountEquals(0)
    }

    @Test
    fun theRowOpensTheBookmarksAsTheirOwnPageAndBackReturns() {
        var backs = 0
        show(onBack = { backs++ })
        compose.onNodeWithText("5 places · 1 not sent yet").assertExists()
        compose.onAllNodesWithText("Conditorei Schober").assertCountEquals(0)

        compose.onNodeWithText("Bookmarked places").performClick()
        // Its page: the title once (the top bar, a heading), every bookmark as before, no collection.
        compose.onAllNodesWithText("Bookmarked places").assertCountEquals(1)
        compose.onNode(hasText("Bookmarked places") and isHeading()).assertExists()
        compose.onNodeWithText("Conditorei Schober").assertExists()
        compose.onNodeWithText("47.36667, 8.55").assertExists()
        compose.onNodeWithText("Not sent yet").assertExists()
        compose.onNodeWithText("47.36722, 8.54493").assertExists()
        compose.onAllNodesWithText("Coffee in Zürich").assertCountEquals(0)
        compose.onAllNodesWithText("Your collections").assertCountEquals(0)

        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithText("Coffee in Zürich").assertExists()
        assertEquals("the arrow on the bookmarks' page goes back to Bookmarks, not out of it", 0, backs)

        compose.onNodeWithText("Bookmarked places").performClick()
        pressBack()
        compose.onNodeWithText("Coffee in Zürich").assertExists()
        assertFalse(compose.activity.isFinishing)
        pressBack()
        assertTrue(compose.activity.isFinishing)
    }

    @Test
    fun bookmarksRemovedWhileTheirPageIsOpenLeaveItSayingHowToAddOne() {
        var shown by mutableStateOf(places)
        compose.setContent {
            PubkyMapsTheme {
                SavedScreen(SavedScreenState(places = shown, lists = listOf(coffee)), onOpen = { _, _ -> }, onBack = {})
            }
        }
        compose.onNodeWithText("Bookmarked places").performClick()
        // Removed on another device; the next read of the homeserver drops them.
        shown = emptyList()
        compose.waitForIdle()
        compose.onNode(hasText("Bookmarked places") and isHeading()).assertExists()
        compose.onNodeWithText("Places you bookmark show here. Tap Bookmark on a place's page.").assertExists()
        compose.onAllNodesWithText("Conditorei Schober").assertCountEquals(0)
    }

    @Test
    fun withoutBookmarksTheRowSaysHowToMakeOneAndOpensNothing() {
        show(places = emptyList())
        compose.onNodeWithText("None yet. Tap Bookmark on a place's page.").assertExists()
        // One TalkBack stop, the row's name and its hint together, that opens nothing.
        compose.onNode(hasText("Bookmarked places") and hasText("None yet.", substring = true)).assertHasNoClickAction()
        compose.onAllNodesWithText("0 places").assertCountEquals(1) // Sunday walks', not the bookmarks'
    }

    private fun captureOpen(file: String) {
        show()
        compose.onNodeWithText("Bookmarked places").performClick()
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("screenshots/$file.png")
    }

    @Test
    fun openPhoneLight() = captureOpen("write-saved-bookmarks-open-phone-light")

    @Test
    @Config(qualifiers = "+night")
    fun openPhoneDark() = captureOpen("write-saved-bookmarks-open-phone-dark")

    @Test
    @Config(qualifiers = "w1280dp-h800dp")
    fun openTabletLight() = captureOpen("write-saved-bookmarks-open-tablet-light")

    @Test
    @Config(qualifiers = "w1280dp-h800dp-night")
    fun openTabletDark() = captureOpen("write-saved-bookmarks-open-tablet-dark")
}
