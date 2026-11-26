# Pubky Maps

A maps app on OpenStreetMap with Pubky social data on top. It runs on Android; the shared Kotlin
code also declares iOS targets, which are not built yet.

The app shows a full-screen online map in light or dark (following the system), the user's location
on request, a compass when the map is rotated, search, and Settings.

**Search** (the bar at the top of the map) finds places, addresses, streets and towns as you type,
from [Photon](https://github.com/komoot/photon) (OpenStreetMap data, biased to the map's centre) and
Pinakes (places people referenced on Pubky, near the map). The same
OpenStreetMap place from both is one result, and a stop's platforms are one stop. Results show in a
list (on a phone a sheet that drags between its first row, half and full height) and as pins with an
icon of their kind; picking one centres the map and opens its place
page. Signed in, places the people you follow referenced on Pubky
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
during resumes from there, and an area deletes. 20 seconds after the start and every 30 minutes, on
a network Android does not count as metered (Wi-Fi, as Google Maps and OsmAnd update offline maps),
the app walks each ready area again: unchanged, this costs no request; when the map server has moved
its tiles to a new release (OpenFreeMap does with each one), the area downloads again at the new
addresses, within the same limits, and shows "Updating"; the old release's tiles stay with the area
until it is deleted. An update that stops before it finishes leaves the area marked "Update
paused" (MapLibre would call it complete, as it counts the old release's tiles too); the next check
goes on with it unless the user paused it, and Resume does at once. MapLibre keeps the offline maps and the map
cache in one database in the no-backup folder.

Settings:

- **Account:** sign in with [Pubky Ring](https://github.com/pubky/pubky-ring). It shows the user's
  profile name, tags and saved places, kept on the phone so they stay readable offline, and signs out.
- **Server lists:** an ordered list of servers per service (map styles, Photon, Pinakes, Nexus,
  Pkarr relays, sign-in relay). Users add, remove, reorder and reset them; servers run by Synonym are
  marked. The map uses its style list in order, the next style taking over when one fails to load;
  sign-in uses the Pkarr and sign-in relay lists (from the next start after a change); opening a
  map link asks the Pinakes list; search uses the Photon and Pinakes lists the same way (the next
  server takes over on an error, a timeout or "too many requests"); place pages and the map's Pubky
  places use the Pinakes and Nexus lists the same way.
- **What is stored online:** per kind of data (saved places, home and work, lists, check-ins, tracks,
  location history, photo locations) phone only, homeserver private or public. Tags and reviews are
  public by nature. Saved places, lists and photo locations follow the choices; the other kinds are
  not saved by the app yet.
- **Open in other apps:** a switch per app a place can be opened in from the place page's Open in
  menu (`links/OpenIn.kt` builds the list).
- **Follow local rules:** hides features a country forbids (stored; no feature has a rule yet).
- **About:** version, updates (in the APK from GitHub releases, which updates itself from a signed
  update feed), licence, source code and the open-source licences of the bundled libraries and of
  the Rust crates in the core.
- **Map data:** the credits of every map source.

**Place pages** open from a search result, a link from another app, a point of interest tapped on
the base map (by its OpenStreetMap element) or a Pubky place on the map. On a phone the page is a
sheet over the map (header only, half the screen, or the whole screen); on a wide screen a panel
beside it. It shows the name, what the place is, the distance and the address, Share and Open in
(the apps switched on in Settings, installed ones first); the Pinakes card's photo and text with
their credits; OpenStreetMap details from the card's facts when Pinakes serves them (opening hours with open or closed now, phone,
website, wheelchair access, cuisine); Pubky tags with their counts and who tagged (people the user
follows first, the user's own tags marked); photos and posts about the place from Nexus, newest first;
the public lists that hold it; and the same place on other services ("Also on"). Each part loads on
its own and is hidden while it has nothing. What a page showed is kept on the phone, so a place
reopens offline with a note saying when it was saved; the user's own tags and lists in it are shown
only to the account that saw them. Typed coordinates open a page without any request, and the
map's Pubky places wait until the user moves the map away from the typed point.

**Writing about a place** (signed in; a write that needs the homeserver opens the sign-in first and
continues after it). The page's action row has Save, Tag, Post and Add to list; its sections have
"Add tag", "Add photo", "Write a post" and "Add to list". Every object is built by the core's spec
builders. Tags go on the link the user had (the OpenStreetMap element when picked on the map, a
pasted Google or Apple link as pasted): one tap on a label people used, or on a suggestion (the
place's top tags, its OpenStreetMap category and cuisine, folded to ASCII by the spec's label
rules), tags it; a tap on the user's own tag removes it. A post is a public `note` replying to the
place (up to 2000 characters). A photo is picked or taken, re-encoded on the phone as JPEG (HEIC
too), which drops camera and phone details and, unless Photo locations is Public, its GPS spot,
then uploaded and posted as an `image` post embedding the place, with alt text. Lists are the
user's collections (read from their homeserver, public or private, or kept on the phone), 100
places each. Save keeps the place privately on the homeserver or on the phone only, per the
setting; the **Saved** screen (from the search page) lists saved places and lists. Public writes
show a Public badge first. A write shows at once and goes into an outbox on the phone, sent in
order; one without a connection waits and is retried, and goes out as soon as the phone is online
again (the homeserver is tried again then, not only on a timer); one the homeserver refuses is
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
  (`SearchRanking`: text match, distance, each source's order, prominence, Pubky collections). The
  ranking is checked against a fixed Zürich query set with recorded answers
  (`shared/src/commonTest/fixtures/search/`, refreshed by `scripts/search/record-fixtures.py`).
  Requests carry `User-Agent: PubkyMaps/<version>`.
- Place pages (`shared/.../place/`): `PlacePageController` loads each part on its own (Pinakes card,
  then Nexus tags/posts/people for every link of the place, and the lists) and caches what was shown
  in its own SQLite database (`place-cache.db`, never in a backup); `OpeningSchedule` evaluates OSM
  opening hours parsed by osm-opening-hours. The map's Pubky places layer (`map/MapPlacesLayer.kt`)
  asks Pinakes for the view grown by half and widened to a 1/16° grid, at most 1,000 places, and
  MapLibre clusters them. The
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
  data (profile name, tags, saved places, lists) and the outbox of writes are cached in SQLite
  (SQLDelight), deleted at sign-out; what is kept on the phone only has its own database, which
  sign-out keeps. When the homeserver cannot
  be reached the app stays signed in, shows the cached data and says so in one line over the map.
- The open-source licence list is generated at build time: the AboutLibraries Gradle plugin lists the
  app's Gradle dependencies, and `scripts/rust-licences.py` (Gradle task `listRustLicences`) lists the
  Rust crates linked into the core with their licence files, from `cargo tree` for the Android targets.
- Versions of every tool and library are in `gradle/libs.versions.toml`.

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

- Smoke test (`scripts/ci/emulator-smoke.sh`): the map draws, Settings opens, back returns.
- Offline maps (`scripts/ci/emulator-offline.sh`, two tiny styles served on the runner): an area
  downloads from Settings → Offline maps until Ready, with at most 4 tile requests at a time, the
  app's User-Agent and no tile twice; after the server moves its tiles to a new release the app
  updates the area by itself; with the server gone the area draws in light and dark; it deletes.
- Sign-in probe (`scripts/ci/sign-in-probe.py`): Pubky Ring cannot run in CI, so a fixture relay on
  the runner stands in for the sign-in relay. The probe opens Settings → Account, starts a sign-in,
  reads the QR code, checks the link and that the app listens on the relay, then opens Ring's return
  links, kills the app in the middle and checks the same sign-in continues and Ring's cancel ends it.
- Synonym blocked (`scripts/ci/synonym-blocked.py`): the emulator gets a DNS server that blocks every
  host run by Synonym (`*.pubky.app`, `*.pubky.org`, `*.synonym.to`) and our development hosts. With a
  seeded signed-in account the app must start, draw the map, show the cached profile and tags, and
  name the missing homeserver in its banner; a second run also blocks the first map style and the map
  must draw from the next one.

## Data sources and attribution

- Map data © [OpenStreetMap contributors](https://www.openstreetmap.org/copyright), available under the
  Open Database Licence.
- Map tiles and styles: [OpenFreeMap](https://openfreemap.org) (styles Liberty and Dark, built on
  [OpenMapTiles](https://www.openmaptiles.org/)), with [VersaTiles](https://versatiles.org) (Colorful
  and Colorful Dark) as the fallback.
- Search: [Photon](https://photon.komoot.io) by komoot (OpenStreetMap data), and Pinakes.
- Icons: [Material icons](https://github.com/google/material-design-icons) (Apache-2.0).

The map always shows the credits of the style it draws; Settings → Map data lists every source.

## Licence

MIT, see [LICENSE](LICENSE).
