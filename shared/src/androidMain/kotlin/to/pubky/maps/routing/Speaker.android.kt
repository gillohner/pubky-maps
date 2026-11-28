package to.pubky.maps.routing

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import java.util.Locale

@Composable
internal actual fun rememberSpeaker(): Speaker? {
    val context = LocalContext.current.applicationContext
    var ready by remember { mutableStateOf<Speaker?>(null) }
    DisposableEffect(context) {
        val speaker = AndroidSpeaker(context) { ready = it }
        onDispose {
            ready = null
            speaker.shutdown()
        }
    }
    return ready
}

/**
 * Guidance's voice through Android's speech engine, in US English as the routing server writes it,
 * as navigation guidance (the system plays it on its navigation volume): music and podcasts turn
 * down while sentences are said and come back once the last one is done ("may duck" audio focus, as
 * Google Maps). [onReady] is called on the main thread once the engine speaks English; never when it
 * cannot. The engine reports each sentence done on its own thread: the count of sentences waiting
 * and the audio focus change together under one lock, and reports of sentences from before a [stop]
 * are ignored, so they cannot give the sound back while a newer sentence is said.
 */
class AndroidSpeaker(
    context: Context,
    private val onReady: (Speaker) -> Unit,
) : Speaker {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val attributes =
        AudioAttributes
            .Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
    private val focus =
        AudioFocusRequest
            .Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attributes)
            .build()

    private val lock = Any()

    /** Sentences given since the last [stop] and not yet said or dropped; the audio focus is held while there are any. */
    private var pending = 0

    /** Counts the [stop]s: a sentence's id names the one it was given after. */
    private var round = 0
    private var ids = 0
    private val engine: TextToSpeech = TextToSpeech(context.applicationContext) { status -> initialized(status) }

    private fun initialized(status: Int) {
        if (status != TextToSpeech.SUCCESS) return
        if (engine.setLanguage(Locale.US) < TextToSpeech.LANG_AVAILABLE) return
        engine.setAudioAttributes(attributes)
        engine.setOnUtteranceProgressListener(
            object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit

                override fun onDone(utteranceId: String?) = finished(utteranceId)

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) = finished(utteranceId)

                override fun onError(
                    utteranceId: String?,
                    errorCode: Int,
                ) = finished(utteranceId)

                override fun onStop(
                    utteranceId: String?,
                    interrupted: Boolean,
                ) = finished(utteranceId)
            },
        )
        onReady(this)
    }

    override fun say(text: String) {
        val id =
            synchronized(lock) {
                if (pending++ == 0) audio.requestAudioFocus(focus)
                "guidance-$round-${++ids}"
            }
        if (engine.speak(text, TextToSpeech.QUEUE_ADD, null, id) != TextToSpeech.SUCCESS) finished(id)
    }

    override fun stop() {
        synchronized(lock) {
            round++
            pending = 0
            audio.abandonAudioFocusRequest(focus)
        }
        engine.stop()
    }

    fun shutdown() {
        stop()
        engine.shutdown()
    }

    private fun finished(id: String?) {
        synchronized(lock) {
            if (id?.startsWith("guidance-$round-") != true || pending == 0) return
            if (--pending == 0) audio.abandonAudioFocusRequest(focus)
        }
    }
}
