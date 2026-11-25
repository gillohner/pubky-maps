package to.pubky.maps.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import to.pubky.maps.R
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
    fun anUnreadableListIsEmptyInsteadOfAnError() {
        assertTrue(parseLicences("").libraries.isEmpty())
        assertTrue(parseLicences("not json").libraries.isEmpty())
    }
}
