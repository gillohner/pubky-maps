package to.pubky.maps.android

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import to.pubky.maps.config.ServerDefaults
import to.pubky.maps.map.AreaPickerUi
import to.pubky.maps.map.MapScreen
import to.pubky.maps.map.MapSearch
import to.pubky.maps.search.SearchActions
import to.pubky.maps.search.SearchState
import to.pubky.maps.ui.PubkyMapsTheme

/**
 * The area picker's top bar shows its whole instruction: at Android's largest font size (200 %) on a
 * phone the text wraps and the bar grows with it, so no line is cut, and the frame starts below the
 * text. Native graphics, so text is measured with real fonts.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp")
class AreaPickerLargeFontTest {
    @get:Rule
    val compose = createComposeRule()

    private val instruction = "Move the map to frame the area"
    private var frame: DpRect? = null

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
                        mapUnavailable = false,
                        onRetryMap = {},
                        onMyLocation = {},
                        onOpenSettings = {},
                        search = MapSearch(SearchState(), recents = emptyList(), userLocation = null, actions = SearchActions()),
                        areaPicker = AreaPickerUi(fits = true, onFrame = { frame = it }),
                    ) { Box(Modifier.fillMaxSize()) }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun layout(): TextLayoutResult {
        val layouts = mutableListOf<TextLayoutResult>()
        compose
            .onNodeWithText(instruction, useUnmergedTree = true)
            .fetchSemanticsNode()
            .config[SemanticsActions.GetTextLayoutResult]
            .action
            ?.invoke(layouts)
        return layouts.single()
    }

    private fun assertFrameBelowTheText() {
        val text = compose.onNodeWithText(instruction, useUnmergedTree = true).getUnclippedBoundsInRoot()
        val frame = checkNotNull(frame) { "the frame was measured" }
        assertTrue("frame top ${frame.top} is below the text's bottom ${text.bottom}", frame.top >= text.bottom)
    }

    @Test
    fun atLargeFontTheWholeInstructionShows() {
        show(fontScale = 2f)
        val text = layout()
        assertTrue("wraps at 200 %", text.lineCount > 1)
        // A line cut by the bar's height shows as a height overflow; hasVisualOverflow also counts a width
        // the layout reports wider than the text here, at the normal font too.
        assertFalse("no line is cut off", text.didOverflowHeight)
        assertFrameBelowTheText()
    }

    @Test
    fun atTheNormalFontTheInstructionIsOneLine() {
        show(fontScale = 1f)
        val text = layout()
        assertEquals(1, text.lineCount)
        assertFalse(text.didOverflowHeight)
        assertFrameBelowTheText()
    }
}
