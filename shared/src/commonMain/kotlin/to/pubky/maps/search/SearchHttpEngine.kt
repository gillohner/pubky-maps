package to.pubky.maps.search

import io.ktor.client.engine.HttpClientEngineFactory

/** The platform's HTTP engine for search requests. */
internal expect fun searchHttpEngine(): HttpClientEngineFactory<*>
