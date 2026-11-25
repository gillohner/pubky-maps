package to.pubky.maps.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalUriHandler

/**
 * Opens a web link in another app. Does nothing when no app can open it (a phone without a browser
 * would otherwise crash the app).
 */
@Composable
fun rememberSafeLinkOpener(): (String) -> Unit {
    val uriHandler = LocalUriHandler.current
    return remember(uriHandler) { { url -> runCatching { uriHandler.openUri(url) } } }
}
