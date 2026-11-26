package to.pubky.maps.map

import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals

class MapTopControlsTest {
    private val safeTop = 24.dp

    // Inside the controls' box (12 dp under the safe area's top): the 48 dp search bar and a 12 dp margin.
    private val underSearchBar = 60.dp

    // Where the picker's frame starts at the normal font: 12 dp margin, its 48 dp bar, 12 dp margin.
    private val normalFrameTop = safeTop + 72.dp

    @Test
    fun withoutThePickerTheControlsStartUnderTheSearchBar() {
        assertEquals(underSearchBar, mapTopControlsPadding(pickerFrame = null, safeTop = safeTop))
    }

    @Test
    fun aPickerFrameLowerThanTheSearchBarMovesThemDownToIt() {
        // A large font: the picker's bar takes two lines, and its frame starts 32 dp lower.
        val frame = DpRect(24.dp, normalFrameTop + 32.dp, 388.dp, 700.dp)
        assertEquals(underSearchBar + 32.dp, mapTopControlsPadding(frame, safeTop))
    }

    @Test
    fun aFrameAtOrAboveTheSearchBarsPlaceLeavesThemThere() {
        assertEquals(underSearchBar, mapTopControlsPadding(DpRect(24.dp, normalFrameTop, 388.dp, 700.dp), safeTop))
        assertEquals(underSearchBar, mapTopControlsPadding(DpRect(24.dp, safeTop, 388.dp, 700.dp), safeTop))
    }
}
