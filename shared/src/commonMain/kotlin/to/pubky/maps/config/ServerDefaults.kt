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

    val mapStyles =
        MapStyleServers(
            light =
                listOf(
                    MapStyleSource(
                        name = "OpenFreeMap Liberty",
                        styleUrl = "https://tiles.openfreemap.org/styles/liberty",
                        attribution = openFreeMapCredits,
                    ),
                    MapStyleSource(
                        name = "VersaTiles Colorful",
                        styleUrl = "https://tiles.versatiles.org/assets/styles/colorful/style.json",
                        attribution = versaTilesCredits,
                    ),
                ),
            dark =
                listOf(
                    MapStyleSource(
                        name = "OpenFreeMap Dark",
                        styleUrl = "https://tiles.openfreemap.org/styles/dark",
                        attribution = openFreeMapCredits,
                    ),
                    MapStyleSource(
                        name = "VersaTiles Colorful Dark",
                        styleUrl = "https://tiles.versatiles.org/assets/styles/colorful-dark/style.json",
                        attribution = versaTilesCredits,
                    ),
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

    val config = ServerConfig(mapStyles = mapStyles, updateFeeds = updateFeeds)
}
