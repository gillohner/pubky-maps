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

/** One server of a [Service]: its address, a name to show, and (for map styles) the credits for what it draws. */
data class ServerEntry(
    val name: String,
    val url: String,
    val attribution: List<AttributionLink> = emptyList(),
)

/**
 * Every kind of server the app talks to. Each one has its own ordered list of servers: the first is
 * used, the next ones take over when it fails. [key] names the list in the settings file and never
 * changes.
 */
enum class Service(
    val key: String,
) {
    MapStyleLight("map_style_light"),
    MapStyleDark("map_style_dark"),
    Photon("photon"),
    Pinakes("pinakes"),
    Nexus("nexus"),
    PkarrRelay("pkarr_relay"),
    AuthRelay("auth_relay"),
    ;

    companion object {
        fun fromKey(key: String): Service? = entries.firstOrNull { it.key == key }
    }
}

/** The server list of every [Service], each one non-empty and in the order the app tries them. */
data class ServerLists(
    private val lists: Map<Service, List<ServerEntry>>,
) {
    init {
        for (service in Service.entries) {
            require(!lists[service].isNullOrEmpty()) { "At least one server is needed for $service" }
        }
    }

    operator fun get(service: Service): List<ServerEntry> = lists.getValue(service)

    /** The map styles for the light or the dark theme. */
    fun mapStyles(darkTheme: Boolean): List<ServerEntry> = get(if (darkTheme) Service.MapStyleDark else Service.MapStyleLight)

    /**
     * Every credit of every configured map style, each once, in list order; the OpenStreetMap credit
     * is always there, also when the user replaced every default style.
     */
    fun allAttribution(): List<AttributionLink> =
        ((get(Service.MapStyleLight) + get(Service.MapStyleDark)).flatMap { it.attribution } + ServerDefaults.openStreetMap).distinct()
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

/**
 * The servers that are fixed per build rather than kept in the user's settings ([AppSettings]): the
 * signed update feeds. The update probe's build replaces its channel's feed for the process.
 */
data class ServerConfig(
    val updateFeeds: UpdateFeedServers,
)

/** Holds the current [ServerConfig] for the life of the process. */
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
