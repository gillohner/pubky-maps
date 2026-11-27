package to.pubky.maps.android

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import to.pubky.maps.ui.PubkyMapsTheme
import to.pubky.maps.write.Kept
import to.pubky.maps.write.KeptNote
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

    private fun badge(): DpRect = compose.onNodeWithText("Public", useUnmergedTree = true).getUnclippedBoundsInRoot()

    private fun sentence(): DpRect =
        compose.onNodeWithText("Anyone can read it", substring = true, useUnmergedTree = true).getUnclippedBoundsInRoot()

    private fun assertUnder() {
        val badge = badge()
        val note = sentence()
        assertTrue("note ${note.top} under the badge ${badge.bottom}", note.top >= badge.bottom)
        assertTrue("note on the whole width, from ${note.left}", note.left < badge.right)
    }

    private fun assertBeside() {
        val badge = badge()
        val note = sentence()
        assertTrue("note ${note.left} beside the badge ${badge.right}", note.left > badge.right)
        assertTrue("note starts on the badge's line", note.top < badge.bottom)
    }

    @Test
    fun atLargeFontThePostPageNoteGoesUnderTheBadge() {
        show(2f, post)
        assertUnder()
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
    }

    @Test
    fun atTheNormalFontTheTagPageNoteStaysBesideTheBadge() {
        show(1f, tag)
        assertBeside()
    }

    /** A parent asking for the note's intrinsic height gets the height it draws, beside the badge. */
    @Test
    fun theIntrinsicHeightIsTheHeightOfTheNoteBesideTheBadge() {
        show(1f) {
            Row(Modifier.width(380.dp).height(IntrinsicSize.Min).testTag("row")) { KeptNote(Kept.Public) }
        }
        assertBeside()
        val row = compose.onNodeWithTag("row").getUnclippedBoundsInRoot()
        val drawn = maxOf(badge().bottom, sentence().bottom)
        // The badge's text node sits 2 dp inside its padding; a note stacked under the badge would be ~24 dp taller.
        assertTrue("row ${row.bottom} ends where the note does, $drawn", row.bottom - drawn < 4.dp)
    }

    /** With no width limit (a horizontal scroll) the note takes the width it needs beside the badge. */
    @Test
    fun withoutAWidthLimitTheSentenceStaysBesideTheBadgeOnOneLine() {
        show(1f) {
            Row(Modifier.horizontalScroll(rememberScrollState())) { KeptNote(Kept.Public) }
        }
        assertBeside()
        val badgeHeight = badge().let { it.bottom - it.top }
        val noteHeight = sentence().let { it.bottom - it.top }
        assertTrue("one line, $noteHeight tall beside the badge $badgeHeight", noteHeight <= badgeHeight + 2.dp)
    }
}
