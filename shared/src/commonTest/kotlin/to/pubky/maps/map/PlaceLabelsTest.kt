package to.pubky.maps.map

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PlaceLabelsTest {
    private fun omt(
        name: String,
        placeClass: String,
        rank: Int? = null,
        distance: Double = 0.0,
    ) = PlaceLabel(listOfNotNull("name" to name, "class" to placeClass, rank?.let { "rank" to "$it" }).toMap(), distance)

    private fun shortbread(
        name: String,
        kind: String,
        population: Int? = null,
        distance: Double = 0.0,
    ) = PlaceLabel(listOfNotNull("name" to name, "kind" to kind, population?.let { "population" to "$it" }).toMap(), distance)

    @Test
    fun aCityNamesTheAreaBeforeItsTownsAndSuburbs() {
        val labels =
            listOf(
                omt("Wiedikon", "suburb", distance = 1.0),
                omt("Schlieren", "town", distance = 40.0),
                omt("Zürich", "city", rank = 3, distance = 90.0),
                omt("Uitikon", "village", distance = 5.0),
            )
        assertEquals("Zürich", areaPlaceName(labels))
    }

    @Test
    fun withoutAnyTownTheVillageOrPartOfTownNamesIt() {
        assertEquals("Uitikon", areaPlaceName(listOf(omt("Wiedikon", "suburb"), omt("Uitikon", "village", distance = 50.0))))
        assertEquals("Wiedikon", areaPlaceName(listOf(omt("Sihlfeld", "neighbourhood"), omt("Wiedikon", "suburb", distance = 50.0))))
    }

    @Test
    fun amongPlacesOfOneKindTheLargerWinsThenTheNearest() {
        // OpenMapTiles: a lower rank is a more important place.
        assertEquals(
            "Winterthur",
            areaPlaceName(listOf(omt("Frauenfeld", "city", rank = 9), omt("Winterthur", "city", rank = 6, distance = 80.0))),
        )
        // Shortbread: population; a capital counts as a city, so the larger city beside it wins.
        assertEquals(
            "Zürich",
            areaPlaceName(
                listOf(shortbread("Bern", "capital", population = 134_000), shortbread("Zürich", "state_capital", population = 421_000)),
            ),
        )
        assertEquals("Adliswil", areaPlaceName(listOf(omt("Thalwil", "town", distance = 30.0), omt("Adliswil", "town", distance = 10.0))))
    }

    @Test
    fun countriesRegionsAndIslandsNameNoArea() {
        val labels =
            listOf(
                omt("Schweiz", "country"),
                omt("Zürich", "state"),
                omt("Ufenau", "island"),
                shortbread("Lützelau", "island"),
                shortbread("Hinterberg", "locality"),
            )
        assertNull(areaPlaceName(labels))
        assertNull(areaPlaceName(emptyList()))
    }

    @Test
    fun aLabelWithoutANameIsSkipped() {
        val unnamed = PlaceLabel(mapOf("class" to "city", "name" to " "), 0.0)
        assertEquals("Uster", areaPlaceName(listOf(unnamed, omt("Uster", "town"))))
        assertEquals("Lugano", areaPlaceName(listOf(PlaceLabel(mapOf("kind" to "city", "name_int" to "Lugano"), 0.0))))
    }
}
