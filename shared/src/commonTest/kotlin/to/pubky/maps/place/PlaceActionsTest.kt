package to.pubky.maps.place

import to.pubky.maps.config.OpenInApp
import to.pubky.maps.links.OpenInAction
import to.pubky.maps.write.Kept
import to.pubky.maps.write.OwnPlace
import to.pubky.maps.write.SavedEntry
import kotlin.test.Test
import kotlin.test.assertEquals

class PlaceActionsTest {
    @Test
    fun allShowWhenTheyFitElseMoreTakesTheLastSlot() {
        assertEquals(8, iconActionsShown(count = 8, slots = 8))
        assertEquals(4, iconActionsShown(count = 4, slots = 8))
        assertEquals(6, iconActionsShown(count = 8, slots = 7))
        assertEquals(0, iconActionsShown(count = 3, slots = 1))
        assertEquals(0, iconActionsShown(count = 3, slots = 0))
    }

    @Test
    fun buttonsFlowIntoLinesAndAWideOneHasItsOwn() {
        assertEquals(listOf(0..1, 2..3), flowLines(listOf(140, 140, 120, 80), maxWidth = 300, gap = 8))
        // 140 + 8 + 152 is exactly 300: still one line.
        assertEquals(listOf(0..1), flowLines(listOf(140, 152), maxWidth = 300, gap = 8))
        assertEquals(listOf(0..0, 1..1, 2..2), flowLines(listOf(100, 400, 100), maxWidth = 300, gap = 8))
        assertEquals(emptyList(), flowLines(emptyList(), maxWidth = 300, gap = 8))
    }

    @Test
    fun theLabelledActionsThatFitInTwoLinesShowWithMoreForTheRest() {
        // The panel's eight at the normal font, in a 368 dp row: Directions, Start here / Bookmark, Tag, More.
        val eight = listOf(141, 140, 125, 85, 90, 175, 100, 105)
        assertEquals(4, labelledActionsShown(eight, more = 100, maxWidth = 368, gap = 8))
        // All that fit show, without More.
        assertEquals(3, labelledActionsShown(listOf(141, 140, 125), more = 100, maxWidth = 368, gap = 8))
        assertEquals(8, labelledActionsShown(eight, more = 100, maxWidth = 1000, gap = 8))
        // More takes the place of the last one that would have fit.
        assertEquals(3, labelledActionsShown(listOf(180, 180, 180, 180, 180), more = 180, maxWidth = 368, gap = 8))
        // Too narrow for any but More.
        assertEquals(0, labelledActionsShown(listOf(400, 400, 400), more = 100, maxWidth = 368, gap = 8, lines = 1))
    }

    private val google = OpenInAction(OpenInApp.GoogleMaps, "Google Maps", "https://www.google.com/maps", null, inBrowser = true)
    private val tapped = mutableListOf<String>()

    private fun writes(own: OwnPlace) =
        PlaceWriteActions(
            own = own,
            viewer = null,
            onToggleSave = { tapped += "save" },
            onTag = { tapped += "tag" },
            onToggleTag = {},
            onPost = { tapped += "post" },
            onPhoto = {},
            onAddToList = { tapped += "list" },
        )

    private fun actions(
        writes: PlaceWriteActions?,
        chosenStart: Boolean = false,
    ) = PlacePageActions(
        onClose = {},
        onShare = { tapped += "share" },
        openIn = listOf(google),
        onOpenIn = { tapped += "open ${it.title}" },
        onOpenLink = {},
        onLoadMorePosts = {},
        writes = writes,
        onDirections = { tapped += "directions" },
        routeStart = RouteStartChoice(chosen = chosenStart, onToggle = { tapped += "start" }),
    )

    @Test
    fun theActionsAreInGoogleMapsOrderWithTheirWordsAndLooks() {
        val row = placeActions(actions(writes(OwnPlace.None)))
        assertEquals(
            listOf("Directions", "Start here", "Bookmark", "Tag", "Post", "Add to collection", "Share", "Open in"),
            row.map { it.label },
        )
        assertEquals(
            listOf(
                ActionLook.Filled,
                ActionLook.Outlined,
                ActionLook.Tonal,
                ActionLook.Outlined,
                ActionLook.Outlined,
                ActionLook.Outlined,
                ActionLook.Outlined,
                ActionLook.Outlined,
            ),
            row.map { it.look },
        )
        // As icons only Directions is filled: nothing is on, so no tonal one.
        assertEquals(listOf(ActionLook.Filled) + List(7) { ActionLook.Outlined }, row.map { it.iconLook })
        val app = row.last().choices.single()
        row.forEach { it.onClick() }
        app.onClick()
        assertEquals(listOf("directions", "start", "save", "tag", "post", "list", "share", "open Google Maps"), tapped)
        assertEquals("Google Maps (web)", app.label)
        assertEquals("Open in Google Maps (web)", app.overflowLabel)
    }

    @Test
    fun aToggledActionSaysItsState() {
        val bookmarked = OwnPlace.None.copy(saved = listOf(SavedEntry("osm:node/1", Kept.Private, "Café")))
        val row = placeActions(actions(writes(bookmarked), chosenStart = true))
        assertEquals(listOf("Directions", "Starting here", "Bookmarked"), row.take(3).map { it.label })
        assertEquals(ActionLook.Tonal, row[1].look)
        assertEquals(listOf(ActionLook.Filled, ActionLook.Tonal, ActionLook.Tonal), row.take(3).map { it.iconLook })
        assertEquals(ActionLook.Outlined, row[3].iconLook)
    }

    @Test
    fun withoutWritesShareIsThePlacesOwnTonalAction() {
        val row = placeActions(actions(writes = null))
        assertEquals(listOf("Directions", "Start here", "Share", "Open in"), row.map { it.label })
        assertEquals(ActionLook.Tonal, row[2].look)
        assertEquals(ActionLook.Outlined, row[2].iconLook)
    }
}
