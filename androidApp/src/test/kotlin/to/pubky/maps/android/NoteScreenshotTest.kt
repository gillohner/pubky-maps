package to.pubky.maps.android

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RoborazziComposeOptions
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.fontScale
import com.github.takahirom.roborazzi.size
import com.github.takahirom.roborazzi.uiMode
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import to.pubky.maps.links.GeoPoint
import to.pubky.maps.links.OsmId
import to.pubky.maps.links.OsmType
import to.pubky.maps.osm.NoteAnswer
import to.pubky.maps.osm.NotePage
import to.pubky.maps.osm.NoteSend
import to.pubky.maps.osm.NoteTarget

/**
 * Screenshots of the note page that reports a map problem to OpenStreetMap (phone 412x915 dp, tablet
 * 1280x800 dp, light and dark): written, not sent, sent, waiting for a connection, and at the largest font.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalRoborazziApi::class)
class NoteScreenshotTest {
    private enum class Device(
        val widthDp: Int,
        val heightDp: Int,
    ) {
        Phone(412, 915),
        Tablet(1280, 800),
    }

    private fun capture(
        name: String,
        device: Device,
        dark: Boolean,
        largeFont: Boolean = false,
        content: @Composable () -> Unit,
    ) {
        val theme = if (dark) "dark" else "light"
        val variant = if (largeFont) "-large-font" else ""
        captureRoboImage(
            filePath = "screenshots/$name$variant-${device.name.lowercase()}-$theme.png",
            roborazziComposeOptions =
                RoborazziComposeOptions {
                    size(widthDp = device.widthDp, heightDp = device.heightDp)
                    uiMode(if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO)
                    if (largeFont) fontScale(2f)
                },
        ) {
            ScreenshotTheme { content() }
        }
    }

    private val schober = NoteTarget(GeoPoint(47.3722, 8.5446), "Conditorei Schober", OsmId(OsmType.Node, 270803095))

    @Composable
    private fun Written(send: NoteSend? = null) {
        NotePage(
            target = schober,
            text = "Closed for good since October, the shop is empty.",
            onTextChange = {},
            send = send,
            onSend = {},
            onClose = {},
        )
    }

    @Test
    fun writtenPhoneLight() = capture("note-written", Device.Phone, dark = false) { Written() }

    @Test
    fun writtenPhoneDark() = capture("note-written", Device.Phone, dark = true) { Written() }

    @Test
    fun writtenTabletLight() = capture("note-written", Device.Tablet, dark = false) { Written() }

    @Test
    fun writtenTabletDark() = capture("note-written", Device.Tablet, dark = true) { Written() }

    @Test
    fun writtenLargeFontPhoneLight() = capture("note-written", Device.Phone, dark = false, largeFont = true) { Written() }

    @Test
    fun notSentPhoneLight() = capture("note-not-sent", Device.Phone, dark = false) { Written(NoteSend.NotSent(NoteAnswer.Unreachable)) }

    @Test
    fun notSentPhoneDark() = capture("note-not-sent", Device.Phone, dark = true) { Written(NoteSend.NotSent(NoteAnswer.Unreachable)) }

    @Test
    fun sentPhoneLight() = capture("note-sent", Device.Phone, dark = false) { Written(NoteSend.Sent) }

    @Test
    fun sentPhoneDark() = capture("note-sent", Device.Phone, dark = true) { Written(NoteSend.Sent) }

    @Test
    fun sentTabletLight() = capture("note-sent", Device.Tablet, dark = false) { Written(NoteSend.Sent) }

    @Test
    fun sentTabletDark() = capture("note-sent", Device.Tablet, dark = true) { Written(NoteSend.Sent) }

    @Test
    fun waitingPhoneLight() = capture("note-waiting", Device.Phone, dark = false) { Written(NoteSend.Waiting) }

    @Test
    fun waitingPhoneDark() = capture("note-waiting", Device.Phone, dark = true) { Written(NoteSend.Waiting) }

    @Test
    fun waitingTabletLight() = capture("note-waiting", Device.Tablet, dark = false) { Written(NoteSend.Waiting) }

    @Test
    fun waitingTabletDark() = capture("note-waiting", Device.Tablet, dark = true) { Written(NoteSend.Waiting) }

    @Test
    fun waitingLargeFontPhoneLight() = capture("note-waiting", Device.Phone, dark = false, largeFont = true) { Written(NoteSend.Waiting) }

    /** A dropped pin: no name, so the page names the point. */
    @Test
    fun pointPhoneLight() =
        capture("note-point", Device.Phone, dark = false) {
            NotePage(NoteTarget(GeoPoint(47.37689, 8.54169)), "", onTextChange = {}, send = null, onSend = {}, onClose = {})
        }
}
