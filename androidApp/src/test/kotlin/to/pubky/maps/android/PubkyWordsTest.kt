package to.pubky.maps.android

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import to.pubky.maps.account.AccountScreen
import to.pubky.maps.account.AccountState
import to.pubky.maps.account.AccountUser
import to.pubky.maps.account.Connection
import to.pubky.maps.cache.CachedAccount
import to.pubky.maps.cache.CachedBookmark
import to.pubky.maps.links.GeoPoint
import to.pubky.maps.place.PlacePageActions
import to.pubky.maps.place.PlacePageContext
import to.pubky.maps.place.PlacePageState
import to.pubky.maps.place.PlacePanel
import to.pubky.maps.place.PlaceRef
import to.pubky.maps.place.PlaceSource
import to.pubky.maps.place.PlaceWriteActions
import to.pubky.maps.search.SearchActions
import to.pubky.maps.search.SearchList
import to.pubky.maps.search.SearchState
import to.pubky.maps.ui.PubkyMapsTheme
import to.pubky.maps.write.Kept
import to.pubky.maps.write.ListChoice
import to.pubky.maps.write.ListPage
import to.pubky.maps.write.ListPageState
import to.pubky.maps.write.OwnList
import to.pubky.maps.write.OwnPlace
import to.pubky.maps.write.SavedEntry
import to.pubky.maps.write.SavedRow
import to.pubky.maps.write.SavedScreen
import to.pubky.maps.write.SavedScreenState

