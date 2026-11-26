package to.pubky.maps.android

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertTrue
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
import to.pubky.maps.write.SavedRow
import to.pubky.maps.write.SavedScreen
import to.pubky.maps.write.SavedScreenState

/**
 * The Saved page names places the way the user knows them (a named place shows no OpenStreetMap id
 * or link), and the list page shows where a new list goes beside the button that makes it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SavedAndListsTest {
    @get:Rule
    val compose = createComposeRule()

    private val osm = "https://www.openstreetmap.org/node/270803095"
    private val lindenhof = "https://maps.app.goo.gl/x1"

    @Test
    fun aNamedPlaceShowsItsNameWithoutItsLink() {
        compose.setContent {
            PubkyMapsTheme(darkTheme = false) {
                SavedScreen(
                    state =
                        SavedScreenState(
                            places =
                                listOf(
                                    SavedRow(osm, "Conditorei Schober", Kept.Private),
                                    SavedRow(lindenhof, "Lindenhof", Kept.Private, pending = true),
                                    SavedRow("geo:47.36667,8.55", null, Kept.Phone),
                                ),
                            lists =
                                listOf(
                                    OwnList(
                                        "phone:1",
                                        Kept.Phone,
                                        "Coffee in Zürich",
                                        listOf(osm, lindenhof),
                                        names = mapOf(lindenhof to "Lindenhof"),
                                    ),
                                ),
                            names = mapOf(osm to "Conditorei Schober"),
                            signedIn = true,
                        ),
                    onOpen = {},
                    onBack = {},
                )
            }
        }
        compose.onNodeWithText("Conditorei Schober").assertExists()
        // A place without a name is still told by its link.
        compose.onNodeWithText("47.36667, 8.55").assertExists()
        // One not sent yet still says so.
        compose.onNodeWithText("Not sent yet", substring = true).assertExists()
        compose.onNodeWithText("Coffee in Zürich").performClick()
        // The saved row and the list entry both have the name; neither shows the OpenStreetMap id or the link.
        compose.onAllNodesWithText("Conditorei Schober").assertCountEquals(2)
        compose.onAllNodesWithText("Lindenhof").assertCountEquals(2)
        compose.onAllNodesWithText("OpenStreetMap node 270803095").assertCountEquals(0)
        compose.onAllNodesWithText("maps.app.goo.gl/x1").assertCountEquals(0)
    }

    @Test
    fun whereANewListGoesShowsBesideItsButton() {
        compose.setContent {
            PubkyMapsTheme(darkTheme = false) {
                ListPage(
                    state =
                        ListPageState(
                            placeName = "Conditorei Schober",
                            lists = listOf(ListChoice(OwnList("phone:1", Kept.Phone, "Sunday walks", emptyList()), holds = false)),
                            newName = "Cake",
                            newKept = Kept.Public,
                            keptChoices = listOf(Kept.Phone, Kept.Public),
                        ),
                    onToggle = {},
                    onNewNameChange = {},
                    onNewKeptChange = {},
                    onCreate = {},
                    onClose = {},
                )
            }
        }
        val button = compose.onNode(hasText("Create list with this place") and hasClickAction()).fetchSemanticsNode().boundsInRoot
        // "Public" is also the radio button's label; the badge is the one on the button's line, after it.
        val beside =
            compose.onAllNodesWithText("Public", useUnmergedTree = true).fetchSemanticsNodes().any { node ->
                val badge = node.boundsInRoot
                badge.left >= button.right && badge.top >= button.top && badge.bottom <= button.bottom
            }
        assertTrue("the Public badge is not beside Create list with this place ($button)", beside)
    }
}
