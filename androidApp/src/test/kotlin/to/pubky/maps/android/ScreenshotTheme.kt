package to.pubky.maps.android

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalCursorBlinkEnabled
import to.pubky.maps.ui.PubkyMapsTheme

/**
 * The app's theme for a screenshot taken by Roborazzi's `captureRoboImage { }`, with a focused text
 * field's cursor shown steadily. Its blink waits on the real clock (Compose's `delay(500)` runs on
 * kotlinx' own timer, not on Robolectric's paused looper), so a capture that takes more than half a
 * second on a busy machine would draw the field without its cursor and fail the comparison
 * (write-lists-new; search-typing is exposed the same way). Captures in a class with a Compose test
 * rule do not need it: the rule's clock drives that delay.
 */
@Composable
fun ScreenshotTheme(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalCursorBlinkEnabled provides false) { PubkyMapsTheme { content() } }
}
