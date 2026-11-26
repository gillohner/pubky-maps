package to.pubky.maps.config

/** Where data of one kind is kept. [key] is how the settings file stores it and never changes. */
enum class StorageChoice(
    val key: String,
) {
    /** On this phone only; the app never uploads it. */
    PhoneOnly("phone"),

    /** On the user's homeserver, outside `/pub`: not public, but readable by the homeserver operator. */
    HomeserverPrivate("homeserver_private"),

    /** On the user's homeserver under `/pub`: anyone can read it. */
    Public("public"),
    ;

    companion object {
        fun fromKey(key: String): StorageChoice? = entries.firstOrNull { it.key == key }
    }
}

/**
 * A kind of data the user makes, with the places it can be kept and where it goes until the user
 * chooses. The reasons for each default are in the project's decision log; in short: nothing is
 * public until the user says so, and data that shows where someone lives or goes stays on the phone.
 */
enum class DataKind(
    val key: String,
    val choices: List<StorageChoice>,
    val default: StorageChoice,
) {
    SavedPlaces("saved_places", listOf(StorageChoice.PhoneOnly, StorageChoice.HomeserverPrivate), StorageChoice.HomeserverPrivate),
    HomeAndWork("home_and_work", listOf(StorageChoice.PhoneOnly, StorageChoice.HomeserverPrivate), StorageChoice.PhoneOnly),
    Lists("lists", StorageChoice.entries, StorageChoice.HomeserverPrivate),
    CheckIns("check_ins", StorageChoice.entries, StorageChoice.PhoneOnly),
    RecordedTracks("recorded_tracks", StorageChoice.entries, StorageChoice.PhoneOnly),
    LocationHistory("location_history", listOf(StorageChoice.PhoneOnly, StorageChoice.HomeserverPrivate), StorageChoice.PhoneOnly),
    PhotoLocations("photo_locations", listOf(StorageChoice.PhoneOnly, StorageChoice.Public), StorageChoice.PhoneOnly),
    Tags("tags", listOf(StorageChoice.Public), StorageChoice.Public),
    ReviewsAndPosts("reviews_and_posts", listOf(StorageChoice.Public), StorageChoice.Public),
    ;

    init {
        require(default in choices) { "$this: the default must be one of its choices" }
    }

    /** The most private place this kind can be kept. */
    val mostPrivate: StorageChoice get() = choices.minBy { it.ordinal }

    /** True when the user can choose; false for kinds that are public by nature. */
    val hasChoice: Boolean get() = choices.size > 1

    companion object {
        fun fromKey(key: String): DataKind? = entries.firstOrNull { it.key == key }
    }
}

/** An app the place page's "Open in" menu can hand a place to, switched on or off in settings. */
enum class OpenInApp(
    val key: String,
    val onByDefault: Boolean,
) {
    GoogleMaps("google_maps", true),

    // Off: Android has no Apple Maps app, so the link only opens Apple's web map in a browser.
    AppleMaps("apple_maps", false),
    Waze("waze", true),
    OrganicMaps("organic_maps", true),
    CoMaps("comaps", true),
    OsmAnd("osmand", true),
    HereWeGo("here_wego", true),
    AnyGeoApp("geo", true),
    ;

    companion object {
        fun fromKey(key: String): OpenInApp? = entries.firstOrNull { it.key == key }
    }
}

/**
 * Everything the user can set. Only the user's own changes are kept ([serverOverrides],
 * [storageChoices], [openInChoices]); everything else follows the defaults, so a new app version's
 * defaults reach users who never changed that setting. A storage choice, once made, stays even when
 * it equals today's default, so a later default can never make the user's data more public.
 */
