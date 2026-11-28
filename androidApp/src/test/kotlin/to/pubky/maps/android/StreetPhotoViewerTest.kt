package to.pubky.maps.android

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.LayoutDirection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import to.pubky.maps.links.GeoPoint
import to.pubky.maps.place.LocalRemoteImage
import to.pubky.maps.place.RemoteImageContent
import to.pubky.maps.place.StreetPhoto
import to.pubky.maps.place.StreetPhotoRoute
import to.pubky.maps.place.StreetPhotoViewer
import to.pubky.maps.place.StreetPhotosAnswer
import to.pubky.maps.place.StreetPhotosController
import to.pubky.maps.place.StreetPhotosState
import to.pubky.maps.ui.PubkyMapsTheme

/**
 * The street-level photo viewer: the chosen photo large (its larger copy over the thumbnail, fitted),
 * who took it, when, its licence and Open in Panoramax, the place's other photos to switch to, a 360°
 * photo turned to face the place, and what it says while Panoramax' answer is not there.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-notnight-mdpi")
class StreetPhotoViewerTest {
    @get:Rule
    val compose = createComposeRule()

    private val place = GeoPoint(47.3752, 8.5302)

    private fun photo(
        n: Int,
        author: String? = "Koreller",
        panorama: Boolean = false,
    ) = StreetPhoto(
        id = "00000000-0000-4000-8000-00000000000$n",
        thumbnailUrl = "https://x.test/$n/thumb.jpg",
        viewerUrl = "https://x.test/#focus=pic&pic=$n",
        author = author,
        taken = "Jul 2025",
        licence = "CC-BY-SA-4.0",
        licenceUrl = "https://creativecommons.org/licenses/by-sa/4.0/",
        photoUrl = "https://x.test/$n/sd.jpg",
        panorama = panorama,
        // 20 m west of the place, the camera looking north: the place is 90° to its right.
        point = GeoPoint(47.3752, 8.53),
        heading = 0.0,
    )

    private val photos = listOf(photo(1), photo(2, author = "immergis"), photo(3, panorama = true), photo(4, author = null))

    private val loaded = ArrayList<Pair<String, ContentScale>>()
    private val opened = ArrayList<String>()
    private var selected by mutableStateOf(photos[1].id)
    private var retried = 0
    private var state by mutableStateOf<StreetPhotosState?>(StreetPhotosState(place, photos, loading = false))

    private val drawn: RemoteImageContent = { url, _, modifier, _, scale, _ ->
        SideEffect { loaded += url to scale }
        Box(modifier.testTag(url))
    }
    private val failing: RemoteImageContent = { _, _, _, _, _, onError -> SideEffect { onError() } }

    private fun show(
        images: RemoteImageContent = drawn,
        direction: LayoutDirection = LayoutDirection.Ltr,
    ) {
        compose.setContent {
            PubkyMapsTheme {
                CompositionLocalProvider(LocalRemoteImage provides images, LocalLayoutDirection provides direction) {
                    StreetPhotoViewer(state, place, selected, { selected = it }, { retried++ }, { opened += it }, onBack = {})
                }
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun theChosenPhotoShowsLargeWithWhoTookItWhenAndItsLicence() {
        show()
        compose.onNodeWithText("2 of 4").assertExists()
        compose.onNodeWithContentDescription("Street-level photo by immergis taken Jul 2025").assertExists()
        compose.onNodeWithText("immergis").assertExists()
        compose.onNodeWithText("Jul 2025").assertExists()
        // The thumbnail the page loaded shows at once, the larger copy over it, both fitted whole.
        assertTrue(loaded.toString(), "https://x.test/2/thumb.jpg" to ContentScale.Fit in loaded)
        assertTrue(loaded.toString(), "https://x.test/2/sd.jpg" to ContentScale.Fit in loaded)
        compose.onNodeWithText("Photo: Panoramax, CC BY-SA 4.0").performClick()
        compose.onNodeWithText("Open in Panoramax").performClick()
        assertEquals(listOf("https://creativecommons.org/licenses/by-sa/4.0/", "https://x.test/#focus=pic&pic=2"), opened)
    }

    @Test
    fun thePlacesOtherPhotosAreThereToSwitchTo() {
        show()
        compose.onNodeWithContentDescription("Photo 2 of 4, street-level photo by immergis taken Jul 2025").assertIsSelected()
        compose
            .onNodeWithContentDescription(
                "Photo 1 of 4, street-level photo by Koreller taken Jul 2025",
            ).assertIsNotSelected()
            .performClick()
        compose.waitForIdle()
        assertEquals(photos[0].id, selected)
        compose.onNodeWithText("1 of 4").assertExists()
        compose.onNodeWithText("Koreller").assertExists()
        // A photo that names nobody says only when it was taken.
        compose.onNodeWithContentDescription("Photo 4 of 4, street-level photo taken Jul 2025").performClick()
        compose.onNodeWithContentDescription("Street-level photo taken Jul 2025").assertExists()
    }

    /** The 360° photo's turn and how far it is scrolled, in px. */
    private fun turned(): Pair<Float, Float> {
        val turns =
            SemanticsMatcher("scrolls across turns") { node ->
                (
                    node.config
                        .getOrNull(SemanticsProperties.HorizontalScrollAxisRange)
                        ?.maxValue
                        ?.invoke() ?: 0f
                ) > 1000f
            }
        val range = compose.onNode(turns).fetchSemanticsNode().config[SemanticsProperties.HorizontalScrollAxisRange]
        return (range.maxValue() + 412f) / 3 to range.value()
    }

    /** Where on the screen the place is in the 360° photo drawn: three quarters across its turn. */
    private fun placeOnScreen(): Float {
        // Not its bounds in the root, which the stage clips.
        val turn = compose.onNodeWithTag("https://x.test/3/sd.jpg", useUnmergedTree = true).fetchSemanticsNode()
        return turn.positionInRoot.x + 0.75f * turn.size.width
    }

    @Test
    fun a360PhotoOpensTurnedToThePlace() {
        selected = photos[2].id
        show()
        compose.onNodeWithContentDescription("Street-level photo by Koreller taken Jul 2025, 360°, turned to face the place").assertExists()
        compose.onNodeWithText("Jul 2025 · 360° photo, drag to look around").assertExists()
        // Three turns side by side, from the middle one: the place, 90° right of where the camera
        // looked, is at three quarters of the turn, in the middle of the 412 px stage.
        val (turn, scrolled) = turned()
        // On a phone the stage is higher than half its width: the turn is twice its height, wider than the stage.
        assertTrue("$turn", turn > 2 * 412f)
        assertEquals(turn + 0.75f * turn - 206f, scrolled, 1f)
        assertEquals(206f, placeOnScreen(), 1f)
        // The larger copy, loaded once; not the thumbnail, a crop of the photo's front.
        assertEquals(
            listOf("https://x.test/3/sd.jpg" to ContentScale.FillBounds),
            loaded.filter {
                it.first.startsWith("https://x.test/3/") &&
                    it.second != ContentScale.Crop
            },
        )
    }

    @Test
    fun a360PhotoTurnsTheSameWayInARightToLeftLanguage() {
        selected = photos[2].id
        show(direction = LayoutDirection.Rtl)
        assertEquals(206f, placeOnScreen(), 1f)
    }

    @Test
    fun a360PhotoThatDoesNotLoadSaysSoOnTheStage() {
        selected = photos[2].id
        show(images = failing)
        compose.onNodeWithText("The photo could not be loaded.").assertIsDisplayed()
    }

    @Test
    fun aPhotoNeitherOfWhoseCopiesLoadsSaysSo() {
        show(images = failing)
        compose.onNodeWithText("The photo could not be loaded.").assertExists()
        compose.onNodeWithText("Open in Panoramax").assertExists()
    }

    @Test
    fun aPhotoGoneFromTheAnswerShowsThePlacesFirst() {
        selected = "00000000-0000-4000-8000-000000000009"
        show()
        compose.onNodeWithText("1 of 4").assertExists()
        compose.onNodeWithContentDescription("Street-level photo by Koreller taken Jul 2025").assertExists()
    }

    @Test
    fun whilePanoramaxHasNotAnsweredOrCannotBeReachedItSaysSo() {
        state = null
        show()
        compose.onNodeWithText("Loading the photos…").assertExists()
        state = StreetPhotosState(place, loading = false, failed = true)
        compose.onNodeWithText("No Panoramax server could be reached.").assertExists()
        compose.onNodeWithText("Try again").performClick()
        assertEquals(1, retried)
        state = StreetPhotosState(place, loading = false)
        compose.onNodeWithText("Panoramax has no street-level photos of this place now.").assertExists()
        compose.onAllNodesWithText("Open in Panoramax").assertCountEquals(0)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    @After
    fun stop() = scope.cancel()

    @Test
    fun theViewerOpenedAfterTheAppWasClosedAsksForThePlacesPhotosOnce() {
        val asked = ArrayList<GeoPoint>()
        val controller =
            StreetPhotosController(
                source = { point ->
                    asked += point
                    StreetPhotosAnswer.Found(photos)
                },
                scope = scope,
            )
        compose.setContent {
            PubkyMapsTheme {
                CompositionLocalProvider(LocalRemoteImage provides drawn) { StreetPhotoRoute(controller, place, photos[1].id, onBack = {}) }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText("2 of 4").assertExists()
        assertEquals(listOf(place), asked)
        // Back on the place page, its photos are those the viewer had: not asked again.
        controller.open(place)
        assertEquals(listOf(place), asked)
    }
}
