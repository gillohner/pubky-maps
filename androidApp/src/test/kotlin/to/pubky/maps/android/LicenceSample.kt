package to.pubky.maps.android

/** A fixed licence list in the AboutLibraries format, so tests do not change with every dependency update. */
internal const val SAMPLE_LICENCES_JSON = """
            {
              "libraries": [
                {
                  "uniqueId": "org.maplibre.compose:maplibre-compose",
                  "artifactVersion": "0.20.0",
                  "name": "MapLibre Compose",
                  "developers": [{ "name": "MapLibre" }],
                  "licenses": ["BSD-3-Clause"]
                },
                {
                  "uniqueId": "androidx.compose.ui:ui",
                  "artifactVersion": "1.12.0",
                  "name": "Compose UI",
                  "developers": [{ "name": "The Android Open Source Project" }],
                  "licenses": ["Apache-2.0"]
                },
                {
                  "uniqueId": "com.mikepenz:aboutlibraries-core",
                  "artifactVersion": "15.3.0",
                  "name": "AboutLibraries Core Library",
                  "developers": [{ "name": "Mike Penz" }],
                  "licenses": ["Apache-2.0"]
                }
              ],
              "licenses": {
                "BSD-3-Clause": { "name": "BSD 3-Clause License", "url": "https://spdx.org/licenses/BSD-3-Clause.html", "spdxId": "BSD-3-Clause" },
                "Apache-2.0": { "name": "Apache License 2.0", "url": "https://spdx.org/licenses/Apache-2.0.html", "spdxId": "Apache-2.0" }
              }
            }
        """
