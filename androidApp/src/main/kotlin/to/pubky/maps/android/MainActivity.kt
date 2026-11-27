package to.pubky.maps.android

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.withContext
import to.pubky.maps.App
import to.pubky.maps.AppInfo
import to.pubky.maps.BuildConfig
import to.pubky.maps.android.links.AndroidInstalledApps
import to.pubky.maps.android.links.IncomingIntent
import to.pubky.maps.android.links.incoming
import to.pubky.maps.android.links.openInApp
import to.pubky.maps.android.links.shareText
import to.pubky.maps.android.photos.AndroidPhotoInput
import to.pubky.maps.links.AppLinks
import to.pubky.maps.write.WriteUi

class MainActivity : ComponentActivity() {
    private val app get() = application as PubkyMapsApplication

    /** Map links other apps sent, until the app's navigation takes them. */
    private val incomingLinks = Channel<String>(Channel.BUFFERED)

    override fun onCreate(savedInstanceState: Bundle?) {
        // Before anything else, so MapLibre's native library loads while the activity is set up.
        app.prepareMapRuntime()
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val appInfo = AppInfo(versionName = BuildConfig.VERSION_NAME, versionCode = BuildConfig.VERSION_CODE.toLong())
        // A recreated activity (rotation) gets the intent it was started with again: handle it once.
        if (savedInstanceState == null) handleIntent(intent)
        val links =
            AppLinks(
                mapLinks = app.mapLinks,
                linkedPlaces = app.linkedPlaces,
                incoming = incomingLinks.receiveAsFlow(),
                share = { text -> shareText(text) },
                installedApps = AndroidInstalledApps(this),
                openApp = { action -> openInApp(action) },
            )
        setContent {
            App(
                appInfo = appInfo,
                settingsRepository = app.settingsRepository,
                loadLicences = ::readLicences,
                updates = app.updates,
                account = app.account,
                links = links,
                search = app.search,
                places = app.places,
                writes = WriteUi(app.writes, AndroidPhotoInput),
                offline = app.offlineMaps,
                // The map on screen is the start's "fully drawn" time (logcat "Fully drawn"), held
                // to a budget by the emulator smoke test.
                onMapDrawn = ::reportFullyDrawn,
                lastMapView = app.lastMapView,
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    // Ring's approval is awaited only while the app is on screen (Android cuts a backgrounded app's
    // network); the core keeps an approval that arrives meanwhile.
    override fun onStart() {
        super.onStart()
        app.account.controller.onForeground()
        // Writes that waited for a connection are tried again.
        app.writes.flush()
    }

    override fun onStop() {
        app.account.controller.onBackground()
        super.onStop()
    }

    /** Pubky Ring's return link, or a map link another app sent (a link or shared text). */
    private fun handleIntent(intent: Intent?) {
        when (val incoming = intent?.incoming()) {
            is IncomingIntent.RingReturn -> {
                app.account.onRingCallback(incoming.url)
            }

            is IncomingIntent.MapLink -> {
                incomingLinks.trySend(incoming.text)
            }

            null -> {}
        }
    }

    private suspend fun readLicences(): String = withContext(Dispatchers.IO) { readLicenceList(resources) }
}
