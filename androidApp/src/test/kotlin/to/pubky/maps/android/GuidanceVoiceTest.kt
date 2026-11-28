package to.pubky.maps.android

import android.media.AudioAttributes
import android.media.AudioManager
import android.speech.tts.TextToSpeech
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowTextToSpeech
import to.pubky.maps.links.GeoPoint
import to.pubky.maps.routing.AndroidSpeaker
import to.pubky.maps.routing.Directions
import to.pubky.maps.routing.GuidanceBar
import to.pubky.maps.routing.GuidanceUi
import to.pubky.maps.routing.Route
import to.pubky.maps.routing.RouteStatus
import to.pubky.maps.routing.Speaker
import to.pubky.maps.routing.TravelMode
import to.pubky.maps.ui.PubkyMapsTheme
import java.util.Locale

/**
 * Guidance's voice: the speech engine says each prompt in turn as navigation guidance, music turned
 * down meanwhile; a phone that cannot speak English gets no voice; the bar's switch turns it off and on.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp")
class GuidanceVoiceTest {
    @get:Rule
    val compose = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<android.app.Application>()
    private val audio = shadowOf(context.getSystemService(AudioManager::class.java))

    private fun speaker(
        englishVoice: Boolean = true,
        status: Int = TextToSpeech.SUCCESS,
    ): Pair<Speaker?, ShadowTextToSpeech> {
        var ready: Speaker? = null
        AndroidSpeaker(context) { ready = it }
        val engine = shadowOf(ShadowTextToSpeech.getLastTextToSpeechInstance())
        if (englishVoice) ShadowTextToSpeech.addLanguageAvailability(Locale.US)
        engine.onInitListener.onInit(status)
        return ready to engine
    }

    @Test
    fun promptsAreSaidOneAfterTheOtherAsNavigationWhileOtherSoundIsTurnedDown() {
        val (speaker, engine) = speaker()
        assertNotNull(speaker)
        speaker!!.say("Drive north.")
        speaker.say("In 300 meters, turn right onto Museumstrasse.")
        assertEquals(listOf("Drive north.", "In 300 meters, turn right onto Museumstrasse."), engine.spokenTextList)
        assertEquals(TextToSpeech.QUEUE_ADD, engine.queueMode)
        assertEquals(Locale.US, engine.currentLanguage)
        val focus = audio.lastAudioFocusRequest.audioFocusRequest
        assertEquals(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK, focus.focusGain)
        assertEquals(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE, focus.audioAttributes.usage)
        // The other sound comes back once both are said, not between them.
        engine.utteranceProgressListener.onDone("guidance-1")
        assertNull(audio.lastAbandonedAudioFocusRequest)
        engine.utteranceProgressListener.onDone("guidance-2")
        assertEquals(focus, audio.lastAbandonedAudioFocusRequest)
    }

    @Test
    fun aPhoneWithoutAnEnglishVoiceOrASpeechEngineHasNoVoice() {
        assertNull(speaker(englishVoice = false).first)
        assertNull(speaker(status = TextToSpeech.ERROR).first)
    }

    @Test
    fun stoppingSilencesThePromptsAndGivesTheSoundBack() {
        val (speaker, engine) = speaker()
        speaker!!.say("Turn left onto Rudolf-Brun-Brücke.")
        speaker.stop()
        assertNotNull(audio.lastAbandonedAudioFocusRequest)
        // A sentence said after it takes the sound again.
        speaker.say("Turn right onto Seilergraben.")
        assertEquals("Turn right onto Seilergraben.", engine.lastSpokenText)
    }

    private val hb = GeoPoint(47.3779, 8.5403)
    private val kunsthaus = GeoPoint(47.3702, 8.5486)

    private fun guidance(
        voice: Boolean?,
        onVoiceChange: (Boolean) -> Unit,
    ) = GuidanceUi(
        directions =
            Directions(
                "osm:relation/3768127",
                kunsthaus,
                TravelMode.Car,
                hb,
                RouteStatus.Found(Route(listOf(hb, kunsthaus), seconds = 290.0, meters = 1401.0), "https://v.test"),
                guiding = true,
            ),
        placeName = "Kunsthaus Zürich",
        arrival = null,
        following = true,
        onRecenter = {},
        onRouteAgain = {},
        onEnd = {},
        credits = emptyList(),
        voice = voice,
        onVoiceChange = onVoiceChange,
    )

    @Test
    fun theBarsSwitchTurnsTheVoiceOffAndOn() {
        var voice by mutableStateOf(true)
        compose.setContent { PubkyMapsTheme(darkTheme = false) { GuidanceBar(guidance(voice) { voice = it }) } }
        compose
            .onNodeWithContentDescription("Voice guidance")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch))
            .assertIsOn()
            .performClick()
        assertEquals(false, voice)
        compose.onNodeWithContentDescription("Voice guidance").assertIsOff().performClick()
        assertEquals(true, voice)
    }

    @Test
    fun noSwitchWhereThePhoneCannotSpeak() {
        compose.setContent { PubkyMapsTheme(darkTheme = false) { GuidanceBar(guidance(null) {}) } }
        compose.onNodeWithContentDescription("Voice guidance").assertDoesNotExist()
    }
}
