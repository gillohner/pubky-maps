package to.pubky.maps.routing

import androidx.compose.runtime.Composable

/** No voice on iOS yet: guidance there shows no voice button. */
@Composable
internal actual fun rememberSpeaker(): Speaker? = null
