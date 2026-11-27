package to.pubky.maps.android

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Dp
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
import to.pubky.maps.links.GeoPoint
import to.pubky.maps.place.PlaceCardInfo
import to.pubky.maps.place.PlacePageActions
import to.pubky.maps.place.PlacePageContext
import to.pubky.maps.place.PlacePageState
import to.pubky.maps.place.PlacePart
import to.pubky.maps.place.PlaceRef
import to.pubky.maps.place.PlaceSections
import to.pubky.maps.place.PlaceSource
import to.pubky.maps.ui.PubkyMapsTheme

/**
 * The Details section's coordinates row while the card loads: hidden while no card is shown (the card's
 * rows would arrive above it), shown at once over a card from the cache, so the sections under Details
 * stay where they are when the fresh card replaces the cached one.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-notnight-mdpi")
class PlaceDetailsWhileLoadingTest {
    @get:Rule
    val compose = createComposeRule()

    private val context =
        PlacePageContext(
            now = LocalDateTime(LocalDate.parse("2026-11-25"), LocalTime(12, 0)),
            nowMillis = 1_795_608_000_000,
            timeZone = TimeZone.UTC,
            language = "en",
            distance = null,
        )

    private val place = PlaceRef("https://www.openstreetmap.org/node/1", "Kunsthaus", GeoPoint(47.3701, 8.5481), null, PlaceSource.Map)

    private val card =
        PlaceCardInfo(
            id = "1",
            uri = place.uri,
            name = "Kunsthaus",
            type = "Museum",
            category = "tourism/museum",
            address = "Heimplatz 1, 8001 Zürich",
            lat = 47.3701,
            lon = 8.5481,
            description = null,
            image = null,
            credits = emptyList(),
            attribution = null,
            alsoOn = emptyList(),
            refs = listOf(place.uri),
            collections = 0,
            facts = mapOf("phone" to "+41 44 253 84 84"),
        )

    /** Opened again: the cached card shows while Pinakes is asked for a fresh one. */
    private val fromCache = PlacePageState(place = place, card = card, loading = setOf(PlacePart.Card), savedAt = 1_795_000_000_000)

    private val actions =
        PlacePageActions(onClose = {}, onShare = null, openIn = emptyList(), onOpenIn = {}, onOpenLink = {}, onLoadMorePosts = {})

    private var shown by mutableStateOf(fromCache)

    private fun show(page: PlacePageState) {
        shown = page
        compose.setContent {
            PubkyMapsTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    PlaceSections(shown, context, actions, Modifier.testTag("sections"))
                }
            }
        }
        compose.waitForIdle()
    }

    private fun sectionsHeight(): Dp {
        val bounds = compose.onNodeWithTag("sections").getUnclippedBoundsInRoot()
        return bounds.bottom - bounds.top
    }

    private fun coordinatesShown(): Boolean = compose.onAllNodesWithText(COORDINATES).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun aCachedCardShowsItsCoordinatesWhileTheFreshCardLoads() {
        show(fromCache)

        compose.onNodeWithText("+41 44 253 84 84").assertExists()
        compose.onNodeWithText(COORDINATES).assertExists()
    }

    @Test
    fun theSectionsKeepTheirHeightWhenTheFreshCardReplacesTheCachedOne() {
        show(fromCache)
        val before = sectionsHeight()

        shown = fromCache.copy(loading = emptySet())
        compose.waitForIdle()

        assertEquals(before.value, sectionsHeight().value, 0.5f)
    }

    @Test
    fun withNoCardYetTheCoordinatesWaitForItAndShowOnceItHasAnswered() {
        show(PlacePageState(place = place, loading = setOf(PlacePart.Card)))
        assertEquals(false, coordinatesShown())

        // Pinakes has no card for the place: the Details section shows the coordinates alone.
        shown = PlacePageState(place = place, loading = emptySet())
        compose.waitForIdle()
        assertEquals(true, coordinatesShown())
    }

    private companion object {
        const val COORDINATES = "47.3701, 8.5481"
    }
}
