package to.pubky.maps.config

/** The servers the app starts with. Every default server address lives here. */
object ServerDefaults {
    /**
     * The credit for OpenStreetMap data (ODbL). Settings always list it, and a map style the user adds
     * carries it, since nearly every web map style draws OSM data.
     */
    val openStreetMap =
        AttributionLink("© OpenStreetMap contributors", "https://www.openstreetmap.org/copyright")
    private val openFreeMap = AttributionLink("OpenFreeMap", "https://openfreemap.org")
    private val openMapTiles = AttributionLink("© OpenMapTiles", "https://www.openmaptiles.org/")
    private val versaTiles = AttributionLink("VersaTiles", "https://versatiles.org")

    private val openFreeMapCredits = listOf(openFreeMap, openMapTiles, openStreetMap)
    private val versaTilesCredits = listOf(versaTiles, openStreetMap)

    /** Search results come from Photon's OpenStreetMap index (komoot's public server by default). */
    val photon = AttributionLink("Search: Photon by komoot", "https://photon.komoot.io")

    /** Routes from FOSSGIS' Valhalla server; its terms ask for a way to report errors in OSM's data. */
    val fossgisRouting =
        AttributionLink("Routing: Valhalla by FOSSGIS", "https://www.fossgis.de/arbeitsgruppen/osm-server/nutzungsbedingungen")

    /** Where users report a wrong road or a missing path in OpenStreetMap. */
    val fixTheMap = AttributionLink("Report a map error", "https://www.openstreetmap.org/fixthemap")

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
                Service.Photon to listOf(ServerEntry("Photon by Komoot", "https://photon.komoot.io", listOf(photon, openStreetMap))),
                Service.Pinakes to
                    listOf(ServerEntry("Pinakes (development)", "https://8092--main--pinakes--gil.coder.riginode.xyz")),
                Service.Nexus to
                    listOf(ServerEntry("Nexus (development)", "https://8080--main--pubky-staging--gil.coder.riginode.xyz")),
                // Public Overpass instances that need no key. dresel.systems' default endpoint holds
                // Europe only (fast for the Swiss and German areas the app starts with); elsewhere its
                // empty answer hands over to the next server. FOSSGIS and private.coffee hold the planet.
                Service.Overpass to
                    listOf(
                        ServerEntry("Overpass Europe (dresel.systems)", "https://ovp.dresel.systems/api/interpreter"),
                        ServerEntry("Overpass (FOSSGIS)", "https://overpass-api.de/api/interpreter"),
                        ServerEntry("Overpass (private.coffee)", "https://overpass.private.coffee/api/interpreter"),
                    ),
                // FOSSGIS' public Valhalla, the one keyless routing server for apps: at most one request
                // a second, an app User-Agent, OSM credits with a link to report map errors, and not
                // hard-coded (fossgis.de/arbeitsgruppen/osm-server/nutzungsbedingungen). It learns the
                // start and end of every route the user asks for.
                Service.Valhalla to
                    listOf(ServerEntry("Valhalla (FOSSGIS)", "https://valhalla1.openstreetmap.de", listOf(fossgisRouting, openStreetMap))),
                // The OpenStreetMap API, for notes about map problems: it takes a note without an
                // account (shown as anonymous), asks apps for a User-Agent of their own and modest use
                // (operations.osmfoundation.org/policies/api). There is one OSM database, so no
                // fallback holds the same notes; the list lets a user point the app elsewhere.
                Service.OsmApi to listOf(ServerEntry("OpenStreetMap", "https://api.openstreetmap.org")),
                // Street-level photos: Panoramax is a federation of keyless open instances (photos under
                // CC BY-SA 4.0 or Etalab 2.0, credited on the page). Its federation index searches every
                // instance; the two largest instances answer for their own photos when it is down.
                Service.Panoramax to
                    listOf(
                        ServerEntry("Panoramax (all instances)", "https://api.panoramax.xyz"),
                        ServerEntry("Panoramax OpenStreetMap France", "https://panoramax.openstreetmap.fr"),
                        ServerEntry("Panoramax IGN", "https://panoramax.ign.fr"),
                    ),
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
