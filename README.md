# Pubky Maps

A maps app on OpenStreetMap with Pubky social data on top. It runs on Android; the shared Kotlin
code also declares iOS targets, which are not built yet.

The app shows a full-screen online map in light or dark (following the system), the user's location
on request, a compass when the map is rotated, and a Settings screen with an About section (version,
updates, licence, map data credits and the open-source licences of the bundled libraries). The APK
from GitHub releases updates itself from a signed update feed.

## How it is built

- Kotlin Multiplatform with Compose Multiplatform. `shared` holds the UI and logic (targets Android,
  iosArm64 and iosSimulatorArm64); `androidApp` is the Android app (`to.pubky.maps`).
- The map is drawn by MapLibre Native through [maplibre-compose](https://github.com/maplibre/maplibre-compose)
  (OpenGL runtime).
- Every server the app talks to is an ordered list with fallbacks. The defaults live in
  `shared/src/commonMain/kotlin/to/pubky/maps/config/ServerDefaults.kt`, and the lists in use are held
  in `ServerConfigRepository`. When a map style fails to load, the map tries the next one in the list.
- The open-source licence list is generated at build time by the AboutLibraries Gradle plugin from the
  app's dependencies.
- Versions of every tool and library are in `gradle/libs.versions.toml`.

## Build

Requirements: JDK 17 or newer to run Gradle, and the Android SDK (`ANDROID_HOME`, or `sdk.dir` in
`local.properties`). Gradle downloads a Java 21 toolchain for the unit tests if none is installed
(Robolectric needs it for Android 15 and later).

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
- `dev`: a pre-release from a manual run of the workflow on any branch, to try release changes.

Each has the signed APK, the signed `.aab`, `SHA256SUMS` and the signed update feed of its channel
(`update-edge.json`, `update.json`, `update-dev.json`). versionCodes only grow: see
`scripts/release/version.py`.

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

## Data sources and attribution

- Map data © [OpenStreetMap contributors](https://www.openstreetmap.org/copyright), available under the
  Open Database Licence.
- Map tiles and styles: [OpenFreeMap](https://openfreemap.org) (styles Liberty and Dark, built on
  [OpenMapTiles](https://www.openmaptiles.org/)), with [VersaTiles](https://versatiles.org) (Colorful
  and Colorful Dark) as the fallback.
- Icons: [Material icons](https://github.com/google/material-design-icons) (Apache-2.0).

The map always shows the credits of the style it draws; Settings → About lists every source.

## Licence

MIT, see [LICENSE](LICENSE).
