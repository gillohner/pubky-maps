package to.pubky.maps.map

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import to.pubky.maps.links.OsmId
import to.pubky.maps.links.OsmType
import to.pubky.maps.place.MapPlace
import to.pubky.maps.search.PlaceKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PubkyPoisTest {
    private fun place(
        uri: String,
        category: String? = null,
        type: String? = null,
    ) = MapPlace(uri = uri, name = uri.substringAfterLast('/'), lat = 47.37, lon = 8.54, type = type, category = category)

    private val schober = place("https://www.openstreetmap.org/node/270803095", category = "amenity/cafe")
    private val kunsthaus = place("https://www.openstreetmap.org/relation/3768127", type = "Museum")
    private val grossmuenster = place("https://www.openstreetmap.org/way/36916418")
    private val wikidata = place("https://www.wikidata.org/wiki/Q72")

    @Test
    fun aPlaceHasTheTileIdsOfItsElement() {
        assertEquals(listOf(2708030951, 2708030950), tileIdsOf(OsmId(OsmType.Node, 270803095)))
        assertEquals(listOf(369164182L), tileIdsOf(OsmId(OsmType.Way, 36916418)))
        assertEquals(listOf(37681273L), tileIdsOf(OsmId(OsmType.Relation, 3768127)))
        // Each id is read back as its element.
        for (id in tileIdsOf(OsmId(OsmType.Node, 270803095))) assertEquals(OsmId(OsmType.Node, 270803095), osmIdOfTileFeature(id))
    }

    @Test
    fun onlyPlacesThatAreOsmElementsAreAskedOfTheTiles() {
        assertEquals(
            listOf(2708030951, 2708030950, 37681273, 369164182),
            tileIdsOf(listOf(schober, kunsthaus, grossmuenster, wikidata, schober)),
        )
    }

    @Test
    fun placesTheTilesHoldAreHighlightedAndTheOthersStayDots() {
        val found = mapOf(2708030951 to mapOf("class" to "cafe", "subclass" to "cafe"), 37681273L to mapOf("subclass" to "museum"))
        val pois = pubkyPois(listOf(schober, kunsthaus, grossmuenster, wikidata), found)
        assertEquals(
            listOf(PoiHighlight(schober, listOf(2708030951), PlaceKind.Food), PoiHighlight(kunsthaus, listOf(37681273), PlaceKind.Culture)),
            pois.highlights,
        )
        assertEquals(listOf(grossmuenster, wikidata), pois.dots)
        assertEquals(schober, pois.placeOf(2708030951))
        assertNull(pois.placeOf(369164182))
    }

    @Test
    fun withNothingFoundEveryPlaceIsADot() {
        val places = listOf(schober, kunsthaus, wikidata)
        assertEquals(PubkyPois(dots = places), pubkyPois(places, emptyMap()))
    }

    @Test
    fun aPlaceListedTwiceIsHighlightedOnce() {
        // Pinakes can hold the same element under two cards (http and https links).
        val again = schober.copy(uri = "http://www.openstreetmap.org/node/270803095")
        val pois = pubkyPois(listOf(schober, again), mapOf(2708030951 to emptyMap()))
        assertEquals(listOf(schober), pois.highlights.map { it.place })
        assertEquals(emptyList(), pois.dots)
    }

    @Test
    fun anAggregatedTransitStopIsFoundByItsOtherId() {
        val hb = place("https://www.openstreetmap.org/node/1236383343")
        val pois = pubkyPois(listOf(hb), mapOf(12363833430 to mapOf("kind" to "station", "name" to "Zürich HB")))
        assertEquals(listOf(PoiHighlight(hb, listOf(12363833430), PlaceKind.Transit)), pois.highlights)
    }

    @Test
    fun theKindIsPinakesCategoryThenItsSchemaTypeThenTheTiles() {
        val found = mapOf(2708030951 to mapOf("amenity" to "theatre"), 37681273L to mapOf("class" to "shop", "subclass" to "books"))
        // Pinakes' category wins over the tile's.
        assertEquals(PlaceKind.Food, pubkyPois(listOf(schober), found).highlights.single().kind)
        // Without a category, the schema type wins over the tile's kind.
        assertEquals(PlaceKind.Culture, pubkyPois(listOf(kunsthaus), found).highlights.single().kind)
        // A type of no kind the app knows leaves it to the tile.
        val cafe = kunsthaus.copy(type = "CafeOrCoffeeShop")
        assertEquals(PlaceKind.Shop, pubkyPois(listOf(cafe), found).highlights.single().kind)
        // A category that says no kind leaves it to the tile.
        val office = schober.copy(category = "office/company")
        assertEquals(PlaceKind.Culture, pubkyPois(listOf(office), found).highlights.single().kind)
        // Nothing says a kind.
        assertEquals(PlaceKind.Other, pubkyPois(listOf(cafe), mapOf(37681273L to emptyMap())).highlights.single().kind)
    }

    @Test
    fun theKindOfATileFeatureIsReadInBothSchemas() {
        // Shortbread names the OSM key.
        assertEquals(PlaceKind.Food, kindOfTileFeature(mapOf("amenity" to "restaurant")))
        assertEquals(PlaceKind.Shop, kindOfTileFeature(mapOf("shop" to "books")))
        assertEquals(PlaceKind.Culture, kindOfTileFeature(mapOf("tourism" to "museum")))
        // Its stops carry only their kind.
        assertEquals(PlaceKind.Transit, kindOfTileFeature(mapOf("kind" to "bus_stop", "name" to "Central")))
        // OpenMapTiles: the subclass is the OSM value; a shop value goes as a shop.
        assertEquals(PlaceKind.Food, kindOfTileFeature(mapOf("class" to "cafe", "subclass" to "cafe")))
        assertEquals(PlaceKind.Culture, kindOfTileFeature(mapOf("class" to "museum", "subclass" to "museum")))
        assertEquals(PlaceKind.Shop, kindOfTileFeature(mapOf("class" to "clothing_store", "subclass" to "clothes")))
        assertEquals(PlaceKind.Nature, kindOfTileFeature(mapOf("class" to "park", "subclass" to "park")))
        assertEquals(PlaceKind.Other, kindOfTileFeature(mapOf("class" to "office", "subclass" to "company")))
        assertEquals(PlaceKind.Other, kindOfTileFeature(emptyMap()))
    }

    @Test
    fun poiSourcesAreTheLayersTheStyleDrawsElseTheSchemas() {
        // OpenFreeMap Liberty draws `poi`; VersaTiles Colorful `pois` and `public_transport`.
        assertEquals(
            listOf(PoiSource("openmaptiles", "poi")),
            poiSourcesOf(
                listOf(
                    StyleLayerRef(null, null),
                    StyleLayerRef("openmaptiles", "transportation"),
                    StyleLayerRef("openmaptiles", "poi"),
                    StyleLayerRef("openmaptiles", "poi"),
                    StyleLayerRef("ne2_shaded", null),
                ),
            ),
        )
        assertEquals(
            listOf(PoiSource("versatiles-shortbread", "pois"), PoiSource("versatiles-shortbread", "public_transport")),
            poiSourcesOf(
                listOf(
                    StyleLayerRef("versatiles-shortbread", "streets"),
                    StyleLayerRef("versatiles-shortbread", "pois"),
                    StyleLayerRef("versatiles-shortbread", "public_transport"),
                    StyleLayerRef("versatiles-landcover", "landcover"),
                ),
            ),
        )
        // OpenFreeMap Dark draws no points of interest: its schema's layer is used.
        assertEquals(
            listOf(PoiSource("openmaptiles", "poi")),
            poiSourcesOf(listOf(StyleLayerRef("openmaptiles", "transportation"), StyleLayerRef("openmaptiles", "water"))),
        )
        assertEquals(listOf(PoiSource("s", "pois")), poiSourcesOf(listOf(StyleLayerRef("s", "streets"))))
        // A source of an unknown schema has none.
        assertEquals(emptyList(), poiSourcesOf(listOf(StyleLayerRef("raster", null), StyleLayerRef("other", "roads"))))
    }

    @Test
    fun theFontIsABoldOneOfTheStyles() {
        val fonts = listOf("""["Noto Sans Italic"]""", """["Noto Sans Regular"]""", """["literal",["Noto Sans Bold"]]""", "null")
        assertEquals(listOf("Noto Sans Bold"), highlightFont(fonts.map { Json.parseToJsonElement(it) } + null))
        assertEquals(listOf("noto_sans_regular"), highlightFont(listOf(Json.parseToJsonElement("""["noto_sans_regular"]"""))))
        assertEquals(listOf("Open Sans Italic"), highlightFont(listOf(Json.parseToJsonElement("""["Open Sans Italic"]"""))))
        // An expression with more than names is no list of fonts.
        assertNull(highlightFont(listOf(Json.parseToJsonElement("""["coalesce",["get","font"],"x"]"""), null)))
        assertNull(highlightFont(emptyList()))
    }

    @Test
    fun aTileIdIsReadAsWrittenOrAsADecimal() {
        assertEquals(2708030951, tileIdOf(JsonPrimitive(2708030951)))
        assertEquals(2708030951, tileIdOf(JsonPrimitive(2708030951.0)))
        assertEquals(2708030951, tileIdOf(JsonPrimitive("2708030951")))
        assertNull(tileIdOf(JsonPrimitive(1.5)))
        assertNull(tileIdOf(JsonPrimitive("x")))
        assertNull(tileIdOf(null))
    }
}
