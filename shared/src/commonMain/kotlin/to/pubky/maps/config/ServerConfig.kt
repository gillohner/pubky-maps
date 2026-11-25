package to.pubky.maps.config

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import to.pubky.maps.update.UpdateChannel

/** A credit the map must show for the data or tiles it draws. */
data class AttributionLink(
    val label: String,
    val url: String,
)

/** One map style server: the style document's address and the credits for what it draws. */
data class MapStyleSource(
    val name: String,
    val styleUrl: String,
    val attribution: List<AttributionLink>,
)

/** Map style servers per theme, in the order the map tries them. */
data class MapStyleServers(
    val light: List<MapStyleSource>,
    val dark: List<MapStyleSource>,
) {
    init {
        require(light.isNotEmpty()) { "At least one light map style is needed" }
        require(dark.isNotEmpty()) { "At least one dark map style is needed" }
    }

    fun forTheme(darkTheme: Boolean): List<MapStyleSource> = if (darkTheme) dark else light

    /** Every credit of every configured style, each once, in list order. */
    fun allAttribution(): List<AttributionLink> = (light + dark).flatMap { it.attribution }.distinct()
}

/** Update feed addresses per channel, in the order the updater tries them. */
data class UpdateFeedServers(
    val edge: List<String>,
    val stable: List<String>,
    val dev: List<String>,
) {
    fun forChannel(channel: UpdateChannel): List<String> =
        when (channel) {
            UpdateChannel.Edge -> edge
            UpdateChannel.Stable -> stable
            UpdateChannel.Dev -> dev
        }

    /** The same servers with [channel]'s list replaced by [urls]. */
    fun withChannel(
        channel: UpdateChannel,
        urls: List<String>,
    ): UpdateFeedServers =
        when (channel) {
            UpdateChannel.Edge -> copy(edge = urls)
            UpdateChannel.Stable -> copy(stable = urls)
            UpdateChannel.Dev -> copy(dev = urls)
        }
}

/** Every server address the app uses. Each one is an ordered list with fallbacks. */
data class ServerConfig(
    val mapStyles: MapStyleServers,
    val updateFeeds: UpdateFeedServers,
)

/** Holds the current [ServerConfig]; settings edit it through [update]. */
interface ServerConfigRepository {
    val config: StateFlow<ServerConfig>

    fun update(transform: (ServerConfig) -> ServerConfig)
}

/** A [ServerConfigRepository] that keeps the configuration for the life of the process. */
class InMemoryServerConfigRepository(
    initial: ServerConfig = ServerDefaults.config,
) : ServerConfigRepository {
    private val state = MutableStateFlow(initial)

    override val config: StateFlow<ServerConfig> = state.asStateFlow()

    override fun update(transform: (ServerConfig) -> ServerConfig) {
        state.update(transform)
    }
}