data class AppSettings(
    val serverOverrides: Map<Service, List<ServerEntry>> = emptyMap(),
    val storageChoices: Map<DataKind, StorageChoice> = emptyMap(),
    val openInChoices: Map<OpenInApp, Boolean> = emptyMap(),
    val followLocalRules: Boolean = true,
) {
    companion object {
        /**
         * What the app starts from when the settings file was lost (unreadable): the defaults, except
         * that every kind of data is kept as privately as it can be, since what the user had chosen is
         * unknown and must not turn into an upload.
         */
        fun afterLostFile(): AppSettings =
            AppSettings(storageChoices = DataKind.entries.filter { it.hasChoice }.associateWith { it.mostPrivate })
    }

    init {
        require(serverOverrides.values.none { it.isEmpty() }) { "A server list cannot be empty" }
        require(storageChoices.all { (kind, choice) -> choice in kind.choices }) { "A storage choice the kind does not offer" }
    }

    /** The server list of every service: the user's own lists, the defaults for the rest. */
    val servers: ServerLists by lazy {
        ServerLists(Service.entries.associateWith { serverOverrides[it] ?: ServerDefaults.lists[it] })
    }

    fun isDefaultServerList(service: Service): Boolean = service !in serverOverrides

    fun storage(kind: DataKind): StorageChoice = storageChoices[kind] ?: kind.default

    fun withStorage(
        kind: DataKind,
        choice: StorageChoice,
    ): AppSettings {
        require(choice in kind.choices) { "$kind cannot be kept as $choice" }
        return copy(storageChoices = storageChoices + (kind to choice))
    }

    fun isOpenInEnabled(app: OpenInApp): Boolean = openInChoices[app] ?: app.onByDefault

    fun withOpenIn(
        app: OpenInApp,
        enabled: Boolean,
    ): AppSettings = copy(openInChoices = if (enabled == app.onByDefault) openInChoices - app else openInChoices + (app to enabled))

    // Server list edits. A list that ends up equal to the defaults stops being an override.

    /** [settings] after adding a server, or why the address was refused. */
    sealed interface AddServerResult {
        data class Added(
            val settings: AppSettings,
        ) : AddServerResult

        data class Refused(
            val reason: String,
        ) : AddServerResult
    }

    /**
     * Adds a server at the end of [service]'s list. [name] may be blank: the host is shown then. A new
     * map style credits its own host and OpenStreetMap: the app cannot know what data it draws, and
     * nearly every web map style draws OSM data, whose licence requires the credit.
     */
    fun withServerAdded(
        service: Service,
        url: String,
        name: String = "",
    ): AddServerResult {
        val parsed = ServerUrl.parse(url)
        if (parsed is ServerUrl.Invalid) return AddServerResult.Refused(parsed.reason)
        parsed as ServerUrl.Valid
        val list = servers[service]
        if (list.any { ServerUrl.parse(it.url) == parsed }) return AddServerResult.Refused("This server is already in the list.")
        val shownName = name.trim().ifEmpty { parsed.host }
        // A search server is credited like a map style: Photon servers index OpenStreetMap.
        val attribution =
            if (service.isMapStyle || service == Service.Photon) {
                listOf(AttributionLink(shownName, parsed.origin), ServerDefaults.openStreetMap)
            } else {
                emptyList()
            }
        return AddServerResult.Added(withServerList(service, list + ServerEntry(shownName, parsed.url, attribution)))
    }

    /**
     * Moves the server with address [url] by [steps] places (negative: towards the top) in [service]'s
     * list. Servers are named by address, not position, so a change made from an older screen state
     * (a double tap) never moves another server; a move past either end or of a server no longer in
     * the list changes nothing.
     */
    fun withServerMoved(
        service: Service,
        url: String,
        steps: Int,
    ): AppSettings {
        val list = servers[service].toMutableList()
        val from = list.indexOfFirst { it.url == url }
        val to = from + steps
        if (from < 0 || to !in list.indices) return this
        list.add(to, list.removeAt(from))
        return withServerList(service, list)
    }

    /**
     * Removes the server with address [url] from [service]'s list. The last server stays (a list is
     * never empty), and a server no longer in the list changes nothing.
     */
    fun withServerRemoved(
        service: Service,
        url: String,
    ): AppSettings {
        val list = servers[service]
        if (list.size <= 1 || list.none { it.url == url }) return this
        return withServerList(service, list.filterNot { it.url == url })
    }

    /** Puts [service]'s list back to the defaults. */
    fun withServersReset(service: Service): AppSettings = copy(serverOverrides = serverOverrides - service)

    private fun withServerList(
        service: Service,
        list: List<ServerEntry>,
    ): AppSettings =
        if (list == ServerDefaults.lists[service]) {
            withServersReset(service)
        } else {
            copy(serverOverrides = serverOverrides + (service to list))
        }
}

val Service.isMapStyle: Boolean get() = this == Service.MapStyleLight || this == Service.MapStyleDark
