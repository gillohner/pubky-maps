package to.pubky.maps.routing

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView

/** The view's keepScreenOn: the window's FLAG_KEEP_SCREEN_ON while the view is shown, no permission needed. */
@Composable
internal actual fun KeepScreenOn() {
    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
}
