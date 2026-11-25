package to.pubky.maps.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import to.pubky.maps.R
import to.pubky.maps.settings.mergeLicenceLists
import to.pubky.maps.settings.parseLicences

/** On Android the licence list is parsed with org.json, so these run on Robolectric. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LicencesTest {
    @Test
    fun theGeneratedListGivesEachLibraryItsLicence() {
        val libraries = parseLicences(SAMPLE_LICENCES_JSON).libraries.associateBy { it.uniqueId }

        assertEquals(
            setOf("org.maplibre.compose:maplibre-compose", "androidx.compose.ui:ui", "com.mikepenz:aboutlibraries-core"),
            libraries.keys,
        )
        assertEquals(
            "BSD-3-Clause",
            libraries
                .getValue("org.maplibre.compose:maplibre-compose")
                .licenses
                .single()
                .spdxId,
        )
        assertEquals(
            "Apache-2.0",
            libraries
                .getValue("androidx.compose.ui:ui")
                .licenses
                .single()
                .spdxId,
        )
    }

    @Test
    fun theListBuiltIntoTheAppNamesMapLibreAndItsLicence() {
        // The list the AboutLibraries plugin generated for this build, as the licences screen reads it.
        val json =
            RuntimeEnvironment
                .getApplication()
                .resources
                .openRawResource(R.raw.aboutlibraries)
                .bufferedReader()
                .use { it.readText() }
        val libraries = parseLicences(json).libraries

        assertTrue("only ${libraries.size} libraries in the generated list", libraries.size >= 50)
        val mapLibre = libraries.single { it.uniqueId == "org.maplibre.compose:maplibre-compose" }
        assertEquals("BSD-3-Clause", mapLibre.licenses.single().spdxId)
        val withoutLicence = libraries.filter { it.licenses.isEmpty() }.map { it.uniqueId }
        assertTrue("no licence for $withoutLicence", withoutLicence.isEmpty())
    }

    @Test
    fun theRustCratesOfTheCoreAreListedWithTheirLicencesNextToTheGradleLibraries() {
        // As MainActivity reads them: both generated lists, merged.
        val resources = RuntimeEnvironment.getApplication().resources
        val read = { id: Int -> resources.openRawResource(id).bufferedReader().use { it.readText() } }
        val libraries =
            parseLicences(mergeLicenceLists(read(R.raw.aboutlibraries), read(R.raw.rust_licences)))
                .libraries
                .associateBy { it.uniqueId }

        assertTrue("MapLibre is still listed", "org.maplibre.compose:maplibre-compose" in libraries)
        val pubky = libraries.getValue("crates.io:pubky")
        assertEquals("MIT", pubky.licenses.single().name)
        val uniffi = libraries.getValue("crates.io:uniffi_core")
        assertEquals("MPL-2.0", uniffi.licenses.single().name)
        // MPL-2.0: the list points to the crate's source.
        assertTrue(uniffi.website.orEmpty().startsWith("https://"))
        // Licence files with copyright lines are shown as they are (tokio is MIT, with its own file).
        assertTrue(libraries.getValue("crates.io:tokio").licenses.any { it.licenseContent.orEmpty().contains("Copyright") })
        val rustCrates = libraries.keys.count { it.startsWith("crates.io:") }
        assertTrue("only $rustCrates Rust crates", rustCrates >= 100)
        val withoutLicence = libraries.values.filter { it.licenses.isEmpty() }.map { it.uniqueId }
        assertTrue("no licence for $withoutLicence", withoutLicence.isEmpty())
    }

    @Test
    fun mergingKeepsEveryLibraryAndLicenceAndSkipsAnUnreadableList() {
        val merged =
            mergeLicenceLists(
                SAMPLE_LICENCES_JSON,
                """{"libraries":[{"uniqueId":"crates.io:x","name":"x","licenses":["rust-1"]}],""" +
                    """"licenses":{"rust-1":{"name":"MIT","hash":"rust-1","content":"Copyright x"}}}""",
                "not json",
            )
        val libraries = parseLicences(merged).libraries.associateBy { it.uniqueId }

        assertEquals(4, libraries.size)
        assertEquals(
            "Copyright x",
            libraries
                .getValue("crates.io:x")
                .licenses
                .single()
                .licenseContent,
        )
        assertEquals(
            "BSD-3-Clause",
            libraries
                .getValue("org.maplibre.compose:maplibre-compose")
                .licenses
                .single()
                .spdxId,
        )
    }

    @Test
    fun anUnreadableListIsEmptyInsteadOfAnError() {
        assertTrue(parseLicences("").libraries.isEmpty())
        assertTrue(parseLicences("not json").libraries.isEmpty())
    }
}
