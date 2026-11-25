package to.pubky.maps.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
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
    fun anUnreadableListIsEmptyInsteadOfAnError() {
        assertTrue(parseLicences("").libraries.isEmpty())
        assertTrue(parseLicences("not json").libraries.isEmpty())
    }
}
