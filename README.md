# Pubky Maps

A maps app on OpenStreetMap with Pubky social data on top. It runs on Android; the shared Kotlin
code also declares iOS targets, which are not built yet.

The app shows a full-screen online map in light or dark (following the system), the user's location
on request, a compass when the map is rotated, search, and Settings. The map opens where the user
left it (centre, zoom, turn and tilt, stored on the phone only in the no-backup folder once the
camera has rested for a second, or when the map leaves the screen: where the user's own gesture had
it, also mid-pan or mid-fling, but not a view the app's own move to a place passes through); the
first start opens on the whole world.

**Search** (the bar at the top of the map) finds places, addresses, streets and towns as you type,
from [Photon](https://github.com/komoot/photon) (OpenStreetMap data, biased to the map's centre) and
Pinakes (places people referenced on Pubky, near the map). The same
OpenStreetMap place from both is one result, and a stop's platforms are one stop. Results show in a
list (on a phone a sheet that drags between its first row, half and full height) and as pins with an
icon of their kind; picking one opens its place page and moves the map so the place is in the
middle of the map the page leaves in view. Signed in, places the people you follow referenced on Pubky
rank higher. Coordinates
(`47.37, 8.54`, `47°22'N 8°32'E`), `geo:` URIs and plus codes open a pin without any request. Recent
searches stay on the phone (Settings → Privacy → Clear search history). Either source failing never
holds back the other; with both unreachable the list and the map's degraded line say search is
offline, and Search asks again. Photon is credited under the results and in Settings → Map data.

**Offline maps** (Settings → Offline maps): Download an area opens a frame over the map; the map
in the frame downloads in the style on screen and the other theme's style of the same server, so it
shows light and dark without a connection. One download may take at most 3,000 tiles (zoom 0 to 14) and 50 MB of
tiles, with at most 4 requests at a time and the app's User-Agent (OpenFreeMap's terms for offline
downloads, applied to every map server); the picker refuses a larger area and a download that grows
past the limit stops. The two styles download one after the other, so the tiles they share are
fetched once. The list shows each area's progress and size; a download paused or the app was closed
during resumes from there, and an area deletes. 20 seconds after the start and every 30 minutes the
app walks each ready area again: unchanged, this costs no request; when the map server has moved
its tiles to a new release (OpenFreeMap does with each one), the area downloads again at the new
addresses, within the same limits, and shows "Updating". MapLibre keeps every tile a pack ever
stored linked to it, so once an update has walked every style the app makes a new pack of each style
from the tiles now on the phone (without fetching them again) and deletes the old one: the old
release's tiles drop to MapLibre's map cache, whose least used tiles go once it is over its size
limit, instead of staying with the area until it is deleted. A new pack left by an app closed in between is deleted, or takes the old one's place, at
the next check. On a network Android counts as metered the walk stops after 10 seconds when it
finds a new release (Google Maps and OsmAnd update offline maps on Wi-Fi); an update begun on
Wi-Fi stops within 10 seconds of the phone moving to a metered network (a moment without a network
does not stop a walk already downloading; a walk that finds a new release without a network stops
there as on mobile data), and one the user resumed goes on on any network. Once a walk shows "Updating",
the area stays so until the walk of its last style is complete. An update that stops
before it finishes leaves the area marked "Update paused" (MapLibre would call it complete, as it
counts the old release's tiles too); Resume goes on with it, and so does the next check on Wi-Fi
unless the user paused it since the app started. An area marked so before the check found new
data (the app was closed in the walk's first seconds) is walked on mobile data too: it is Ready
again when nothing changed. MapLibre keeps the offline maps and the map
cache in one database in the no-backup folder. When the phone has a network but the map server
cannot be reached, an offline area's style stays drawn from its cached copy even once the copy has
expired (OpenFreeMap's does a day after it was fetched), instead of failing and moving on to the
next server, whose tiles are not on the phone; any other style still hands over to the next server. The map's Pubky places of each area (the places
people referenced on Pubky) are fetched from Pinakes as soon as the area is in the list, at most
10,000 per area, and fetched again once a day on a network Android does not count as metered; when
Pinakes cannot be reached, the map shows them in the downloaded areas. Their tags and counts come
from Nexus with them (no viewer, at most 20 requests of 100 links per area; when Nexus did not
answer, only Nexus is asked again, every 15 minutes, and the old tags keep their date); when Nexus
cannot be reached, a place page shows them (if newer than the tags the page saved) with the date
they were fetched, also for a place never opened before. These requests tell Pinakes which areas
are downloaded (the area widened to a 1/16° grid) and Nexus which places are in them, also while the
user looks elsewhere.

**Search without a connection:** once an area is ready, and again after every update that fetched
tiles (the area's `newDataAt`, kept with its places), the app reads the named places of its zoom 14
tiles (points of interest, towns and districts, streets, peaks, lakes) into its own database
(`no_backup/offline-places.db`). It reads only the newest tile of each source at each place, as the
release before's tiles stay linked to an area whose packs could not be replaced, from a copy of
MapLibre's database taken while no
download or update check fetches tiles (MapLibre's own SQLite writes the file; a second SQLite in the
process must not open it); a copy taken during a write is refused and taken again later. Each place
gets the district and town it lies in from the area's own place labels: the nearest of each, its
distance divided by how far a label of its kind reaches (a city 12 km, a town 5 km, a village 2 km,
a suburb 2.5 km, a quarter 1.2 km), so a place near a boundary can be named after the neighbour.
When Photon cannot be reached, search answers from these places: every typed word starts a word of
the name, the category or the district and town ("bahnhofstrasse winterthur"), and at least one of
the name or category, so a town's name finds the town and its streets, not every shop in it. The
rarest word, and every other word that is not in thousands of places, looks them up nearest first;
a word in more than 5,000 places ("s" as the user starts typing "street") is looked up within a box
around the point that grows until it holds the nearest few hundred, while it holds fewer places than
the word, so it does not read every place with the word (under 100 ms for a word of all 60,000
places of `OfflinePlaceIndexDatabaseTest`, where reading them took more);
when the query names a town or district, the other words are looked up around it too ("cafe uster"
from Zürich). A place named exactly as typed passes also when farther than the others, and a town's
label before the places named after it. They are merged with Pinakes when it answers. A result
shows its district and town where an address goes, and so does its page until its Pinakes card
gives the street address; the list says the places are from the offline maps and that street
addresses need a connection, and the map's credits stand under them. A place opened from the map
(a tapped point of interest, a Pubky pin, a saved place, a link), a dropped pin or typed coordinates without an
address get the same line on their page, unless a saved card gives the street address: the district
and town stored with its OpenStreetMap element, else those the labels around its point give (a
town's page is not named after the town), when the areas have a place within 500 m north, south,
east or west of it (farther out, a nearer label may lie outside their tiles). Deleting an area
deletes its places.

**Place details without a connection:** once an area is listed, the app asks the Overpass list for
the OpenStreetMap details of its named places (opening hours, phone, website, email, wheelchair
access, cuisine, operator, floor, bitcoin payments: the tags a place page shows) in the area's
whole zoom-14 tiles (what its map and place index hold), as one query that answers only those tags
as CSV (a few MB for the largest area one download may take) and ends with an end line, so an answer
a server cut off goes to the next server, as does an empty one (a server may hold one region only:
the first default, dresel.systems, holds Europe); an area is kept with no details only when every
server answered it empty. Each Overpass server learns which areas the user keeps. They are kept by
element in `offline-places.db`, apart from the places read from the tiles, fetched again after a week on a
network Android does not count as metered, one area at a time and 10 s apart, and deleted with the
area; an area that fails is asked again after 30 minutes, then twice as long after each failure, up
to a day. A place page whose Pinakes card has no OSM details (no card could be loaded, Pinakes has
none for the place, or its card brings none) shows the details kept for its
element, and the offline note says when they were fetched; a card with OSM details shows only its own.
A search result whose element has kept opening hours says under its address whether it is open now
and when that changes ("Open · Closes 18:00" in green, "Closed · Opens Thu 10:00" in red), online
too: Photon's and Pinakes' answers carry no hours.

Settings:

- **Account:** sign in with [Pubky Ring](https://github.com/pubky/pubky-ring). It shows the user's
  profile name, tags and bookmarks, kept on the phone so they stay readable offline, and signs out.
- **Server lists:** an ordered list of servers per service (map styles, Photon, Pinakes, Nexus,
  Overpass, Valhalla, OpenStreetMap API, Pkarr relays, sign-in relay). Users add, remove, reorder and reset them; servers run by Synonym are
  marked. The map uses its style list in order, the next style taking over when one fails to load;
  sign-in uses the Pkarr and sign-in relay lists (from the next start after a change); opening a
  map link asks the Pinakes list; search uses the Photon and Pinakes lists the same way (the next
  server takes over on an error, a timeout or "too many requests"); place pages and the map's Pubky
  places use the Pinakes and Nexus lists the same way, the offline areas' place details the
  Overpass list, directions the Valhalla list, and map notes the OpenStreetMap API list.
- **What is stored online:** per kind of data (bookmarks, home and work, collections, check-ins, tracks,
  location history, photo locations) phone only, homeserver private or public. Tags and reviews are
  public by nature. Bookmarks, collections and photo locations follow the choices; the other kinds are
  not stored by the app yet.
- **Open in other apps:** a switch per app a place can be opened in from the place page's Open in
  menu (`links/OpenIn.kt` builds the list).
- **Follow local rules:** hides features a country forbids (stored; no feature has a rule yet).
- **About:** version, updates (in the APK from GitHub releases, which updates itself from a signed
  update feed), licence, source code and the open-source licences of the bundled libraries and of
  the Rust crates in the core.
- **Map data:** the credits of every map source.

**Place pages** open from a search result, a link from another app, a point of interest tapped on
the base map (by its OpenStreetMap element), a Pubky place on the map, or a long-press anywhere on
the map, which drops a pin there (also on a point of interest; not while an area is picked for
download): its page is named by its coordinates and says "Dropped pin". On a phone the page is a
sheet over the map (header only, half the screen, or the whole screen); on a wide screen a panel
beside it. A place tapped on the map that its sheet or panel would hide moves to the middle of the
map left in view (also under the notices below the search bar) once the sheet has opened, or, while
the page loads, once its growing header covers it; not after the user moved the map or the
sheet. A search result or a link's place goes there in one move as its page opens. It shows the
name, what the place is, the distance and the address, Directions, Start here, Share and Open in
(the apps switched on in Settings, installed ones first); the Pinakes card's photo and text with
their credits; OpenStreetMap details from the card's facts when Pinakes serves them, or, when the card brings none, those an offline area keeps (opening hours with open or closed now, phone,
website, wheelchair access, cuisine); Pubky tags with their counts and who tagged (people the user
follows first, the user's own tags marked); photos and posts about the place from Nexus, newest first;
the public collections that hold it; and the same place on other services ("Also on"). Each part loads on
its own and is hidden while it has nothing. What a page showed is kept on the phone, so a place
reopens offline with a note saying when its copy is from; the user's own tags and collections in it are shown
only to the account that saw them. Typed coordinates and a dropped pin open a page without any
request, also when bookmarked or added to a collection from that page and reopened from their pin or the Bookmarks page, and
the map's Pubky places wait until the map is next moved: by a gesture while the page is open, or by
any move after it closes. The phone that bookmarked them keeps which `geo:` places were bookmarked so (the
homeserver's bookmark has no room for it): on another phone, or after the app's data was cleared,
such a bookmark reopens as a `geo:` link from another app or a Pubky place with a `geo:` URI does, a
public place read with its card and tags.

**Directions** on a place page ask for the user's position as the my-location button does (the
permission only then) and route from there to the place by car, bike or on foot (car at first, then
the mode picked last) through the Valhalla list (FOSSGIS' public server by default: at most one
request a second, the next server taking over when one fails). The page shows the route's time and
length with the server's credits and a link to report a map error; the map draws the line and fits
it into the part the page leaves in view. Steps under the route open its list of turns under the place's
header (Valhalla's English sentences, each with its turn's icon and how far the route then goes); on a
phone the sheet rises to the top for them and, lowered again, keeps the map with the route in view.
A tapped step is marked on the line, the map moves to it (zoom 17, in the middle of the map the page
leaves in view) and the route card shows the step with buttons to the previous and the next one; on
a phone the sheet goes down to its lowest stop for it. The list stays open for the other modes (a
new route marks no step). The swap button beside the modes routes the other way, from the place back
to the user's position, and again to the place; the next place starts from the user again. Start
here on a place page (also a dropped pin, as CoMaps' "Route from") makes that place the start of the
directions to every other place until the user clears it, for as long as the app runs: they ask for
no position, say "By car from" the start's name, mark the start with a white dot in a dark ring, and
"From your location" in the route card routes from the user's position again. The start's own page
reads "Starting here" (a tap clears it) and routes from the user. Another
place, or closing the page, ends them. Without a position, a route or a
server, the page says which is missing (with Settings when location access is off for good), and
every server failing names routing in the map's line of missing parts. Directions do not follow the
user's position or speak while they travel, and need a connection.

**Report a map problem** at the end of a place page (also of a dropped pin) opens a note to
OpenStreetMap's mappers: what the user writes, then the place's name and OpenStreetMap link when it
has them (a point of its own, as a dropped pin, goes without the user's label) and `#PubkyMaps`, at
the place's own point (a page whose point is only a map view or a search centre has no button), up
to OpenStreetMap's 2000 characters. It goes through the OpenStreetMap API list (`api.openstreetmap.org`
by default) without an account, so the note is public and anonymous. A server that cannot be reached,
fails or answers not as the API does (also a success that is not the new note, as a web page
answering every address) hands over to the next one; the API's refusal is shown with its
reason; a request with no answer in time is not sent elsewhere and the page says the note may be on
the map already. A note that did not go keeps its text on the page to send again; notes need a
connection. A note page Android brought back after stopping the app still thanks for a sent note, and
one whose note was on its way or got no answer in time says it may be on the map already, so neither
offers the same note again as new.

**Writing about a place** (signed in; a write that needs the homeserver opens the sign-in first and
continues after it). The page's action row has Bookmark, Tag, Post and Add to collection; its sections have
"Add tag", "Add photo", "Write a post" and "Add to collection". Every object is built by the core's spec
builders. Tags go on the link the user had (the OpenStreetMap element when picked on the map, a
pasted Google or Apple link as pasted): one tap on a label people used, or on a suggestion (the
place's top tags, its OpenStreetMap category and cuisine, folded to ASCII by the spec's label
rules), tags it; a tap on the user's own tag removes it. A post is a public `note` replying to the
place (up to 2000 characters). A photo is picked or taken, re-encoded on the phone as JPEG (HEIC
too), which drops camera and phone details and, unless Photo locations is Public, its GPS spot,
then uploaded and posted as an `image` post embedding the place, with alt text. Collections (`OwnList` in the code) are
the user's, read from their homeserver (public or private) or kept on the phone, 100
places each. Bookmark keeps the place privately on the homeserver or on the phone only, per the
setting; the **Bookmarks** screen (from the search page) lists bookmarks and collections (bookmarks of
other Pubky apps' objects in the same folder, such as pubky.app posts, are left out), and the map
shows each bookmarked place as a purple bookmark badge that opens it. The homeserver's bookmark holds
only the link, so the place's name, point and summary (what and where it is, "Café · Napfgasse 4,
8001 Zürich", as its page says once its card and offline district and town are in) are kept on the
phone with the bookmark (by the link as the core spells it), never sent; the Bookmarks screen shows the
summary under the name, also for the places in collections, and tells two same-named places with the same
summary (or none) apart by their points. A `geo:` link names its own point. A bookmarked or tagged place
the phone knows no name, point or summary for (bookmarked on another device, before the last sign-out,
or before summaries were kept) keeps those its page shows when the user opens it, and from then on
has its name on the Bookmarks and Account pages and, when bookmarked, its badge on the map; a summary kept
stays, except one taken while Pinakes could not be reached and no card or address was known (the
offline district and town only), which the summary of a page Pinakes answered replaces. Public writes
show a Public badge first. A write shows at once and goes into an outbox on the phone, sent in order;
one without a connection waits and is retried, and goes out as soon as the phone is online
again (the homeserver is tried again then, not only on a timer), also with the app closed: while
writes wait, a JobScheduler job (`OutboxJobService`, expedited from Android 12 on, kept over a
restart of the phone) has Android start the app when the phone has a network, and tries for one
minute per run, again after a backoff while the homeserver cannot be reached (and, through a
short guard job, also when the app's process dies during a run); one the homeserver refuses is
taken back with a message.

The map also shows the places people referenced on Pubky (from zoom 12, from Pinakes, clustered).
When no Pinakes server answers, the map's status line says so.

**Map links.** Links from other map apps open in Pubky Maps: tapped (`geo:`, `om://`, `cm://`,
and Google Maps, Apple Maps, Waze, OpenStreetMap, OsmAnd, Organic Maps, CoMaps and HERE web links
when the user lets the app open them) or shared as text from another app's Share. The map moves to
the place, pins it and shows it with its name, where it is and Share (the name plus an
openstreetmap.org link), as a place page. Short links (`maps.app.goo.gl`, `maps.apple/p/`) are followed by their
redirect header only; the place's name and point come from Pinakes when it knows the link, else from
the link text. The link the user had stays the place's URI.

## How it is built

- Map links are read and built by the Rust crate `core/map-links` (see its README for every
  format), exposed through the core.
- Kotlin Multiplatform with Compose Multiplatform. `shared` holds the UI and logic (targets Android,
  iosArm64 and iosSimulatorArm64); `androidApp` is the Android app (`to.pubky.maps`).
- The map is drawn by MapLibre Native through [maplibre-compose](https://github.com/maplibre/maplibre-compose)
  (OpenGL runtime).
- Server addresses are ordered lists. The defaults live in
  `shared/src/commonMain/kotlin/to/pubky/maps/config/ServerDefaults.kt`; users edit the lists in
  Settings. When a map style fails to load, the map tries the next one in the list. The update feeds
  are fixed per build and not part of the user's settings.
- Search (`shared/.../search/`): `SearchController` debounces typing (300 ms, nothing under 3
  characters), asks Photon and Pinakes together over Ktor and ranks the merged answers
  (`SearchRanking`: text match, distance, each source's order, prominence, Pubky collections). Each
  answer's elements are looked up in the offline areas' kept details (`KeptHours.kt`) before its
  rows show, so a row has its opening hours from the start. The
  ranking is checked against a fixed Zürich query set with recorded answers
  (`shared/src/commonTest/fixtures/search/`, refreshed by `scripts/search/record-fixtures.py`).
  Requests carry `User-Agent: PubkyMaps/<version>`.
- Place pages (`shared/.../place/`): `PlacePageController` loads each part on its own (Pinakes card,
  then Nexus tags/posts/people for every link of the place, and the lists) and caches what was shown
  in its own SQLite database (`place-cache.db`, never in a backup); `OpeningSchedule` evaluates OSM
  opening hours parsed by osm-opening-hours. The map's Pubky places layer (`map/MapPlacesLayer.kt`)
  asks Pinakes for the view grown by half and widened to a 1/16° grid, at most 1,000 places, and
  MapLibre clusters them. `offline/OfflinePubkyPlaces.kt` keeps the layer's places of each offline
  area (the area widened to the same grid) and Nexus' tags of them in `offline-pubky-places.db`
  (never in a backup), which the layer reads when every Pinakes server fails, and a place page when
  every Nexus server does. `offline/OfflinePlaceDetails.kt` keeps the OSM details of each offline
  area's places from Overpass (`OverpassPlaceDetails`) in `offline-places.db`, for pages with no
  card. The
  readers are tested against recorded answers (`shared/src/commonTest/fixtures/place/`, refreshed by
  `scripts/place/record-fixtures.py`).
- Settings are one JSON file with a schema version, written by AndroidX DataStore
  (`config/SettingsRepository.kt`, `config/SettingsCodec.kt`). Only the user's own changes are
  stored, so new defaults reach everyone who did not change that setting. A file from an older
  version is migrated step by step; one from a newer version is read as far as it is understood.
- The Rust core (`core/`, see [core/README.md](core/README.md)) does the Pubky work: sign-in, the
  homeserver, the social objects. Gradle builds it: `:shared:generateCoreBindings` builds it for this
  machine and generates its Kotlin bindings (UniFFI, called through JNA), which the JVM tests also use
  for real calls; `:androidApp:buildCoreForAndroid` builds `libpubky_maps_core.so` for arm64-v8a and
  x86_64 with cargo-ndk and packages it into the APK.
- Sign-in with Pubky Ring: the app opens Ring's `pubkyauth://` link, or shows it as a QR code when
  Ring is on another device; Ring sends the user back with `pubkymaps://auth/…`. A running sign-in
  and the session are stored encrypted with an Android Keystore key, never in a backup; the user's own
  data (profile name, tags, bookmarks, collections) and the outbox of writes are cached in SQLite
  (SQLDelight), deleted at sign-out; what is kept on the phone only has its own database, which
  sign-out keeps. When the homeserver cannot
  be reached the app stays signed in, shows the cached data and says so in one line over the map.
- The open-source licence list is generated at build time: the AboutLibraries Gradle plugin lists the
  app's Gradle dependencies, and `scripts/rust-licences.py` (Gradle task `listRustLicences`) lists the
  Rust crates linked into the core with their licence files, from `cargo tree` for the Android targets.
- Versions of every tool and library are in `gradle/libs.versions.toml`.
- The Maven libraries the app ships (the release APK and bundle of both flavours, Gradle task
  `listShippedLibraries`, which fails when one does not resolve; not the test, lint and build tools)
  are checked against the advisories of [OSV](https://osv.dev) (`scripts/ci/gradle-advisories.py`,
  CI `.github/workflows/gradle-advisories.yml`, when a dependency changes and every Monday). An
  advisory fails the check unless `gradle/advisory-ignores.toml` ignores it with a reason; an ignore
  that matches nothing any more fails too, and so does OSV not answering.

## Build

Requirements: JDK 17 or newer to run Gradle, the Android SDK (`ANDROID_HOME`, or `sdk.dir` in
`local.properties`), and Rust (stable, via rustup). Gradle downloads a Java 21 toolchain for the unit
tests if none is installed (Robolectric needs it for Android 15 and later). Building an APK also needs
the Android NDK (`ANDROID_NDK_HOME`, r27 or newer), `cargo install cargo-ndk` and
`rustup target add aarch64-linux-android x86_64-linux-android`. Set `CARGO_TARGET_DIR` to share one
cargo build directory between checkouts.

```sh
./gradlew check                  # unit tests, screenshot tests, Android lint, ktlint
./gradlew verifyRoborazziDirectDebug   # compare the screens with the reference screenshots
./gradlew recordRoborazziDirectDebug   # write new reference screenshots after a UI change
./gradlew assembleDirectDebug          # androidApp/build/outputs/apk/direct/debug/androidApp-direct-debug.apk
./gradlew ktlintFormat                 # fix formatting
```

The app has two flavours: `direct`, the APK published on GitHub, with the in-app updater; and
`store`, the bundle for app stores, which update the app themselves (no updater, no
`REQUEST_INSTALL_PACKAGES`). Release builds are shrunk with R8 and signed outside Gradle.

A Kotlin compiler warning in non-test code (a deprecated call, a check that is always true) fails
the build, including the generated SQLDelight code and core bindings; warnings in test code do not
(`build.gradle.kts`). On Linux this covers the Android code; the iOS compiles have the same rule but
run only on macOS.

The iOS targets compile only on macOS; on Linux Gradle skips them.

## Run

Install the debug APK on a phone or emulator (Android 8.0, API 26, or newer):

```sh
./gradlew installDirectDebug
```

Every CI run also uploads the debug APK as an artifact (`pubky-maps-debug-apk`).

## Releases and updates

[GitHub releases](https://github.com/gillohner/pubky-maps/releases) (`.github/workflows/release.yml`):

- `edge`: a pre-release that every push to `main` replaces with a build of `main`'s head, unless edge
  already has that build (versionName `<x.y.z>-edge.<run>`), so pushes that come close together do
  not leave it on an older commit, and never with an older commit than the one it has. When
  `release.yml` itself changed between a run's commit and `main`'s head, that run builds its own
  commit, and edge can stay behind `main` until the next push to `main`;
- `v<x.y.z>`: a release, made by pushing that tag; the tag must match `pubkymaps.version` in
  `gradle.properties`;
- `dev`: a pre-release from a manual run of the workflow on any branch, to try release changes. It is
  signed with separate dev keys, so it cannot update an edge or stable install.

Each has the signed APK, the signed `.aab`, `SHA256SUMS` and the signed update feed of its channel
(`update-edge.json`, `update.json`, `update-dev.json`). versionCodes only grow: see
`scripts/release/version.py`. Edge and stable APKs are signed with the release key whose certificate
SHA-256 is in `release/release-cert.sha256` (dev builds: `release/release-cert-dev.sha256`); compare it
with `apksigner verify --print-certs`. After publishing, the release workflow downloads the APK from the
release, checks that certificate and `SHA256SUMS`, installs it on an emulator and runs the smoke test.

The APK checks the feed of the channel it was built for when it starts (at most once a day, unless
turned off in Settings → About) and on "Check for updates". The feed is signed with Ed25519; the app
accepts it only with a valid signature from the key in `release/update-feed-key.pub`, for its own
package and channel, and only for a newer versionCode. It then downloads the APK, checks its size and
SHA-256 against the feed and its package and versionCode, checks it again, and hands it to Android's
installer, which installs it only when it is signed with the same release key. A feed or file that
fails a check is never installed, and the app says why. The feed addresses are a server list like
every other server (`ServerDefaults.updateFeeds`): any mirror can serve the same signed files.

## Screenshots

`androidApp/screenshots/` holds the reference screenshots of every screen: phone (412x915 dp) and
tablet (1280x800 dp), light and dark. The JVM cannot draw the MapLibre map, so the map screen shows a
flat placeholder under its controls there; the emulator smoke test in CI screenshots the real map,
the emulator search test (`scripts/ci/emulator-search.sh`, recorded answers served on the runner)
screenshots search with its pins, and the update probe screenshots each step of an in-app update.

## Emulator tests in CI

- Smoke test (`scripts/ci/emulator-smoke.sh`): the map draws, Settings opens, back returns, and
  after a map link moved the map to Zürich the next start opens it there; a theme switch during
  a pan (the finger still down, so the map reports its camera moving) and Back right after a pan
  still store the new view; for the theme switch the view must be first written within 1 s of
  MainActivity's destroy, so it was stored on leaving and not by the new map's 1 s rest. The app
  calls `reportFullyDrawn` once the map has drawn its first whole view (MapLibre idle with a style
  from the list); the test reads Android's "Displayed" and "Fully drawn" times of the first start
  and of a restart (`scripts/ci/start_time.py`) and fails when the map took longer than the
  budget set in `emulator-smoke.sh`.
- Offline maps (`scripts/ci/emulator-offline.sh`, two tiny styles served on the runner): an area
  downloads from Settings → Offline maps until Ready, with at most 4 tile requests at a time, the
  app's User-Agent and no tile twice; after the server moves its tiles to a new release the app
  updates the area by itself, and no tile of the old release is linked to it afterwards (read from a
  copy of MapLibre's database); with the server gone the area draws in light and dark (the light
  style is served already expired, the dark one `must-revalidate` too, so both are kept from the cache), and search
  (its servers gone too) finds the bakery each fixture tile holds; it deletes.
- Sign-in probe (`scripts/ci/sign-in-probe.py`): Pubky Ring cannot run in CI, so a fixture relay on
  the runner stands in for the sign-in relay. The probe opens Settings → Account, starts a sign-in,
  reads the QR code, checks the link and that the app listens on the relay, then opens Ring's return
  links, kills the app in the middle and checks the same sign-in continues and Ring's cancel ends it.
- Synonym blocked (`scripts/ci/synonym-blocked.py`): the emulator gets a DNS server that blocks every
  host run by Synonym (`*.pubky.app`, `*.pubky.org`, `*.synonym.to`) and our development hosts. With a
  seeded signed-in account the app must start, draw the map, show the cached profile and tags, and
  name the missing homeserver in its banner; a place bookmarked in airplane mode waits, and with the app
  closed Android starts it for the outbox job, again after its process is killed during a run (the
  run counts as ended by the kill whichever way JobScheduler noticed the dead process; on the API 35
  emulator Android sometimes restarts the job service at once and otherwise leaves it to the run's
  guard job; both pass, `scripts/ci/synonym-blocked-check.py` checks how the test reads the job
  states); a second run also blocks the first map style and the map must draw from the next one.
- StrictMode: debug builds turn it on before the app starts
  (`androidApp/src/debug/.../StrictModeProvider.kt`), on top of the platform's own policies, so
  network on the main thread and a file:// URI handed to another app still crash the debug app as
  they crash the release app. On Android 9 or newer they log each disk access on the main thread and
  each leaked resource (seen when the garbage collector runs), blamed on the first frame outside
  Android, Java, Kotlin, AndroidX and the app's I/O layers (SQLDelight, okio, JNA).
  StrictMode sees only Java I/O, not the Rust core's native files and sockets. Every emulator
  test above reads the log at its end (`scripts/ci/strict_mode.py`), lists the reports and fails on
  one that blames our code (a leaked activity is blamed on its class: the offline test switches
  the theme three times in one process, each switch recreates `MainActivity`, and an old one still
  in memory at the second or third fails it); reports about a library, and our lookups of the
  app's private folders (`filesDir`, `noBackupFilesDir`: a microsecond check that the folder
  exists), are listed only. MapLibre making its runtime (its native library's load) on the main
  thread counts as ours: the app makes it in the background as its map screen is created
  (`startMapRuntime`).

## Data sources and attribution

- Map data © [OpenStreetMap contributors](https://www.openstreetmap.org/copyright), available under the
  Open Database Licence.
- Map tiles and styles: [OpenFreeMap](https://openfreemap.org) (styles Liberty and Dark, built on
  [OpenMapTiles](https://www.openmaptiles.org/)), with [VersaTiles](https://versatiles.org) (Colorful
  and Colorful Dark) as the fallback.
- Search: [Photon](https://photon.komoot.io) by komoot (OpenStreetMap data), and Pinakes.
- Routes: [Valhalla](https://github.com/valhalla/valhalla) on [FOSSGIS](https://www.fossgis.de)' public
  server (OpenStreetMap data), credited under each route.
- Icons: [Material icons](https://github.com/google/material-design-icons) (Apache-2.0).

The map always shows the credits of the style it draws; Settings → Map data lists every source of the
map and of search; a route shows its own under it.

## Licence

MIT, see [LICENSE](LICENSE).
