package to.pubky.maps.search

import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.android.Android

// Android's own HttpURLConnection: no extra HTTP stack in the APK.
internal actual fun searchHttpEngine(): HttpClientEngineFactory<*> = Android
