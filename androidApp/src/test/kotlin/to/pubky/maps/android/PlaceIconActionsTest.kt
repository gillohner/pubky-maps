package to.pubky.maps.android

import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.isPopup
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureScreenRoboImage
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import to.pubky.maps.config.OpenInApp
import to.pubky.maps.config.ServerDefaults
import to.pubky.maps.links.GeoPoint
import to.pubky.maps.links.OpenInAction
import to.pubky.maps.links.OsmId
import to.pubky.maps.links.OsmType
import to.pubky.maps.map.MapScreen
import to.pubky.maps.map.PlacePageSlot
import to.pubky.maps.place.PlacePageActions
import to.pubky.maps.place.PlacePageContext
import to.pubky.maps.place.PlacePageState
import to.pubky.maps.place.PlacePanel
import to.pubky.maps.place.PlaceRef
import to.pubky.maps.place.PlaceSheet
import to.pubky.maps.place.PlaceSource
import to.pubky.maps.place.PlaceWriteActions
import to.pubky.maps.place.RouteStartChoice
import to.pubky.maps.place.SheetStop
import to.pubky.maps.ui.PubkyMapsTheme
import to.pubky.maps.write.Kept
import to.pubky.maps.write.OwnPlace
import to.pubky.maps.write.SavedEntry

