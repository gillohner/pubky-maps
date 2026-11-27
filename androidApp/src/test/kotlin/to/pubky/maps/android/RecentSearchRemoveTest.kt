package to.pubky.maps.android

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureScreenRoboImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import to.pubky.maps.config.ServerDefaults
import to.pubky.maps.links.GeoPoint
import to.pubky.maps.links.OsmId
import to.pubky.maps.links.OsmType
import to.pubky.maps.map.MapScreen
import to.pubky.maps.map.MapSearch
import to.pubky.maps.place.PlaceRef
import to.pubky.maps.place.PlaceSource
import to.pubky.maps.search.InMemoryRecentSearchStore
import to.pubky.maps.search.RecentSearch
import to.pubky.maps.search.SearchActions
import to.pubky.maps.search.SearchMode
import to.pubky.maps.search.SearchState
import to.pubky.maps.ui.PubkyMapsTheme

/**
 * One recent search removed the way a user removes it: a long press on its row opens a menu (captured
 * as the whole screen, the menu is a popup), "Remove from recent searches" takes only that entry away.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w412dp-h915dp")
@OptIn(ExperimentalRoborazziApi::class)
class RecentSearchRemoveTest {
    @get:Rule
    val compose = createComposeRule()

    private val schober =
        RecentSearch(
            "conditorei schober",
            PlaceRef(
                uri = "https://www.openstreetmap.org/node/270803095",
                name = "Conditorei schober",
                point = GeoPoint(47.3708689, 8.5418241),
                osm = OsmId(OsmType.Node, 270803095),
                source = PlaceSource.Pinakes,
                address = "Napfgasse 4, 8001 Zürich",
                category = "Café",
            ),
        )

    private val store = InMemoryRecentSearchStore(listOf(schober, RecentSearch("zoo"), RecentSearch("bahnhofstrasse 1")))
    private val picked = mutableListOf<RecentSearch>()

    private fun showRecents(removable: Boolean = true) {
        compose.setContent {
            val recents by store.entries.collectAsState()
            PubkyMapsTheme {
                MapScreen(
                    attribution =
                        ServerDefaults.lists
                            .mapStyles(darkTheme = isSystemInDarkTheme())
                            .first()
                            .attribution,
                    mapUnavailable = false,
                    onRetryMap = {},
                    onMyLocation = {},
                    onOpenSettings = {},
                    search =
                        MapSearch(
                            SearchState(mode = SearchMode.Editing),
                            recents = recents,
                            userLocation = GeoPoint(47.3779, 8.5403),
                            actions =
                                SearchActions(
                                    onSelectRecent = { picked += it },
                                    onRemoveRecent = store::remove.takeIf { removable },
                                ),
                        ),
                ) {
                    val dark = isSystemInDarkTheme()
                    Box(Modifier.fillMaxSize().background(placeholderMapColor(dark)))
                }
            }
        }
    }

    private fun longPressZoo(screenshot: String?) {
        showRecents()
        compose.onNodeWithText("zoo").performTouchInput { longClick() }
        compose.onNodeWithText("Remove from recent searches").assertExists()
        assertEquals("a long press does not search again", emptyList<RecentSearch>(), picked)
        screenshot?.let { captureScreenRoboImage("screenshots/$it.png") }
    }

    @Test
    @Config(qualifiers = "+notnight-mdpi")
    fun aLongPressRemovesOnlyThatEntryPhoneLight() {
        longPressZoo("search-recent-remove-phone-light")
        compose.onNodeWithText("Remove from recent searches").performClick()
        compose.waitForIdle()
        assertEquals(listOf("conditorei schober", "bahnhofstrasse 1"), store.entries.value.map { it.query })
        compose.onNodeWithText("zoo").assertDoesNotExist()
        compose.onNodeWithText("Remove from recent searches").assertDoesNotExist()
        compose.onNodeWithText("bahnhofstrasse 1").performClick()
        assertEquals(listOf("bahnhofstrasse 1"), picked.map { it.query })
    }

    @Test
    @Config(qualifiers = "+night-mdpi")
    fun theMenuPhoneDark() = longPressZoo("search-recent-remove-phone-dark")

    @Test
    @Config(qualifiers = "w1280dp-h800dp-notnight-mdpi")
    fun theMenuTabletLight() = longPressZoo("search-recent-remove-tablet-light")

    @Test
    @Config(qualifiers = "w1280dp-h800dp-night-mdpi")
    fun theMenuTabletDark() = longPressZoo("search-recent-remove-tablet-dark")

    @Test
    fun talkBackRemovesWithTheRowsAction() {
        showRecents()
        val row = compose.onNode(hasClickAction().and(hasText("zoo")))
        val config = row.fetchSemanticsNode().config
        assertEquals("Show options", config[SemanticsActions.OnLongClick].label)
        val remove = config[SemanticsActions.CustomActions].single()
        assertEquals("Remove from recent searches", remove.label)
        compose.runOnIdle { remove.action() }
        compose.waitForIdle()
        assertEquals(listOf("conditorei schober", "bahnhofstrasse 1"), store.entries.value.map { it.query })
        compose.onNodeWithText("zoo").assertDoesNotExist()
    }

    @Test
    fun withoutAWayToRemoveALongPressDoesNothing() {
        showRecents(removable = false)
        compose.onNodeWithText("zoo").performTouchInput { longClick() }
        compose.onNodeWithText("Remove from recent searches").assertDoesNotExist()
        val config = compose.onNode(hasClickAction().and(hasText("zoo"))).fetchSemanticsNode().config
        assertFalse("no remove action for TalkBack either", SemanticsActions.CustomActions in config)
        assertEquals(3, store.entries.value.size)
    }
}
