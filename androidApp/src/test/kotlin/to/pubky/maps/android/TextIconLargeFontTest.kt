package to.pubky.maps.android

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onParent
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import to.pubky.maps.ui.PubkyMapsTheme
import to.pubky.maps.write.Kept
import to.pubky.maps.write.KeptBadge

/**
 * The icon of a "Public"/"Private"/"On this phone" badge grows with the font as its 14 sp label does:
 * 14 dp at the normal font, 26 dp at Android's largest font size (200 %, whose non-linear curve draws
 * 14 sp at 26 dp), where a 14 dp icon beside "Public" reads as a dot.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp")
class TextIconLargeFontTest {
    @get:Rule
    val compose = createComposeRule()

    private fun show(
        fontScale: Float,
        content: @Composable () -> Unit,
    ) {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = fontScale)) {
                PubkyMapsTheme { content() }
            }
        }
    }

    /** The room before the badge's label: its 8 dp padding, the icon and the 4 dp gap; the icon draws no node of its own. */
    private fun badgeIconWidth(label: String): Dp {
        val text = compose.onNodeWithText(label, useUnmergedTree = true)
        val badge = text.onParent().getUnclippedBoundsInRoot()
        return text.getUnclippedBoundsInRoot().left - badge.left - 8.dp - 4.dp
    }

    private fun assertNear(
        expected: Dp,
        actual: Dp,
    ) = assertEquals(expected.value, actual.value, 0.5f)

    @Test
    fun atTheNormalFontTheBadgeIconIs14Dp() {
        show(1f) { KeptBadge(Kept.Public) }
        assertNear(14.dp, badgeIconWidth("Public"))
    }

    @Test
    fun atLargeFontTheBadgeIconGrowsWithItsLabel() {
        show(2f) { KeptBadge(Kept.Public) }
        assertNear(26.dp, badgeIconWidth("Public"))
    }

    @Test
    fun atLargeFontEveryKindOfBadgeIconGrows() {
        show(2f) {
            Column {
                KeptBadge(Kept.Private)
                KeptBadge(Kept.Phone)
            }
        }
        assertNear(26.dp, badgeIconWidth("Private"))
        assertNear(26.dp, badgeIconWidth("On this phone"))
    }
}
