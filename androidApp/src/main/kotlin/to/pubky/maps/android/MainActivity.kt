package to.pubky.maps.android

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

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val appInfo = AppInfo(versionName = BuildConfig.VERSION_NAME, versionCode = BuildConfig.VERSION_CODE.toLong())
        val serverConfigRepository = (application as PubkyMapsApplication).serverConfigRepository
        setContent {
            App(
                appInfo = appInfo,
                serverConfigRepository = serverConfigRepository,
                loadLicences = ::readLicences,
            )
        }
    }

    /** The open-source licence list the AboutLibraries plugin generated at build time. */
    private suspend fun readLicences(): String =
        withContext(Dispatchers.IO) {
            resources.openRawResource(R.raw.aboutlibraries).bufferedReader().use { it.readText() }
        }
}
