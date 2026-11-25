package to.pubky.maps.config

/** The servers the app starts with. Every default server address lives here. */
object ServerDefaults {
    private val openStreetMap =
        AttributionLink("© OpenStreetMap contributors", "https://www.openstreetmap.org/copyright")
    private val openFreeMap = AttributionLink("OpenFreeMap", "https://openfreemap.org")
    private val openMapTiles = AttributionLink("© OpenMapTiles", "https://www.openmaptiles.org/")
    private val versaTiles = AttributionLink("VersaTiles", "https://versatiles.org")

    private val openFreeMapCredits = listOf(openFreeMap, openMapTiles, openStreetMap)
    private val versaTilesCredits = listOf(versaTiles, openStreetMap)

    val lists =
        ServerLists(
            mapOf(
                Service.MapStyleLight to
                    listOf(
                        ServerEntry(
                            name = "OpenFreeMap Liberty",
                            url = "https://tiles.openfreemap.org/styles/liberty",
                            attribution = openFreeMapCredits,
                        ),
                        ServerEntry(
                            name = "VersaTiles Colorful",
                            url = "https://tiles.versatiles.org/assets/styles/colorful/style.json",
                            attribution = versaTilesCredits,
                        ),
                    ),
                Service.MapStyleDark to
                    listOf(
                        ServerEntry(
                            name = "OpenFreeMap Dark",
                            url = "https://tiles.openfreemap.org/styles/dark",
                            attribution = openFreeMapCredits,
                        ),
                        ServerEntry(
                            name = "VersaTiles Colorful Dark",
                            url = "https://tiles.versatiles.org/assets/styles/colorful-dark/style.json",
                            attribution = versaTilesCredits,
                        ),
                    ),
                Service.Photon to listOf(ServerEntry("Photon by Komoot", "https://photon.komoot.io")),
                Service.Pinakes to
                    listOf(ServerEntry("Pinakes (development)", "https://8092--main--pinakes--gil.coder.riginode.xyz")),
                Service.Nexus to
                    listOf(ServerEntry("Nexus (development)", "https://8080--main--pubky-staging--gil.coder.riginode.xyz")),
                // The Pubky SDK's default relays (pkarr::DEFAULT_RELAYS).
                Service.PkarrRelay to
                    listOf(
                        ServerEntry("Pubky Pkarr relay", "https://pkarr.pubky.app"),
                        ServerEntry("Pubky Pkarr relay (pubky.org)", "https://pkarr.pubky.org"),
                    ),
                // The Pubky SDK's default sign-in relay (DEFAULT_HTTP_RELAY_INBOX).
                Service.AuthRelay to listOf(ServerEntry("Pubky HTTP relay", "https://httprelay.pubky.app/inbox")),
            ),
        )

    // The signed update feeds are release assets on GitHub (scripts/release/update-feed.py). Any
    // mirror can serve the same files: the app checks the signature, not the server.
    private const val RELEASES = "https://github.com/gillohner/pubky-maps/releases"

    val updateFeeds =
        UpdateFeedServers(
            edge = listOf("$RELEASES/download/edge/update-edge.json"),
            stable = listOf("$RELEASES/latest/download/update.json"),
            dev = listOf("$RELEASES/download/dev/update-dev.json"),
        )

    val config = ServerConfig(updateFeeds = updateFeeds)
}
