package to.pubky.maps.settings

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import com.mikepenz.aboutlibraries.Libs
import com.mikepenz.aboutlibraries.ui.compose.m3.LibrariesContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import to.pubky.maps.ui.AppIcons

/** Reads the licence list with [loadLicences] off the main thread, then shows [LicencesScreen]. */
@Composable
fun LicencesRoute(
    loadLicences: suspend () -> String,
    onBack: () -> Unit,
) {
    val libraries by produceState<Libs?>(initialValue = null, loadLicences) {
        // A missing or unreadable list shows an empty screen instead of crashing the app.
        val json = runCatching { loadLicences() }.getOrDefault("")
        value = withContext(Dispatchers.Default) { parseLicences(json) }
    }
    LicencesScreen(libraries = libraries, onBack = onBack)
}

/** The AboutLibraries list in [json]; an empty list when it cannot be read. */
fun parseLicences(json: String): Libs = Libs.Builder().withJson(json).build()

/** Every library bundled in the app, with its licence; a tap shows the licence text. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LicencesScreen(
    libraries: Libs?,
    onBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Open-source licences") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(AppIcons.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        // The list's website and licence chips open links through LocalUriHandler; without an app
        // that can open them (no browser) that would throw and crash the app.
        val uriHandler = LocalUriHandler.current
        val safeUriHandler = remember(uriHandler) { SafeUriHandler(uriHandler) }
        CompositionLocalProvider(LocalUriHandler provides safeUriHandler) {
            LibrariesContainer(
                libraries = libraries,
                modifier = Modifier.fillMaxSize(),
                contentPadding = padding,
            )
        }
    }
}

/** Opens links like [delegate] and does nothing when no app can open them. */
private class SafeUriHandler(
    private val delegate: UriHandler,
) : UriHandler {
    override fun openUri(uri: String) {
        runCatching { delegate.openUri(uri) }
    }
}
