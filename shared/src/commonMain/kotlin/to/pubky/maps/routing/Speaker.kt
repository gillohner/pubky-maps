package to.pubky.maps.routing

import androidx.compose.runtime.Composable

/** Says guidance aloud: each sentence after the ones before it; [stop] silences them all. */
interface Speaker {
    fun say(text: String)

    fun stop()
}

/**
 * The phone's speech engine while this is composed, once it is ready to speak English (the routing
 * server's sentences are in English); null before, and on a phone with no engine or no English voice,
 * where guidance has no voice to switch on.
 */
@Composable
internal expect fun rememberSpeaker(): Speaker?
