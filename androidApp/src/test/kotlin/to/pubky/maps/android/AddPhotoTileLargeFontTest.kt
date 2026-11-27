package to.pubky.maps.android

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onParent
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
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
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import to.pubky.maps.links.GeoPoint
import to.pubky.maps.links.OsmId
import to.pubky.maps.links.OsmType
import to.pubky.maps.place.PlacePageActions
import to.pubky.maps.place.PlacePageContext
import to.pubky.maps.place.PlacePageState
import to.pubky.maps.place.PlacePanel
import to.pubky.maps.place.PlaceRef
import to.pubky.maps.place.PlaceSocial
import to.pubky.maps.place.PlaceSource
import to.pubky.maps.place.PlaceWriteActions
import to.pubky.maps.ui.PubkyMapsTheme
import to.pubky.maps.write.OwnPlace

/**
 * At Android's largest font size (200 %) the place page's "Add photo" tile (120 dp square) wraps its
 * label into two lines; each line is centred under the centred camera icon and keeps 8 dp from the
 * tile's sides, instead of starting at its left edge.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp")
class AddPhotoTileLargeFontTest {
    @get:Rule
    val compose = createComposeRule()

    private val page =
        PlacePageState(
            place =
                PlaceRef(
                    uri = "https://www.openstreetmap.org/node/270803095",
                    name = "Conditorei Schober",
                    point = GeoPoint(47.3722, 8.5446),
                    osm = OsmId(OsmType.Node, 270803095),
                    source = PlaceSource.Map,
                ),
            social = PlaceSocial(emptyList(), emptyList(), 0, 0),
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

    private val actions =
        PlacePageActions(
            onClose = {},
            onShare = {},
            openIn = emptyList(),
            onOpenIn = {},
            onOpenLink = {},
            onLoadMorePosts = {},
            writes =
                PlaceWriteActions(
                    own = OwnPlace.None,
                    viewer = "8pinxsr5ctu1ou5ruzomw4fiz4fe1jybsr6o9dnb8jxcc7bpc3wo",
                    onToggleSave = {},
                    onTag = {},
                    onToggleTag = {},
                    onPost = {},
                    onPhoto = {},
                    onAddToList = {},
                ),
        )

    @Test
    fun atLargeFontTheWrappedLabelIsCentredInTheTile() {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) {
                PubkyMapsTheme { PlacePanel(page, context, actions, Modifier.fillMaxSize()) }
            }
        }
        val label = compose.onNodeWithText("Add photo", useUnmergedTree = true)
        val layouts = mutableListOf<TextLayoutResult>()
        label
            .fetchSemanticsNode()
            .config[SemanticsActions.GetTextLayoutResult]
            .action
            ?.invoke(layouts)
        val layout = layouts.single()
        assertEquals("the label wraps at 200 %", 2, layout.lineCount)
        val width = layout.size.width.toFloat()
        for (line in 0 until layout.lineCount) {
            val left = layout.getLineLeft(line)
            val right = width - layout.getLineRight(line)
            assertEquals("line $line is centred (left $left, right $right)", left, right, 1.5f)
            assertTrue("line $line does not start at the text's edge", left > 1f)
        }
        val text = label.getUnclippedBoundsInRoot()
        val tile = label.onParent().getUnclippedBoundsInRoot()
        assertTrue("8 dp from the tile's left side", text.left - tile.left >= 8.dp - 0.5.dp)
        assertTrue("8 dp from the tile's right side", tile.right - text.right >= 8.dp - 0.5.dp)
    }
}
