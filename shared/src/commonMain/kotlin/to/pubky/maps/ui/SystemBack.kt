package to.pubky.maps.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState

/**
 * Takes the system back action (back button, back gesture) while [enabled] and its screen is at
 * least started. Of the enabled ones, the one composed last gets it; with none, back goes on to the
 * navigation or closes the app.
 *
 * The started check matters inside the NavHost: a page that is left stays composed during the
 * transition to the next one (about 0.7 s) but is no longer started, so a quick back on the new page
 * goes to the navigation instead of closing the old page's sheet or search.
 */
@Composable
fun SystemBack(
    enabled: Boolean = true,
    onBack: () -> Unit,
) {
    val state by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    NavigationBackHandler(
        state = rememberNavigationEventState(NavigationEventInfo.None),
        isBackEnabled = enabled && state.isAtLeast(Lifecycle.State.STARTED),
        onBackCompleted = onBack,
    )
}
