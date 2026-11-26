package to.pubky.maps.android

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import to.pubky.maps.ui.PubkyMapsTheme
import to.pubky.maps.write.PostPage
import to.pubky.maps.write.TagPage
import to.pubky.maps.write.TagPageState

/**
 * The "Public" note of the write pages on a 412 dp phone: at the normal font its sentence sits beside
 * the badge; at Android's largest font size (200 %) it goes under the badge, on the whole width, instead
 * of a narrow column beside it. Native graphics, so text is measured with real fonts.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp")
class KeptNoteLargeFontTest {
    @get:Rule
    val compose = createComposeRule()

    private fun show(
        fontScale: Float,
        page: @Composable () -> Unit,
    ) {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = fontScale)) {
                PubkyMapsTheme { page() }
            }
        }
    }

    private val post: @Composable () -> Unit = {
        PostPage(placeName = "Conditorei Schober", text = "", onTextChange = {}, onPost = {}, onClose = {})
    }

    private val tag: @Composable () -> Unit = {
        TagPage(TagPageState(placeName = "Conditorei Schober", mine = emptyList(), suggestions = emptyList()), {}, {}, {}, {}, {})
    }

    private fun bounds(text: String): DpRect =
        compose.onNodeWithText(text, substring = true, useUnmergedTree = true).getUnclippedBoundsInRoot()

    private fun lines(text: String): Int {
        val layouts = mutableListOf<TextLayoutResult>()
        compose
            .onNodeWithText(text, substring = true, useUnmergedTree = true)
            .fetchSemanticsNode()
            .config[SemanticsActions.GetTextLayoutResult]
            .action
            ?.invoke(layouts)
        return layouts.single().lineCount
    }

    private fun assertUnder() {
        val badge = bounds("Public")
        val note = bounds("Anyone can read it")
        assertTrue("note ${note.top} under the badge ${badge.bottom}", note.top >= badge.bottom)
        assertTrue("note on the whole width, from ${note.left}", note.left < badge.right)
    }

    private fun assertBeside() {
        val badge = bounds("Public")
        val note = bounds("Anyone can read it")
        assertTrue("note ${note.left} beside the badge ${badge.right}", note.left > badge.right)
        assertTrue("note starts on the badge's line", note.top < badge.bottom)
    }

    @Test
    fun atLargeFontThePostPageNoteGoesUnderTheBadge() {
        show(2f, post)
        assertUnder()
        // On the whole width the sentence takes 2 lines; beside the badge it took 3.
        assertEquals(2, lines("Anyone can read it"))
    }

    @Test
    fun atLargeFontTheTagPageNoteGoesUnderTheBadge() {
        show(2f, tag)
        assertUnder()
    }

    @Test
    fun atTheNormalFontThePostPageNoteStaysBesideTheBadge() {
        show(1f, post)
        assertBeside()
        assertEquals(1, lines("Anyone can read it"))
    }

    @Test
    fun atTheNormalFontTheTagPageNoteStaysBesideTheBadge() {
        show(1f, tag)
        assertBeside()
    }
}
