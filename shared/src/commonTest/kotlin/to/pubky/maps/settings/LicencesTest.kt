package to.pubky.maps.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LicencesTest {
    @Test
    fun theGeneratedListGivesEachLibraryItsLicence() {
        val libraries = parseLicences(SAMPLE_LICENCES_JSON).libraries.associateBy { it.uniqueId }

        assertEquals(setOf("org.maplibre.compose:maplibre-compose", "androidx.compose.ui:ui"), libraries.keys)
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
