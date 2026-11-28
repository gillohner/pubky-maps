package to.pubky.maps.android

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ButtonDefaults
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import to.pubky.maps.links.GeoPoint
import to.pubky.maps.links.OsmId
import to.pubky.maps.links.OsmType
import to.pubky.maps.place.PlaceCardInfo
import to.pubky.maps.place.PlacePageActions
import to.pubky.maps.place.PlacePageContext
import to.pubky.maps.place.PlacePageState
import to.pubky.maps.place.PlaceRef
import to.pubky.maps.place.PlaceSheet
import to.pubky.maps.place.PlaceSource
import to.pubky.maps.place.SheetStop
import to.pubky.maps.ui.PubkyMapsTheme

/**
 * At its lowest height the phone place sheet shows its whole header: name, kind, address, opening
 * hours and the row of actions, at the normal font size and at twice it (every size doubled, stricter
 * than Android's largest setting, which grows big text less), also above a system bar, as Google
 * Maps' and CoMaps' collapsed place cards do. Native graphics, so text is measured with real fonts.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp-notnight-mdpi")
class PlaceSheetPeekTest {
    @get:Rule
    val compose = createComposeRule()

    private val context =
        PlacePageContext(
            now = LocalDateTime(LocalDate.parse("2026-11-25"), LocalTime(12, 0)),
            nowMillis = 1_795_608_000_000,
            timeZone = TimeZone.UTC,
            language = "en",
            distance = "850 m",
        )

    private val place =
        PlaceRef(
            uri = "https://www.openstreetmap.org/relation/3768127",
            name = "Kunsthaus",
            point = GeoPoint(47.3702241, 8.5479797),
            osm = OsmId(OsmType.Relation, 3768127),
            source = PlaceSource.Pinakes,
            address = "Heimplatz 1, 8001 Zürich",
            category = "Museum",
        )

    private val page =
        PlacePageState(
            place = place,
            card =
                PlaceCardInfo(
                    id = "49d6eb1c6a69946a41fe45ccd44c4a72",
                    uri = place.uri,
                    name = "Kunsthaus",
                    type = "Museum",
                    category = "tourism/museum",
                    address = "Heimplatz 1, 8001 Zürich",
                    lat = 47.3702241,
                    lon = 8.5479797,
                    description = null,
                    image = null,
                    credits = emptyList(),
                    attribution = null,
                    alsoOn = emptyList(),
                    refs = listOf(place.uri),
                    collections = 0,
                    facts = mapOf("opening_hours" to "Tu-Su 10:00-18:00"),
                ),
            loading = emptySet(),
        )

    private val actions =
        PlacePageActions(onClose = {}, onShare = {}, openIn = emptyList(), onOpenIn = {}, onOpenLink = {}, onLoadMorePosts = {})

    private fun showAtPeek(
        fontScale: Float,
        bottomInset: Dp = 0.dp,
    ) {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                PubkyMapsTheme {
                    Box(Modifier.fillMaxSize()) {
                        PlaceSheet(
                            page,
                            context,
                            actions,
                            SheetStop.Peek,
                            {},
                            maxHeight = 800.dp,
                            modifier = Modifier.align(Alignment.BottomCenter),
                            bottomInset = bottomInset,
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    /**
     * The row of actions, the header's last line, is laid out whole on the screen: the Share button
     * has its full height (a sheet too short squeezes it, down to nothing) and ends above the
     * [bottomInset] at the bottom of the screen.
     */
    private fun assertHeaderShown(bottomInset: Dp = 0.dp) {
        val bottom = compose.onRoot().getUnclippedBoundsInRoot().bottom - bottomInset
        val share = compose.onNode(hasContentDescription("Share") and hasClickAction()).getUnclippedBoundsInRoot()
        val height = share.bottom - share.top
        assertTrue("the Share button is $height tall", height >= ButtonDefaults.MinHeight)
        assertTrue("the Share button ends at ${share.bottom}, the sheet at $bottom", share.bottom <= bottom)
    }

    @Test
    fun theWholeHeaderShowsAtTheNormalFontSize() {
        showAtPeek(fontScale = 1f)
        assertHeaderShown()
    }

    @Test
    fun theWholeHeaderShowsAtTwiceTheFontSize() {
        showAtPeek(fontScale = 2f)
        assertHeaderShown()
    }

    @Test
    fun theWholeHeaderShowsAboveTheSystemBar() {
        showAtPeek(fontScale = 2f, bottomInset = 48.dp)
        assertHeaderShown(bottomInset = 48.dp)
    }
}
