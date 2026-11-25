# Pubky Maps

A maps app on OpenStreetMap with Pubky social data on top. It runs on Android; the shared Kotlin
code also declares iOS targets, which are not built yet.

The app shows a full-screen online map in light or dark (following the system), the user's location
on request, a compass when the map is rotated, and Settings:

- **Account:** sign in with [Pubky Ring](https://github.com/pubky/pubky-ring). It shows the user's
  profile name, tags and saved places, kept on the phone so they stay readable offline, and signs out.
- **Server lists:** an ordered list of servers per service (map styles, Photon, Pinakes, Nexus,
  Pkarr relays, sign-in relay). Users add, remove, reorder and reset them; servers run by Synonym are
  marked. The map uses its style list in order, the next style taking over when one fails to load;
  sign-in uses the Pkarr and sign-in relay lists (from the next start after a change); the Photon,
  Pinakes and Nexus lists are stored and editable, and no feature reads them yet.
- **What is stored online:** per kind of data (saved places, home and work, lists, check-ins, tracks,
  location history, photo locations) phone only, homeserver private or public. Tags and reviews are
  public by nature. The choices are stored; nothing uploads data yet.
- **Open in other apps:** a switch per app a place can be opened in (stored for the place page's
  Open in menu, which does not exist yet).
- **Follow local rules:** hides features a country forbids (stored; no feature has a rule yet).
- **About:** version, updates (in the APK from GitHub releases, which updates itself from a signed
  update feed), licence, source code and the open-source licences of the bundled libraries and of
  the Rust crates in the core.
- **Map data:** the credits of every map source.

## How it is built

- Kotlin Multiplatform with Compose Multiplatform. `shared` holds the UI and logic (targets Android,
  iosArm64 and iosSimulatorArm64); `androidApp` is the Android app (`to.pubky.maps`).
- The map is drawn by MapLibre Native through [maplibre-compose](https://github.com/maplibre/maplibre-compose)
  (OpenGL runtime).
- Server addresses are ordered lists. The defaults live in
  `shared/src/commonMain/kotlin/to/pubky/maps/config/ServerDefaults.kt`; users edit the lists in
  Settings. When a map style fails to load, the map tries the next one in the list. The update feeds
  are fixed per build and not part of the user's settings.
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
  data (profile name, tags, saved places) is cached in SQLite (SQLDelight). When the homeserver cannot
  be reached the app stays signed in, shows the cached data and says so in one line over the map.
- The open-source licence list is generated at build time: the AboutLibraries Gradle plugin lists the
  app's Gradle dependencies, and `scripts/rust-licences.py` (Gradle task `listRustLicences`) lists the
  Rust crates linked into the core with their licence files, from `cargo metadata`.
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

- `edge`: a pre-release that every push to `main` replaces (versionName `<x.y.z>-edge.<run>`);
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
and the update probe screenshots each step of an in-app update.

## Emulator tests in CI

- Smoke test (`scripts/ci/emulator-smoke.sh`): the map draws, Settings opens, back returns.
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
- Icons: [Material icons](https://github.com/google/material-design-icons) (Apache-2.0).

The map always shows the credits of the style it draws; Settings → Map data lists every source.

## Licence

MIT, see [LICENSE](LICENSE).
