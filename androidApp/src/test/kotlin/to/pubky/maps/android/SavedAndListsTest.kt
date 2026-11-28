package to.pubky.maps.android

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.TextLayoutResult
import org.junit.Assert.assertEquals
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
import to.pubky.maps.write.LIST_DESCRIPTION_MAX
import to.pubky.maps.write.ListChoice
import to.pubky.maps.write.ListPage
import to.pubky.maps.write.ListPageState
import to.pubky.maps.write.OwnList
import to.pubky.maps.write.SavedRow
import to.pubky.maps.write.SavedScreen
import to.pubky.maps.write.SavedScreenState

/**
 * The Saved page names places the way the user knows them (a named place shows no OpenStreetMap id
 * or link), says what and where each is under its name and tells two places of the same name apart
 * by where they are; the list page shows where a new list goes beside the button that makes it.
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
                    onOpen = { _, _ -> },
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
        // The collection's page names its places too; it shows neither the OpenStreetMap id nor the link.
        compose.onNodeWithText("Conditorei Schober").assertExists()
        compose.onNodeWithText("Lindenhof").assertExists()
        compose.onAllNodesWithText("OpenStreetMap node 270803095").assertCountEquals(0)
        compose.onAllNodesWithText("maps.app.goo.gl/x1").assertCountEquals(0)
    }

    @Test
    fun aSavedPlaceSaysWhatAndWhereItIsUnderItsName() {
        val bahnhofbruecke = "https://www.openstreetmap.org/node/1"
        val bellevue = "https://www.openstreetmap.org/node/2"
        compose.setContent {
            PubkyMapsTheme(darkTheme = false) {
                SavedScreen(
                    state =
                        SavedScreenState(
                            places =
                                listOf(
                                    SavedRow(osm, "Conditorei Schober", Kept.Private, summary = "Café · Napfgasse 4, 8001 Zürich"),
                                    SavedRow(
                                        bahnhofbruecke,
                                        "Coop",
                                        Kept.Private,
                                        point = GeoPoint(47.37586, 8.54253),
                                        summary = "Supermarket · Bahnhofbrücke 1",
                                    ),
                                    SavedRow(
                                        bellevue,
                                        "Coop",
                                        Kept.Phone,
                                        point = GeoPoint(47.36722, 8.54493),
                                        summary = "Supermarket · Theaterstrasse 2",
                                    ),
                                ),
                            lists =
                                listOf(
                                    OwnList("phone:1", Kept.Phone, "Coffee in Zürich", listOf(osm), names = mapOf(osm to "Schober")),
                                ),
                            summaries = mapOf(osm to "Café · Napfgasse 4, 8001 Zürich"),
                            signedIn = true,
                        ),
                    onOpen = { _, _ -> },
                    onBack = {},
                )
            }
        }
        compose.onNodeWithText("Café · Napfgasse 4, 8001 Zürich").assertExists()
        // Two Coops on different streets are told apart by their summaries: no point needed.
        compose.onNodeWithText("Supermarket · Bahnhofbrücke 1").assertExists()
        compose.onNodeWithText("Supermarket · Theaterstrasse 2").assertExists()
        compose.onAllNodesWithText("47.37586, 8.54253", substring = true).assertCountEquals(0)
        compose.onNodeWithText("Coffee in Zürich").performClick()
        // The collection's page says it too.
        compose.onNodeWithText("Café · Napfgasse 4, 8001 Zürich").assertExists()
    }

    @Test
    fun twoPlacesOfTheSameNameAreToldApartByWhereTheyAre() {
        val bahnhofbruecke = "https://www.openstreetmap.org/node/1"
        val bellevue = "https://www.openstreetmap.org/node/2"
        compose.setContent {
            PubkyMapsTheme(darkTheme = false) {
                SavedScreen(
                    state =
                        SavedScreenState(
                            places =
                                listOf(
                                    SavedRow(bahnhofbruecke, "Coop", Kept.Private, point = GeoPoint(47.37586, 8.54253)),
                                    SavedRow(bellevue, "Coop", Kept.Phone, point = GeoPoint(47.36722, 8.54493)),
                                    SavedRow(osm, "Conditorei Schober", Kept.Private, point = GeoPoint(47.37220, 8.54460)),
                                ),
                            lists =
                                listOf(
                                    OwnList(
                                        "phone:1",
                                        Kept.Phone,
                                        "Groceries",
                                        listOf(bahnhofbruecke, bellevue, "https://www.openstreetmap.org/node/3"),
                                        names =
                                            mapOf(
                                                bahnhofbruecke to "Coop",
                                                bellevue to "Coop",
                                                "https://www.openstreetmap.org/node/3" to "Migros",
                                            ),
                                    ),
                                ),
                            signedIn = true,
                        ),
                    onOpen = { _, _ -> },
                    onBack = {},
                )
            }
        }
        compose.onNodeWithText("47.37586, 8.54253").assertExists()
        compose.onNodeWithText("47.36722, 8.54493").assertExists()
        // A name no other place has shows the name alone.
        compose.onAllNodesWithText("47.3722, 8.5446").assertCountEquals(0)
        compose.onNodeWithText("Groceries").performClick()
        // On the collection's page too, from the bookmark's point; Migros, alone, has no line under it.
        compose.onNodeWithText("47.37586, 8.54253").assertExists()
        compose.onNodeWithText("47.36722, 8.54493").assertExists()
        compose.onAllNodesWithText("OpenStreetMap node 3").assertCountEquals(0)
    }

    @Test
    fun aPlaceWithoutASummaryIsToldFromASameNamedOneByItsPoint() {
        val bahnhofbruecke = "https://www.openstreetmap.org/node/1"
        val bellevue = "https://www.openstreetmap.org/node/2"
        compose.setContent {
            PubkyMapsTheme(darkTheme = false) {
                SavedScreen(
                    state =
                        SavedScreenState(
                            places =
                                listOf(
                                    SavedRow(
                                        bahnhofbruecke,
                                        "Coop",
                                        Kept.Private,
                                        point = GeoPoint(47.37586, 8.54253),
                                        summary = "Supermarket · Bahnhofbrücke 1",
                                    ),
                                    // Saved on another device, before summaries.
                                    SavedRow(bellevue, "Coop", Kept.Phone, point = GeoPoint(47.36722, 8.54493)),
                                ),
                            lists =
                                listOf(
                                    OwnList(
                                        "phone:1",
                                        Kept.Phone,
                                        "Groceries",
                                        listOf(bahnhofbruecke, bellevue),
                                        names = mapOf(bahnhofbruecke to "Coop", bellevue to "Coop"),
                                    ),
                                ),
                            summaries = mapOf(bahnhofbruecke to "Supermarket · Bahnhofbrücke 1"),
                            signedIn = true,
                        ),
                    onOpen = { _, _ -> },
                    onBack = {},
                )
            }
        }
        // The bare Coop shows where it is; the other is told by its summary alone.
        compose.onNodeWithText("47.36722, 8.54493").assertExists()
        compose.onNodeWithText("Supermarket · Bahnhofbrücke 1").assertExists()
        compose.onAllNodesWithText("47.37586, 8.54253", substring = true).assertCountEquals(0)
        compose.onNodeWithText("Groceries").performClick()
        // On the collection's page too.
        compose.onNodeWithText("47.36722, 8.54493").assertExists()
        compose.onNodeWithText("Supermarket · Bahnhofbrücke 1").assertExists()
        compose.onAllNodesWithText("47.37586, 8.54253", substring = true).assertCountEquals(0)
    }

    @Test
    fun aTapOpensThePlaceWithTheNameItsRowShows() {
        val opened = mutableListOf<Pair<String, String?>>()
        compose.setContent {
            PubkyMapsTheme(darkTheme = false) {
                SavedScreen(
                    state =
                        SavedScreenState(
                            places = listOf(SavedRow("geo:47.36667,8.55", null, Kept.Phone)),
                            // A dropped pin named only in the list that holds it.
                            lists =
                                listOf(
                                    OwnList(
                                        "phone:1",
                                        Kept.Phone,
                                        "Walks",
                                        listOf("geo:47.37,8.55"),
                                        names =
                                            mapOf("geo:47.37,8.55" to "Bench"),
                                    ),
                                ),
                            signedIn = true,
                        ),
                    onOpen = { place, name -> opened += place to name },
                    onBack = {},
                )
            }
        }
        compose.onNodeWithText("47.36667, 8.55").performClick()
        compose.onNodeWithText("Walks").performClick()
        compose.onNodeWithText("Bench").performClick()
        assertEquals(listOf("geo:47.36667,8.55" to null, "geo:47.37,8.55" to "Bench"), opened)
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
                    onNewDescriptionChange = {},
                    onNewKeptChange = {},
                    onCreate = {},
                    onClose = {},
                )
            }
        }
        val button = compose.onNode(hasText("Create collection with this place") and hasClickAction()).fetchSemanticsNode().boundsInRoot
        // "Public" is also the radio button's label; the badge is the one on the button's line, after it.
        val beside =
            compose.onAllNodesWithText("Public", useUnmergedTree = true).fetchSemanticsNodes().any { node ->
                val badge = node.boundsInRoot
                badge.left >= button.right && badge.top >= button.top && badge.bottom <= button.bottom
            }
        assertTrue("the Public badge is not beside Create collection with this place ($button)", beside)
    }

    @Test
    fun aCollectionShowsItsDescriptionUnderItsName() {
        compose.setContent {
            PubkyMapsTheme(darkTheme = false) {
                SavedScreen(
                    state =
                        SavedScreenState(
                            places = emptyList(),
                            lists =
                                listOf(
                                    OwnList("phone:1", Kept.Phone, "Coffee", listOf(osm), description = "Where to sit with a book"),
                                    OwnList("phone:2", Kept.Phone, "Walks", emptyList()),
                                ),
                        ),
                    onOpen = { _, _ -> },
                    onBack = {},
                )
            }
        }
        val name = compose.onNodeWithText("Coffee", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val description = compose.onNodeWithText("Where to sit with a book", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val count = compose.onNodeWithText("1 place", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue(
            "the description is not between the name and the count",
            name.bottom <= description.top && description.bottom <= count.top,
        )
        val walks = compose.onNodeWithText("Walks", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue("Walks has no description, so nothing comes before its count", walks.top >= count.bottom)
    }

    @Test
    fun aNewCollectionTakesADescriptionUpToTheSpecsLimit() {
        var state by mutableStateOf(ListPageState(placeName = "Conditorei Schober", lists = emptyList(), newName = "Cake"))
        compose.setContent {
            PubkyMapsTheme(darkTheme = false) {
                ListPage(
                    state = state,
                    onToggle = {},
                    onNewNameChange = {},
                    onNewDescriptionChange = { state = state.copy(newDescription = it) },
                    onNewKeptChange = {},
                    onCreate = {},
                    onClose = {},
                )
            }
        }
        val create = hasText("Create collection with this place") and hasClickAction()
        compose.onNodeWithText("Description (optional)").performTextInput("Worth the walk")
        assertEquals("Worth the walk", state.newDescription)
        compose.onNode(create).assertIsEnabled()

        state = state.copy(newDescription = "x".repeat(LIST_DESCRIPTION_MAX))
        compose.onNode(create).assertIsEnabled()
        compose.onNodeWithText("$LIST_DESCRIPTION_MAX / $LIST_DESCRIPTION_MAX").assertExists()

        state = state.copy(newDescription = "x".repeat(LIST_DESCRIPTION_MAX) + " ")
        compose.onNode(create).assertIsEnabled()

        state = state.copy(newDescription = "🍰".repeat(300))
        compose.onNodeWithText("300 / $LIST_DESCRIPTION_MAX").assertExists()
        compose.onNode(create).assertIsEnabled()

        state = state.copy(newDescription = "x".repeat(LIST_DESCRIPTION_MAX + 1))
        compose.onNode(create).assertIsNotEnabled()
    }

    @Test
    fun anOpenedCollectionShowsAllOfItsDescription() {
        val long =
            "Quiet cafés with a seat by the window, good for an hour with a book or a laptop on a rainy afternoon, " +
                "and a few that open early enough for a coffee before the first train."
        compose.setContent {
            PubkyMapsTheme(darkTheme = false) {
                SavedScreen(
                    state =
                        SavedScreenState(
                            places = emptyList(),
                            lists = listOf(OwnList("phone:1", Kept.Phone, "Coffee", listOf(osm), description = long)),
                        ),
                    onOpen = { _, _ -> },
                    onBack = {},
                )
            }
        }

        fun lines(): Int {
            val layouts = mutableListOf<TextLayoutResult>()
            compose
                .onNodeWithText(long, useUnmergedTree = true)
                .fetchSemanticsNode()
                .config[SemanticsActions.GetTextLayoutResult]
                .action!!
                .invoke(layouts)
            return layouts.single().lineCount.also { compose.waitForIdle() }
        }
        assertEquals("closed: two lines", 2, lines())

        compose.onNodeWithText("Coffee").performClick()
        assertTrue("opened: all of it (${lines()} lines)", lines() > 2)
    }
}
