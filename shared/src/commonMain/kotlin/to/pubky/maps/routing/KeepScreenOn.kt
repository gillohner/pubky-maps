package to.pubky.maps.routing

import androidx.compose.runtime.Composable

/** Keeps the screen on while this is composed: a route guiding the user is looked at, not touched. */
@Composable
internal expect fun KeepScreenOn()
