package to.pubky.maps.settings

/** A small licence list in the AboutLibraries JSON format, as the build generates it. */
internal const val SAMPLE_LICENCES_JSON = """
{
  "libraries": [
    {
      "uniqueId": "org.maplibre.compose:maplibre-compose",
      "artifactVersion": "0.20.0",
      "name": "MapLibre Compose",
      "description": "Compose Multiplatform bindings for MapLibre",
      "website": "https://github.com/maplibre/maplibre-compose",
      "developers": [{ "name": "MapLibre" }],
      "licenses": ["BSD-3-Clause"]
    },
    {
      "uniqueId": "androidx.compose.ui:ui",
      "artifactVersion": "1.12.0",
      "name": "Compose UI",
      "website": "https://developer.android.com/jetpack/androidx/releases/compose-ui",
      "developers": [{ "name": "The Android Open Source Project" }],
      "licenses": ["Apache-2.0"]
    }
  ],
  "licenses": {
    "BSD-3-Clause": { "name": "BSD 3-Clause \"New\" or \"Revised\" License", "url": "https://spdx.org/licenses/BSD-3-Clause.html", "spdxId": "BSD-3-Clause" },
    "Apache-2.0": { "name": "Apache License 2.0", "url": "https://spdx.org/licenses/Apache-2.0.html", "spdxId": "Apache-2.0" }
  }
}
"""
