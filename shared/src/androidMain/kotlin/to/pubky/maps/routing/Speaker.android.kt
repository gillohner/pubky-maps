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
import java.util.concurrent.atomic.AtomicInteger

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
 * as navigation guidance (the system plays it on the navigation volume and Android Auto, and over a
 * call it waits): music and podcasts turn down while a sentence is said and come back after it
 * ("may duck" audio focus, as Google Maps). [onReady] is called on the main thread once the engine
 * speaks English; never when it cannot.
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

    /** Sentences given and not yet said or dropped; the audio focus is held while there are any. */
    private val pending = AtomicInteger()
    private val ids = AtomicInteger()
    private val engine: TextToSpeech = TextToSpeech(context.applicationContext) { status -> initialized(status) }

    private fun initialized(status: Int) {
        if (status != TextToSpeech.SUCCESS) return
        if (engine.setLanguage(Locale.US) < TextToSpeech.LANG_AVAILABLE) return
        engine.setAudioAttributes(attributes)
        engine.setOnUtteranceProgressListener(
            object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit

                override fun onDone(utteranceId: String?) = finished()

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) = finished()

                override fun onError(
                    utteranceId: String?,
                    errorCode: Int,
                ) = finished()

                override fun onStop(
                    utteranceId: String?,
                    interrupted: Boolean,
                ) = finished()
            },
        )
        onReady(this)
    }

    override fun say(text: String) {
        if (pending.getAndIncrement() == 0) audio.requestAudioFocus(focus)
        if (engine.speak(text, TextToSpeech.QUEUE_ADD, null, "guidance-${ids.incrementAndGet()}") != TextToSpeech.SUCCESS) finished()
    }

    override fun stop() {
        engine.stop()
        pending.set(0)
        audio.abandonAudioFocusRequest(focus)
    }

    fun shutdown() {
        stop()
        engine.shutdown()
    }

    private fun finished() {
        if (pending.updateAndGet { maxOf(0, it - 1) } == 0) audio.abandonAudioFocusRequest(focus)
    }
}