/**
 * The place page's actions on a phone are one row of icon buttons (Gil, 2026-11-27): each a 48 dp
 * target whose words are its TalkBack label and its long-press tooltip, the ones that do not fit
 * under More with their words; the panel beside the map on a tablet keeps the labelled buttons.
 * The screenshots are of the whole screen, as the More menu and the tooltip are popups.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w412dp-h915dp-notnight-mdpi")
@OptIn(ExperimentalRoborazziApi::class)
class PlaceIconActionsTest {
    @get:Rule
    val compose = createComposeRule()

    private val osm = "https://www.openstreetmap.org/node/270803095"

    private val page =
        PlacePageState(
            PlaceRef(
                uri = osm,
                name = "Conditorei Schober",
                point = GeoPoint(47.3722, 8.5446),
                osm = OsmId(OsmType.Node, 270803095),
                source = PlaceSource.Map,
                address = "Napfgasse 4, 8001 Zürich",
                category = "Café",
            ),
            loading = emptySet(),
        )

    private val context =
        PlacePageContext(
            now = LocalDateTime(LocalDate.parse("2026-11-25"), LocalTime(12, 0)),
            nowMillis = 1_795_608_000_000,
            timeZone = TimeZone.UTC,
            language = "en",
            distance = "850 m",
        )

    private val organic =
        OpenInAction(OpenInApp.OrganicMaps, "Organic Maps", "om://map?ll=47.37,8.54", "app.organicmaps", inBrowser = false)
    private val google = OpenInAction(OpenInApp.GoogleMaps, "Google Maps", "https://www.google.com/maps", null, inBrowser = true)

    /** What was tapped, by the action's words. */
    private val tapped = mutableListOf<String>()
    private val opened = mutableListOf<OpenInAction>()
    private var bookmarked by mutableStateOf(false)

    private fun actions() =
        PlacePageActions(
            onClose = {},
            onShare = { tapped += "Share" },
            openIn = listOf(organic, google),
            onOpenIn = { opened += it },
            onOpenLink = {},
            onLoadMorePosts = {},
            writes =
                PlaceWriteActions(
                    own =
                        if (bookmarked) {
                            OwnPlace.None.copy(
                                saved = listOf(SavedEntry(osm, Kept.Private, "Conditorei Schober")),
                            )
                        } else {
                            OwnPlace.None
                        },
                    viewer = "8pinxsr5ctu1ou5ruzomw4fiz4fe1jybsr6o9dnb8jxcc7bpc3wo",
                    onToggleSave = { tapped += "Bookmark" },
                    onTag = { tapped += "Tag" },
                    onToggleTag = {},
                    onPost = { tapped += "Post" },
                    onPhoto = {},
                    onAddToList = { tapped += "Add to collection" },
                ),
            onDirections = { tapped += "Directions" },
            routeStart = RouteStartChoice(chosen = false, onToggle = { tapped += "Start here" }),
        )

    /** The view the screen is in, for what TalkBack is given. */
    private var hostView: View? = null

    private fun show() {
        compose.setContent {
            hostView = LocalView.current
            PubkyMapsTheme {
                val actions = actions()
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
                    placePage =
                        PlacePageSlot(
                            full = false,
                            sheet = { maxHeight, inset ->
                                PlaceSheet(page, context, actions, SheetStop.Half, {}, maxHeight, bottomInset = inset)
                            },
                            panel = { modifier -> PlacePanel(page, context, actions, modifier) },
                        ),
                ) { Placeholder() }
            }
        }
        compose.waitForIdle()
    }

    @Composable
    private fun Placeholder() {
        Box(Modifier.fillMaxSize().background(placeholderMapColor(isSystemInDarkTheme())))
    }

    private fun iconButton(label: String) = compose.onNode(hasContentDescription(label) and hasClickAction())

    /** The area a finger can tap, at least 48 x 48 dp (the screenshots are at mdpi: 1 px is 1 dp). */
    private fun SemanticsNodeInteraction.assertTouchTargetAtLeast48Dp(): SemanticsNodeInteraction {
        val touch = fetchSemanticsNode().touchBoundsInRoot
        assertTrue("touch target ${touch.width} x ${touch.height} px", touch.width >= 48f && touch.height >= 48f)
        return this
    }

    private val allEight = listOf("Directions", "Start here", "Bookmark", "Tag", "Post", "Add to collection", "Share", "Open in")

    @Test
    fun onAPhoneEveryActionIsAnIconButtonWithItsWords() {
        show()
        allEight.forEach { label ->
            iconButton(label).assertTouchTargetAtLeast48Dp()
        }
        // No words in the row: "Add to collection" is only the Collections section's own button.
        allEight.forEach { label ->
            compose.onAllNodes(hasText(label) and hasClickAction()).assertCountEquals(if (label == "Add to collection") 1 else 0)
        }
        compose.onNode(hasContentDescription("More")).assertDoesNotExist()
        captureScreenRoboImage("screenshots/place-actions-phone-light.png")
    }

    @Test
    @Config(qualifiers = "+night")
    fun onAPhoneEveryActionIsAnIconButtonPhoneDark() {
        show()
        allEight.forEach { iconButton(it).assertExists() }
        captureScreenRoboImage("screenshots/place-actions-phone-dark.png")
    }

    @Test
    fun eachIconButtonDoesItsAction() {
        show()
        allEight.filter { it != "Open in" }.forEach { iconButton(it).performClick() }
        assertEquals(allEight.filter { it != "Open in" }, tapped)
        iconButton("Open in").performClick()
        compose.onNodeWithText("Google Maps (web)").performClick()
        assertEquals(listOf(google), opened)
    }

    @Test
    fun aBookmarkedPlaceSaysBookmarked() {
        bookmarked = true
        show()
        iconButton("Bookmarked").assertExists()
        iconButton("Bookmark").assertDoesNotExist()
    }

    @Test
    fun aLongPressShowsTheWords() {
        show()
        compose.onNodeWithText("Tag").assertDoesNotExist()
        iconButton("Tag").performTouchInput { longClick() }
        compose.onNodeWithText("Tag").assertExists()
        assertEquals("a long press only explains", emptyList<String>(), tapped)
        captureScreenRoboImage("screenshots/place-actions-tooltip-phone-light.png")
    }

    /** At the largest font the row is the same: icons do not grow with the text, only the tooltips' words do. */
    @Test
    fun theLargestFontKeepsOneRow() {
        RuntimeEnvironment.setFontScale(2f)
        show()
        allEight.forEach { iconButton(it).assertExists() }
        compose.onNode(hasContentDescription("More")).assertDoesNotExist()
        captureScreenRoboImage("screenshots/place-actions-large-font-phone-light.png")
    }

    /**
     * A 404 dp window has just the room for eight (380 dp of row and the 4 dp it starts early): each
     * keeps its whole 48 dp slot, the last one too, so the circles stay 8 dp apart.
     */
    @Test
    @Config(qualifiers = "w404dp-h900dp-notnight-mdpi")
    fun eightFitJustWithWholeSlots() {
        show()
        val lefts = allEight.map { iconButton(it).getUnclippedBoundsInRoot().left.value }
        assertEquals(List(7) { 48f }, lefts.zipWithNext { a, b -> b - a })
        compose.onNode(hasContentDescription("More")).assertDoesNotExist()
    }

    /** A 360 dp phone has room for seven: Share and the Open in apps go under More, each by its words. */
    private fun openMore(screenshot: String?) {
        show()
        listOf("Directions", "Start here", "Bookmark", "Tag", "Post", "Add to collection").forEach { iconButton(it).assertExists() }
        iconButton("Share").assertDoesNotExist()
        iconButton("Open in").assertDoesNotExist()
        iconButton("More").assertTouchTargetAtLeast48Dp().performClick()
        compose.onNodeWithText("Share").assertExists()
        compose.onNodeWithText("Open in Organic Maps").assertExists()
        compose.onNodeWithText("Open in Google Maps (web)").assertExists()
        screenshot?.let { captureScreenRoboImage("screenshots/$it.png") }
    }

    @Test
    @Config(qualifiers = "w360dp-h780dp-notnight-mdpi")
    fun aNarrowPhonePutsTheRestUnderMore() {
        openMore("place-actions-more-smallphone-light")
        compose.onNodeWithText("Open in Organic Maps").performClick()
        assertEquals(listOf(organic), opened)
        compose.onNodeWithText("Share").assertDoesNotExist()
        iconButton("More").performClick()
        compose.onNodeWithText("Share").performClick()
        assertEquals(listOf("Share"), tapped)
    }

    @Test
    @Config(qualifiers = "w360dp-h780dp-night-mdpi")
    fun theMoreMenuSmallPhoneDark() = openMore("place-actions-more-smallphone-dark")

    /** How many of the labelled buttons with [label] show on the screen (the Collections section has its own "Add to collection"). */
    private fun labelledShown(label: String): Int {
        val nodes = compose.onAllNodes(hasText(label) and hasClickAction())
        return nodes.fetchSemanticsNodes().indices.count { nodes[it].isDisplayed() }
    }

    /** Whether TalkBack would read each clickable node with [label]'s text. */
    private fun visibleToTalkBack(label: String): List<Boolean> {
        val provider = checkNotNull(hostView?.accessibilityNodeProvider)
        return compose.onAllNodes(hasText(label) and hasClickAction()).fetchSemanticsNodes().map {
            provider.createAccessibilityNodeInfo(it.id)?.isVisibleToUser == true
        }
    }

    private val inTheRow = listOf("Directions", "Start here", "Bookmark", "Tag")
    private val underMore = listOf("Post", "Share", "Open in")

    /**
     * The panel beside the map keeps the words: the actions that fit in two lines, each whole inside the
     * 400 dp panel (12 dp from the screen's edge), the rest under a labelled More.
     */
    private fun panelKeepsTheWords(screenshot: String) {
        show()
        inTheRow.forEach { label ->
            assertEquals(label, 1, labelledShown(label))
            val right =
                compose
                    .onNode(hasText(label) and hasClickAction())
                    .getUnclippedBoundsInRoot()
                    .right.value
            assertTrue("$label ends at $right dp, outside the panel", right <= 412f - 8f)
        }
        underMore.forEach { label -> assertEquals(label, 0, labelledShown(label)) }
        // The buttons under More are measured but not placed: TalkBack does not see them either.
        inTheRow.forEach { label -> assertEquals(label, listOf(true), visibleToTalkBack(label)) }
        underMore.forEach { label -> assertEquals(label, listOf(false), visibleToTalkBack(label)) }
        // The Collections section's own button, not one in the row.
        assertEquals(1, labelledShown("Add to collection"))
        compose.onNode(hasText("More") and hasClickAction()).assertIsDisplayed().assertTouchTargetAtLeast48Dp()
        compose.onNode(hasContentDescription("More")).assertDoesNotExist()
        captureScreenRoboImage("screenshots/$screenshot.png")
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp-notnight-mdpi")
    fun theTabletPanelKeepsTheLabelledButtons() = panelKeepsTheWords("place-actions-tablet-light")

    @Test
    @Config(qualifiers = "w1280dp-h800dp-night-mdpi")
    fun theTabletPanelKeepsTheLabelledButtonsDark() = panelKeepsTheWords("place-actions-tablet-dark")

    @Test
    @Config(qualifiers = "w1280dp-h800dp-notnight-mdpi")
    fun thePanelsMoreHasTheRestByTheirWords() {
        show()
        inTheRow.forEach { compose.onNode(hasText(it) and hasClickAction()).performClick() }
        assertEquals(inTheRow, tapped)
        compose.onNode(hasText("More") and hasClickAction()).performClick()
        listOf("Post", "Add to collection", "Share", "Open in Organic Maps", "Open in Google Maps (web)").forEach { label ->
            compose.onNode(hasText(label) and hasClickAction() and hasAnyAncestor(isPopup())).assertIsDisplayed()
        }
        inTheRow.forEach { compose.onNode(hasText(it) and hasAnyAncestor(isPopup())).assertDoesNotExist() }
        captureScreenRoboImage("screenshots/place-actions-more-tablet-light.png")
        compose.onNode(hasText("Share") and hasAnyAncestor(isPopup())).performClick()
        compose.onNode(hasText("Share") and hasAnyAncestor(isPopup())).assertDoesNotExist()
        compose.onNode(hasText("More") and hasClickAction()).performClick()
        compose.onNode(hasText("Open in Google Maps (web)") and hasAnyAncestor(isPopup())).performClick()
        assertEquals(inTheRow + "Share", tapped)
        assertEquals(listOf(google), opened)
    }

    /** At the largest font the panel's buttons grow with their words and fewer fit before More. */
    @Test
    @Config(qualifiers = "w1280dp-h800dp-notnight-mdpi")
    fun theTabletPanelAtTheLargestFont() {
        RuntimeEnvironment.setFontScale(2f)
        show()
        compose.onNode(hasText("Directions") and hasClickAction()).assertIsDisplayed()
        compose.onNode(hasText("More") and hasClickAction()).assertIsDisplayed()
        (inTheRow + "More").forEach { label ->
            val nodes = compose.onAllNodes(hasText(label) and hasClickAction())
            nodes.fetchSemanticsNodes().indices.filter { nodes[it].isDisplayed() }.forEach {
                val right = nodes[it].getUnclippedBoundsInRoot().right.value
                assertTrue("$label ends at $right dp, outside the panel", right <= 412f - 8f)
            }
        }
        captureScreenRoboImage("screenshots/place-actions-large-font-tablet-light.png")
    }
}
