# Pubky Maps

A maps app on OpenStreetMap with Pubky social data on top. Android first; iOS later from the same
Kotlin code.

Today the app shows a full-screen online map in light or dark (following the system), the user's
location on request, a compass when the map is rotated, and a Settings screen with an About section.

## How it is built

- Kotlin Multiplatform with Compose Multiplatform. `shared` holds the UI and logic (targets Android,
  iosArm64 and iosSimulatorArm64); `androidApp` is the Android app (`to.pubky.maps`).
- The map is drawn by MapLibre Native through [maplibre-compose](https://github.com/maplibre/maplibre-compose)
  (OpenGL runtime).
- Every server the app talks to is an ordered list with fallbacks. The defaults live in
  `shared/src/commonMain/kotlin/to/pubky/maps/config/ServerDefaults.kt`; settings will let users edit
  them. When a map style fails to load, the map tries the next one in the list.
- Versions of every tool and library are in `gradle/libs.versions.toml`.

## Build

Requirements: JDK 17 or newer to run Gradle, and the Android SDK (`ANDROID_HOME`, or `sdk.dir` in
`local.properties`). Gradle downloads a Java 21 toolchain for the unit tests if none is installed
(Robolectric needs it for Android 15 and later).

```sh
./gradlew check                  # unit tests, screenshot tests, Android lint, ktlint
./gradlew verifyRoborazziDebug   # compare the screens with the reference screenshots
./gradlew recordRoborazziDebug   # write new reference screenshots after a UI change
./gradlew assembleDebug          # androidApp/build/outputs/apk/debug/androidApp-debug.apk
./gradlew ktlintFormat           # fix formatting
```

The iOS targets compile only on macOS; on Linux Gradle skips them.

## Run

Install the debug APK on a phone or emulator (Android 8.0, API 26, or newer):

```sh
./gradlew installDebug
```

Every CI run also uploads the debug APK as an artifact (`pubky-maps-debug-apk`).

## Screenshots

`androidApp/screenshots/` holds the reference screenshots of every screen: phone (412x915 dp) and
tablet (1280x800 dp), light and dark. The JVM cannot draw the MapLibre map, so the map screen shows a
flat placeholder under its controls there; the emulator smoke test in CI screenshots the real map.

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
