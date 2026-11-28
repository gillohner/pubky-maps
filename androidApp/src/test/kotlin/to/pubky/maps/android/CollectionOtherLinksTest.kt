package to.pubky.maps.android

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import to.pubky.maps.ui.PubkyMapsTheme
import to.pubky.maps.write.Kept
import to.pubky.maps.write.ListChoice
import to.pubky.maps.write.ListPage
import to.pubky.maps.write.ListPageState
import to.pubky.maps.write.OwnList
import to.pubky.maps.write.SavedScreen
import to.pubky.maps.write.SavedScreenState

/**
 * A collection that also holds another app's posts or profiles (pubky.app shares the folder) shows
 * only its places; the open collection says how many other links it holds, and a collection full
 * with them says so.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CollectionOtherLinksTest {
    @get:Rule
    val compose = createComposeRule()

    private val me = "8pinxsr5ctu1ou5ruzomw4fiz4fe1jybsr6o9dnb8jxcc7bpc3wo"
    private val schober = "https://www.openstreetmap.org/node/270803095"
    private val lindenhof = "https://www.openstreetmap.org/way/4"

    private val mixed =
        OwnList(
            "pubky://$me/pub/social/v1/posts/0034A0000001",
            Kept.Public,
            "Reading and coffee",
            listOf(schober, lindenhof),
            description = "Quiet tables to read at",
            otherItems = 3,
        )
    private val oneOther =
        OwnList("pubky://$me/priv/social/v1/posts/0034A0000002", Kept.Private, "Weekend", listOf(schober), otherItems = 1)
    private val placesOnly = OwnList("pubky://$me/pub/social/v1/posts/0034A0000003", Kept.Public, "Coffee in Zürich", listOf(schober))

    private fun showSaved() {
        compose.setContent {
            PubkyMapsTheme {
                SavedScreen(
                    state =
                        SavedScreenState(
                            places = emptyList(),
                            lists = listOf(mixed, oneOther, placesOnly),
                            names = mapOf(schober to "Conditorei Schober", lindenhof to "Lindenhof"),
                            summaries = mapOf(schober to "Café · Napfgasse 4, 8001 Zürich", lindenhof to "Park · Altstadt, Zürich"),
                            signedIn = true,
                        ),
                    onOpen = { _, _ -> },
                    onBack = {},
                )
            }
        }
    }

    @Test
    fun anOpenCollectionCountsTheLinksThatAreNotPlaces() {
        showSaved()
        // On the Bookmarks page, a collection counts only its places.
        compose.onNodeWithText("2 places").assertExists()
        compose.onAllNodesWithText("other link", substring = true).assertCountEquals(0)

        compose.onNodeWithText("Reading and coffee").performClick()
        compose.onNodeWithText("Lindenhof").assertExists()
        compose.onNodeWithText("3 other links not shown: they are not places.").assertExists()

        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithText("Weekend").performClick()
        compose.onNodeWithText("1 other link not shown: it is not a place.").assertExists()
        compose.onAllNodesWithText("3 other links", substring = true).assertCountEquals(0)

        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithText("Coffee in Zürich").performClick()
        compose.onAllNodesWithText("not shown", substring = true).assertCountEquals(0)
    }

    @Test
    fun aCollectionFullWithOtherLinksSaysWhatFillsIt() {
        val full = mixed.copy(items = (1..97).map { "geo:$it,1" })
        compose.setContent {
            PubkyMapsTheme {
                ListPage(
                    state = ListPageState(placeName = "Conditorei Schober", lists = listOf(ListChoice(full, holds = false))),
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
        compose.onNodeWithText("Full: 97 places and 3 other links").assertExists()
    }

    private fun captureOpen(file: String) {
        showSaved()
        compose.onNodeWithText("Reading and coffee").performClick()
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("screenshots/$file.png")
    }

    @Test
    fun openPhoneLight() = captureOpen("write-saved-collection-open-phone-light")

    @Test
    @Config(qualifiers = "+night")
    fun openPhoneDark() = captureOpen("write-saved-collection-open-phone-dark")

    @Test
    @Config(qualifiers = "w1280dp-h800dp")
    fun openTabletLight() = captureOpen("write-saved-collection-open-tablet-light")

    @Test
    @Config(qualifiers = "w1280dp-h800dp-night")
    fun openTabletDark() = captureOpen("write-saved-collection-open-tablet-dark")
}
