package to.pubky.maps.search

import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.darwin.Darwin

internal actual fun searchHttpEngine(): HttpClientEngineFactory<*> = Darwin
