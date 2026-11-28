package to.pubky.maps.android

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
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
import to.pubky.maps.place.LocalRemoteImage
import to.pubky.maps.place.PlacePageActions
import to.pubky.maps.place.PlacePageContext
import to.pubky.maps.place.PlacePageState
import to.pubky.maps.place.PlaceRef
import to.pubky.maps.place.PlaceSections
import to.pubky.maps.place.PlaceSource
import to.pubky.maps.place.RemoteImageContent
import to.pubky.maps.place.StreetPhoto
import to.pubky.maps.ui.PubkyMapsTheme

/**
 * The place page's street-level photos: each photo one TalkBack stop that names who took it and when
 * and opens it in Panoramax' viewer, the licences under them opening their text, and no section when
 * there are no photos or none of them loads.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-notnight-mdpi")
class StreetPhotosSectionTest {
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

    private val page =
        PlacePageState(
            place = PlaceRef("https://www.openstreetmap.org/node/1", "Kunsthaus", GeoPoint(47.3701, 8.5481), null, PlaceSource.Map),
            loading = emptySet(),
        )

    private val koreller =
        StreetPhoto(
            id = "88949d59-c992-4016-ac51-5b983e8f5426",
            thumbnailUrl = "https://panoramax.openstreetmap.fr/derivates/88/94/9d/59/c992-4016-ac51-5b983e8f5426/thumb.jpg",
            viewerUrl = "https://panoramax.openstreetmap.fr/#focus=pic&pic=88949d59-c992-4016-ac51-5b983e8f5426",
            author = "Koreller",
            taken = "Jul 2025",
            licence = "CC-BY-SA-4.0",
            licenceUrl = "https://creativecommons.org/licenses/by-sa/4.0/",
        )
    private val ign =
        StreetPhoto(
            id = "a1effd71-9b92-4149-b2a7-2a7042f65cfa",
            thumbnailUrl = "https://panoramax.ign.fr/derivates/a1/ef/fd/71/9b92-4149-b2a7-2a7042f65cfa/thumb.jpg",
            viewerUrl = "https://panoramax.ign.fr/#focus=pic&pic=a1effd71-9b92-4149-b2a7-2a7042f65cfa",
            author = "immergis",
            taken = "Apr 2026",
            licence = "etalab-2.0",
            licenceUrl = "https://alliance.numerique.gouv.fr/licence-ouverte-open-licence/",
        )
    private val unnamed =
        StreetPhoto("00000000-0000-4000-8000-000000000001", "https://x.test/t.jpg", "https://x.test/#p", null, null, null, null)

    private val opened = ArrayList<String>()

    private val drawn: RemoteImageContent = { _, _, modifier, _, _ -> Box(modifier) }
    private val failing: RemoteImageContent = { _, _, _, _, onError -> SideEffect { onError() } }

    private fun show(
        photos: List<StreetPhoto>,
        images: RemoteImageContent = drawn,
    ) {
        val actions =
            PlacePageActions(
                onClose = {},
                onShare = null,
                openIn = emptyList(),
                onOpenIn = {},
                onOpenLink = { opened += it },
                onLoadMorePosts = {},
                streetPhotos = photos,
            )
        compose.setContent {
            PubkyMapsTheme {
                CompositionLocalProvider(LocalRemoteImage provides images) {
                    Column(Modifier.verticalScroll(rememberScrollState())) { PlaceSections(page, context, actions) }
                }
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun aPhotoIsOneStopNamingWhoTookItAndWhenAndOpensInPanoramax() {
        show(listOf(koreller, ign))
        val tile = compose.onNodeWithContentDescription("Street-level photo by Koreller taken Jul 2025")
        val node = tile.performScrollTo().fetchSemanticsNode()
        assertEquals(Role.Button, node.config.getOrNull(SemanticsProperties.Role))
        assertEquals("Open in Panoramax", node.config.getOrNull(SemanticsActions.OnClick)?.label)
        // Its caption lines are part of that one stop, not stops of their own.
        compose.onAllNodesWithText("Koreller").assertCountEquals(0)
        tile.performClick()
        assertEquals(listOf(koreller.viewerUrl), opened)
        compose.onNodeWithContentDescription("Street-level photo by immergis taken Apr 2026").assertExists()
    }

    @Test
    fun eachLicenceOfThePhotosShownIsCreditedAndOpensItsText() {
        show(listOf(koreller, ign, koreller.copy(id = "00000000-0000-4000-8000-000000000002")))
        compose.onNodeWithText("Street-level photos").assertExists()
        compose.onAllNodesWithText("Photos: Panoramax, CC BY-SA 4.0").assertCountEquals(1)
        compose.onNodeWithText("Photos: Panoramax, etalab-2.0").performScrollTo().performClick()
        assertEquals(listOf(ign.licenceUrl), opened)
    }

    @Test
    fun aPhotoWithoutAuthorDateOrLicenceStillCreditsPanoramax() {
        show(listOf(unnamed))
        compose.onNodeWithContentDescription("Street-level photo").assertExists()
        compose.onNodeWithText("Photos: Panoramax").assertExists()
    }

    @Test
    fun noPhotosNoSection() {
        show(emptyList())
        compose.onAllNodesWithText("Street-level photos").assertCountEquals(0)
    }

    @Test
    fun photosThatDoNotLoadTakeTheSectionWithThem() {
        show(listOf(koreller, ign), images = failing)
        compose.onAllNodesWithText("Street-level photos").assertCountEquals(0)
        compose.onAllNodesWithText("Photos: Panoramax, CC BY-SA 4.0").assertCountEquals(0)
    }
}
