package to.pubky.maps.android

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import to.pubky.maps.App
import to.pubky.maps.AppInfo
import to.pubky.maps.BuildConfig
import to.pubky.maps.R

/** Pubky Ring's return links: `pubkymaps://auth/{success|error|cancel}?nonce=…`. */
private const val RING_RETURN_SCHEME = "pubkymaps"

class MainActivity : ComponentActivity() {
    private val app get() = application as PubkyMapsApplication

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val appInfo = AppInfo(versionName = BuildConfig.VERSION_NAME, versionCode = BuildConfig.VERSION_CODE.toLong())
        // A recreated activity (rotation) gets the intent it was started with again: handle it once.
        if (savedInstanceState == null) handleRingReturn(intent)
        setContent {
            App(
                appInfo = appInfo,
                settingsRepository = app.settingsRepository,
                loadLicences = ::readLicences,
                updates = app.updates,
                account = app.account,
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleRingReturn(intent)
    }

    // Ring's approval is awaited only while the app is on screen (Android cuts a backgrounded app's
    // network); the core keeps an approval that arrives meanwhile.
    override fun onStart() {
        super.onStart()
        app.account.controller.onForeground()
    }

    override fun onStop() {
        app.account.controller.onBackground()
        super.onStop()
    }

    private fun handleRingReturn(intent: Intent?) {
        // Reopened from Recents, the task replays the link that started it: that one was handled.
        if (intent == null || intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return
        val data = intent.data ?: return
        if (data.scheme == RING_RETURN_SCHEME) app.account.onRingCallback(data.toString())
    }

    /** The open-source licence list the AboutLibraries plugin generated at build time. */
    private suspend fun readLicences(): String =
        withContext(Dispatchers.IO) {
            resources.openRawResource(R.raw.aboutlibraries).bufferedReader().use { it.readText() }
        }
}
