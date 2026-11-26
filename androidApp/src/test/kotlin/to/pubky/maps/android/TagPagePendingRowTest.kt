package to.pubky.maps.android

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
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
import to.pubky.maps.ui.PubkyMapsTheme
import to.pubky.maps.write.OwnTag
import to.pubky.maps.write.TagPage
import to.pubky.maps.write.TagPageState
import kotlin.math.abs

/**
 * The tag page's waiting note keeps the page's width and "Try now" goes under it, at the end, when the
 * note does not fit on one line beside the button (a phone at the normal font and at 200 %); on a
 * tablet both share one line. Native graphics, so text is measured with real fonts.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TagPagePendingRowTest {
    @get:Rule
    val compose = createComposeRule()

    private val note = "Waiting for a connection: sent when it can be."
    private var retries = 0

    private fun show(
        fontScale: Float,
        waiting: Boolean = true,
    ) {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = fontScale)) {
                PubkyMapsTheme {
                    TagPage(
                        state =
                            TagPageState(
                                placeName = "Conditorei Schober",
                                mine = listOf(OwnTag("https://www.openstreetmap.org/node/1", "cake", pending = true)),
                                suggestions = emptyList(),
                                waiting = waiting,
                            ),
                        onTextChange = {},
                        onAdd = {},
                        onRemove = {},
                        onRetry = { retries++ },
                        onClose = {},
                    )
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

    private fun assertTryNowUnderTheNote() {
        val noteBounds = bounds(note)
        val button = bounds("Try now")
        val pageEnd = compose.onRoot().getUnclippedBoundsInRoot().right - 16.dp
        assertTrue("Try now (top ${button.top}) is under the note (bottom ${noteBounds.bottom})", button.top >= noteBounds.bottom)
        // The label sits inside the TextButton's 12 dp padding, so its end is that far from the page's edge.
        assertTrue("Try now ends at the page's end (${button.right} vs $pageEnd)", abs((pageEnd - 12.dp - button.right).value) <= 1f)
    }

    @Test
    @Config(qualifiers = "w412dp-h915dp")
    fun atLargeFontTryNowGoesUnderTheNoteAndTheNoteKeepsTheWidth() {
        show(fontScale = 2f)
        assertTryNowUnderTheNote()
        // Beside the button the note would get three lines of two words ('Waiting for a / connection: sent / when it can be.').
        assertTrue("the note takes at most 2 lines, took ${lines(note)}", lines(note) <= 2)
        assertEquals(1, lines("Try now"))
    }

    @Test
    @Config(qualifiers = "w412dp-h915dp")
    fun atTheNormalFontOnAPhoneTheNoteStaysOnOneLine() {
        show(fontScale = 1f)
        assertEquals(1, lines(note))
        assertTryNowUnderTheNote()
        compose.onNodeWithText("Try now").assertIsDisplayed().performClick()
        assertEquals(1, retries)
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp")
    fun onATabletTheNoteAndTryNowShareOneLine() {
        show(fontScale = 1f)
        assertEquals(1, lines(note))
        val noteBounds = bounds(note)
        val button = bounds("Try now")
        assertTrue("Try now is beside the note", button.left >= noteBounds.right)
        assertTrue("Try now is on the note's line", button.top < noteBounds.bottom && button.bottom > noteBounds.top)
    }

    @Test
    @Config(qualifiers = "w412dp-h915dp")
    fun whileSendingThereIsNoButton() {
        show(fontScale = 2f, waiting = false)
        compose.onNodeWithText("Sending…", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("Try now").assertDoesNotExist()
    }
}
