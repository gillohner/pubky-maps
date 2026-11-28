package to.pubky.maps.android

import android.graphics.Bitmap
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.core.view.drawToBitmap
import androidx.test.core.app.ActivityScenario
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A screenshot of a focused text field does not depend on how long the capture takes: the same
 * activity as Roborazzi's `captureRoboImage { }` (no Compose test rule, so the cursor's blink runs on
 * the real clock) drawn now and again after more than half a second of real time gives the same
 * picture, and that picture has the cursor in it.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h200dp-notnight-mdpi")
class ScreenshotThemeTest {
    @Test
    fun aFocusedFieldLooksTheSameAfterHalfASecondOfRealTime() {
        var focused = false
        var cursorHidden by mutableStateOf(false)
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    ScreenshotTheme {
                        val title = remember { FocusRequester() }
                        LaunchedEffect(Unit) { title.requestFocus() }
                        OutlinedTextField(
                            value = "Cake",
                            onValueChange = {},
                            label = { Text("Title") },
                            colors =
                                OutlinedTextFieldDefaults.colors(
                                    cursorColor = if (cursorHidden) Color.Transparent else MaterialTheme.colorScheme.primary,
                                ),
                            modifier = Modifier.focusRequester(title).onFocusChanged { focused = it.isFocused },
                        )
                    }
                }
            }
            val first = draw(scenario)
            assertTrue("the field has the focus", focused)
            // Longer than one blink (500 ms), as a capture on a busy machine can take.
            Thread.sleep(700)
            val later = draw(scenario)
            assertTrue("the cursor blinked off while the capture waited", first.sameAs(later))
            cursorHidden = true
            assertFalse("no cursor was drawn, so the pictures could not tell a blink", first.sameAs(draw(scenario)))
        }
    }

    private fun draw(scenario: ActivityScenario<ComponentActivity>): Bitmap {
        shadowOf(Looper.getMainLooper()).idle()
        lateinit var bitmap: Bitmap
        scenario.onActivity { bitmap = it.window.decorView.drawToBitmap() }
        return bitmap
    }
}
