package to.pubky.maps.android

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import to.pubky.maps.ui.PubkyMapsTheme
import to.pubky.maps.write.PhotoPage
import to.pubky.maps.write.PhotoPageState
import to.pubky.maps.write.PreparedPhoto

/**
 * The photo page's two buttons keep their labels on one line: side by side at the normal font, and at
 * Android's largest font size (200 %) on a phone "Take a photo" goes below "Choose a photo" instead of
 * being squeezed beside it into one syllable per line. Native graphics, so text is measured with real
 * fonts.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp")
class PhotoPageLargeFontTest {
    @get:Rule
    val compose = createComposeRule()

    private val empty = PhotoPageState(placeName = "Conditorei Schober")

    private val ready =
        empty.copy(
            photo = PreparedPhoto(ByteArray(0), "image/jpeg", 4, 3, keptLocation = false, removedLocation = true),
            alt = "Two cups of hot chocolate on a marble table",
        )

    private fun show(
        state: PhotoPageState,
        fontScale: Float,
    ) {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = fontScale)) {
                PubkyMapsTheme {
                    PhotoPage(state, onChoose = {}, onTake = {}, onAltChange = {}, onCaptionChange = {}, onPost = {}, onClose = {})
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

    private fun top(text: String) = compose.onNodeWithText(text, useUnmergedTree = true).getUnclippedBoundsInRoot().top

    @Test
    fun atLargeFontWithoutAPhotoBothLabelsStayOnOneLine() {
        show(empty, fontScale = 2f)
        assertEquals(1, lines("Choose a photo"))
        assertEquals(1, lines("Take a photo"))
        assertTrue("Take a photo goes below", top("Take a photo") > top("Choose a photo"))
    }

    @Test
    fun atLargeFontWithAPhotoBothLabelsStayOnOneLine() {
        show(ready, fontScale = 2f)
        assertEquals(1, lines("Choose another"))
        assertEquals(1, lines("Take a photo"))
        assertTrue("Take a photo goes below", top("Take a photo") > top("Choose another"))
    }

    @Test
    fun atTheNormalFontTheButtonsStaySideBySide() {
        show(empty, fontScale = 1f)
        assertEquals(top("Choose a photo"), top("Take a photo"))
        assertEquals(1, lines("Take a photo"))
    }
}