/**
 * Bookmarks and collections are named as pubky.app and the Pubky specs name them: a place is
 * bookmarked and added to a collection, never saved or put in a list (Gil, 2026-11-27).
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PubkyWordsTest {
    @get:Rule
    val compose = createComposeRule()

    private val osm = "https://www.openstreetmap.org/node/270803095"
    private val coffee = OwnList("phone:1", Kept.Phone, "Coffee in Zürich", listOf(osm))

    @Test
    fun thePlacePageBookmarksAndAddsToACollection() {
        var own by mutableStateOf(OwnPlace.None.copy(lists = listOf(coffee), allLists = setOf(coffee.uri)))
        compose.setContent { PubkyMapsTheme(darkTheme = false) { PlacePanel(page, context, actions(own)) } }
        compose.onNode(hasText("Bookmark") and hasClickAction()).assertExists()
        // In the action row and beside the Collections section's title.
        compose.onAllNodesWithText("Add to collection").assertCountEquals(2)
        compose.onNodeWithText("In 1 collection").assertExists()
        compose.onNodeWithText("Your collection · 1 place", substring = true).assertExists()
        assertNoOldWords()

        own = own.copy(saved = listOf(SavedEntry(osm, Kept.Private, "Conditorei Schober")))
        compose.onNode(hasText("Bookmarked") and hasClickAction()).assertExists()
        assertNoOldWords()
    }

    @Test
    fun theBookmarksPageNamesBookmarksAndCollections() {
        compose.setContent {
            PubkyMapsTheme(darkTheme = false) {
                SavedScreen(SavedScreenState(places = emptyList(), lists = emptyList()), onOpen = { _, _ -> }, onBack = {})
            }
        }
        compose.onNodeWithText("Bookmarks").assertExists()
        compose.onNodeWithText("Tap Bookmark on a place's page", substring = true).assertExists()
        compose.onNodeWithText("Your collections").assertExists()
        compose.onNodeWithText("Tap Add to collection on a place's page", substring = true).assertExists()
        compose.onNodeWithText("Sign in to see the bookmarks and collections", substring = true).assertExists()
        assertNoOldWords()
    }

    @Test
    fun theCollectionPickerNamesCollections() {
        compose.setContent {
            PubkyMapsTheme(darkTheme = false) {
                ListPage(
                    state =
                        ListPageState(
                            placeName = "Conditorei Schober",
                            lists = listOf(ListChoice(coffee, holds = true)),
                            stale = true,
                            newOpen = true,
                            keptChoices = listOf(Kept.Phone, Kept.Public),
                        ),
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
        compose.onNodeWithText("Add to collection").assertExists()
        compose.onNodeWithText("Your collections").assertExists()
        compose.onNodeWithText("New collection").assertExists()
        compose.onNodeWithText("Create collection with this place").assertExists()
        assertNoOldWords()
    }

    @Test
    fun aFilledBookmarksPageWithAnOpenCollectionSaysNoOldWord() {
        compose.setContent {
            PubkyMapsTheme(darkTheme = false) {
                SavedScreen(
                    SavedScreenState(
                        places = listOf(SavedRow(osm, "Conditorei Schober", Kept.Private, pending = true)),
                        lists = listOf(coffee),
                        names = mapOf(osm to "Conditorei Schober"),
                        signedIn = true,
                    ),
                    onOpen = { _, _ -> },
                    onBack = {},
                )
            }
        }
        assertNoOldWords()
        // The collection's own page.
        compose.onNodeWithText("Coffee in Zürich").performClick()
        compose.onNodeWithText("Conditorei Schober").assertExists()
        compose.onAllNodesWithText("Not sent yet", substring = true).assertCountEquals(0)
        assertNoOldWords()
    }

    @Test
    fun theSearchPageOpensBookmarks() {
        compose.setContent {
            PubkyMapsTheme(darkTheme = false) {
                SearchList(SearchState(), recents = emptyList(), userLocation = null, actions = SearchActions(onOpenSaved = {}))
            }
        }
        compose.onNodeWithText("Bookmarks").assertExists()
        compose.onNodeWithText("Your bookmarks and collections").assertExists()
        assertNoOldWords()
    }

    @Test
    fun theAccountPageListsBookmarks() {
        val user = AccountUser("8pinxxgqs41n4aididenw5apqp1urfmzdztr8jt4abrkdn435ewo", "Ada Lovelace")
        val cached =
            CachedAccount(
                publicKey = user.publicKey,
                name = user.name,
                refreshedAt = 1,
                tags = emptyList(),
                bookmarks = listOf(CachedBookmark("/priv/social/v1/bookmarks/a.json", osm)),
            )
        compose.setContent {
            PubkyMapsTheme(darkTheme = false) {
                AccountScreen(
                    state = AccountState.SignedIn(user, cached, Connection.Offline),
                    ringOnThisPhone = false,
                    onSignIn = {},
                    onOpenRing = {},
                    onCancelSignIn = {},
                    onRefresh = {},
                    onSignOut = {},
                    onBack = {},
                    placeLabel = { "Conditorei Schober" },
                )
            }
        }
        compose.onNodeWithText("Your bookmarks", substring = true).assertExists()
        compose.onNodeWithText("Showing this phone's copy", substring = true).assertExists()
        assertNoOldWords()
    }

    /** No text or TalkBack label on screen says save, saved or list. */
    private fun assertNoOldWords() {
        val texts = mutableListOf<String>()

        fun walk(node: SemanticsNode) {
            node.config.getOrNull(SemanticsProperties.Text)?.forEach { texts += it.text }
            node.config.getOrNull(SemanticsProperties.ContentDescription)?.let { texts += it }
            node.config.getOrNull(SemanticsProperties.StateDescription)?.let { texts += it }
            node.config.getOrNull(SemanticsProperties.EditableText)?.let { texts += it.text }
            node.config
                .getOrNull(SemanticsActions.OnClick)
                ?.label
                ?.let { texts += it }
            node.config.getOrNull(SemanticsActions.CustomActions)?.forEach { texts += it.label }
            node.children.forEach(::walk)
        }
        walk(compose.onRoot(useUnmergedTree = true).fetchSemanticsNode())
        assertEquals(emptyList<String>(), texts.filter { OLD_WORDS.containsMatchIn(it) })
    }

    private val page =
        PlacePageState(
            place = PlaceRef(osm, "Conditorei Schober", GeoPoint(47.3722, 8.5446), null, PlaceSource.Map, category = "Café"),
            loading = emptySet(),
        )

    private val context =
        PlacePageContext(
            now = LocalDateTime(LocalDate.parse("2026-11-25"), LocalTime(12, 0)),
            nowMillis = 1_795_608_000_000,
            timeZone = TimeZone.UTC,
            language = "en",
            distance = null,
        )

    private fun actions(own: OwnPlace) =
        PlacePageActions(
            onClose = {},
            onShare = {},
            openIn = emptyList(),
            onOpenIn = {},
            onOpenLink = {},
            onLoadMorePosts = {},
            writes =
                PlaceWriteActions(
                    own = own,
                    viewer = "8pinxsr5ctu1ou5ruzomw4fiz4fe1jybsr6o9dnb8jxcc7bpc3wo",
                    onToggleSave = {},
                    onTag = {},
                    onToggleTag = {},
                    onPost = {},
                    onPhoto = {},
                    onAddToList = {},
                ),
        )

    private companion object {
        val OLD_WORDS = Regex("\\b(save|saved|saving|list|lists)\\b", RegexOption.IGNORE_CASE)
    }
}
