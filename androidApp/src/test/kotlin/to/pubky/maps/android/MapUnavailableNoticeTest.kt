package to.pubky.maps.android

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import to.pubky.maps.config.ServerDefaults
import to.pubky.maps.map.MapScreen
import to.pubky.maps.ui.PubkyMapsTheme
import kotlin.math.abs

/**
 * The map's "could not load" notice keeps its text on the notice's width and Retry goes under it, at
 * the end, when the text does not fit on one line beside Retry (a phone at the normal font and at
 * 200 %); on a tablet both share one line. Native graphics, so text is measured with real fonts.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MapUnavailableNoticeTest {
    @get:Rule
    val compose = createComposeRule()

    private val note = "The map could not load. Check your connection."
    private var retries = 0

    private fun show(fontScale: Float) {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = fontScale)) {
                PubkyMapsTheme {
                    MapScreen(
                        attribution =
                            ServerDefaults.lists
                                .mapStyles(darkTheme = false)
                                .first()
                                .attribution,
                        mapUnavailable = true,
                        onRetryMap = { retries++ },
                        onMyLocation = {},
                        onOpenSettings = {},
                    ) { Box(Modifier.fillMaxSize()) }
                }
            }
        }
    }

    private fun lines(text: String): Int {
        val layouts = mutableListOf<TextLayoutResult>()
        compose
            .onNodeWithText(text, useUnmergedTree = true)
            .fetchSemanticsNode()
            .config[SemanticsActions.GetTextLayoutResult]
            .action
            ?.invoke(layouts)
        return layouts.single().lineCount
    }

    private fun bounds(text: String): DpRect = compose.onNodeWithText(text, useUnmergedTree = true).getUnclippedBoundsInRoot()

    private fun assertRetryUnderTheNote() {
        val noteBounds = bounds(note)
        val retry = bounds("Retry")
        assertTrue("Retry (top ${retry.top}) is under the note (bottom ${noteBounds.bottom})", retry.top >= noteBounds.bottom)
        // The notice ends 12 dp before the screen's edge and its padding is 8 dp there, so the button
        // (its whole touch target, not its label) ends 20 dp before the screen's edge.
        val button = compose.onNode(hasText("Retry") and hasClickAction()).getUnclippedBoundsInRoot()
        val buttonEnd = compose.onRoot().getUnclippedBoundsInRoot().right - 20.dp
        assertTrue("Retry ends at the notice's end (${button.right} vs $buttonEnd)", abs((buttonEnd - button.right).value) <= 1f)
    }

    @Test
    @Config(qualifiers = "w412dp-h915dp")
    fun atLargeFontRetryGoesUnderTheNoteAndTheNoteKeepsTheWidth() {
        show(fontScale = 2f)
        assertRetryUnderTheNote()
        // Beside Retry the note would get three lines of about two words ('The map could not / load. Check your / connection.').
        assertTrue("the note takes at most 2 lines, took ${lines(note)}", lines(note) <= 2)
        assertEquals(1, lines("Retry"))
    }

    @Test
    @Config(qualifiers = "w412dp-h915dp")
    fun atTheNormalFontOnAPhoneTheNoteStaysOnOneLine() {
        show(fontScale = 1f)
        // Beside Retry, 'connection.' would go alone on a second line.
        assertEquals(1, lines(note))
        assertRetryUnderTheNote()
        compose.onNodeWithText("Retry").assertIsDisplayed().performClick()
        assertEquals(1, retries)
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp")
    fun onATabletTheNoteAndRetryShareOneLine() {
        show(fontScale = 1f)
        assertEquals(1, lines(note))
        val noteBounds = bounds(note)
        val retry = bounds("Retry")
        assertTrue("Retry is beside the note", retry.left >= noteBounds.right)
        assertTrue("Retry is on the note's line", retry.top < noteBounds.bottom && retry.bottom > noteBounds.top)
        val noteMiddle = (noteBounds.top + noteBounds.bottom) / 2
        val retryMiddle = (retry.top + retry.bottom) / 2
        assertTrue("the note is centred on Retry's line ($noteMiddle vs $retryMiddle)", abs((noteMiddle - retryMiddle).value) <= 1f)
    }
}
