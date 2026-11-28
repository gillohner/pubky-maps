package to.pubky.maps.map

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okio.FileSystem
import okio.Path.Companion.toPath
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
        typePath: List<String> = emptyList(),
    ) = MapPlace(
        uri = uri,
        name = uri.substringAfterLast('/'),
        lat = 47.37,
        lon = 8.54,
        type = type,
        category = category,
        typePath = typePath,
    )

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
        // A type is known by its ancestors, as Pinakes sends them: a café is a FoodEstablishment.
        val path = listOf("Thing", "Organization", "Place", "LocalBusiness", "FoodEstablishment", "CafeOrCoffeeShop")
        assertEquals(
            PlaceKind.Food,
            pubkyPois(listOf(kunsthaus.copy(type = "CafeOrCoffeeShop", typePath = path)), found).highlights.single().kind,
        )
        // A type without its ancestors (a place kept offline) that is of no kind the app knows leaves it to the tile.
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

    /**
     * The layers of the default style [name], bottom up, as its server lists them (id, type, source,
     * source layer; recorded 2026-11-28 in src/commonTest/fixtures/style/layers.json).
     */
    private fun layersOf(name: String): List<StyleLayerRef> =
        Json
            .parseToJsonElement(FileSystem.SYSTEM.read("src/commonTest/fixtures/style/layers.json".toPath()) { readUtf8() })
            .jsonObject
            .getValue(name)
            .jsonObject
            .getValue("layers")
            .jsonArray
            .map { layer ->
                val (id, type, source, sourceLayer) = layer.jsonArray.map { it.jsonPrimitive.contentOrNull }
                StyleLayerRef(source, sourceLayer, id.orEmpty(), type.orEmpty())
            }

    @Test
    fun zoomedOutTheHighlightsGoUnderTheLowestLayerOfPlaceNames() {
        // The place names are the top layers of each default style, over its points of interest and
        // road names: OpenFreeMap's (OpenMapTiles' `place`) and VersaTiles' (Shortbread's `place_labels`).
        assertEquals("label_other", placeNamesLayerOf(layersOf("liberty")))
        assertEquals("place_other", placeNamesLayerOf(layersOf("dark")))
        assertEquals("label-place-neighbourhood", placeNamesLayerOf(layersOf("colorful")))
        assertEquals("label-place-neighbourhood", placeNamesLayerOf(layersOf("colorful-dark")))
        // Only a symbol layer of names of places counts, the lowest of them.
        assertEquals(
            "place_other",
            placeNamesLayerOf(
                listOf(
                    StyleLayerRef("openmaptiles", "place", "place-dots", "circle"),
                    StyleLayerRef("openmaptiles", "poi", "poi", "symbol"),
                    StyleLayerRef("openmaptiles", "place", "place_other", "symbol"),
                    StyleLayerRef("openmaptiles", "place", "place_city", "symbol"),
                ),
            ),
        )
        // A style without place names: the highlights stay on top at every zoom.
        assertNull(
            placeNamesLayerOf(listOf(StyleLayerRef("openmaptiles", "poi", "poi", "symbol"), StyleLayerRef(null, null, "bg", "background"))),
        )
    }

    @Test
    fun theHighlightsStartAtTheZoomOfThePubkyPlacesAndGoOverPlaceNamesFrom14() {
        // OpenMapTiles' tiles hold railway stations, halts and ferry terminals from zoom 12, the
        // zoom the Pubky places layer starts at.
        assertEquals(PoiZoom.None, poiZoomOf(11.99))
        assertEquals(PoiZoom.UnderPlaceNames, poiZoomOf(MapPlacesLayer.MIN_ZOOM))
        assertEquals(PoiZoom.UnderPlaceNames, poiZoomOf(13.99))
        assertEquals(PoiZoom.OverPlaceNames, poiZoomOf(14.0))
    }

    private val station = mapOf(12363833431 to mapOf("class" to "railway", "subclass" to "station"))
    private val cafe = mapOf(2708030951 to mapOf("class" to "cafe", "subclass" to "cafe"))

    /** [poisFoundOf] on a map at [zoom] that reads [tiles] each time it is asked; its answers and asks. */
    private class FoundPois(
        scope: TestScope,
        zoom: PoiZoom,
        var tiles: Map<Long, Map<String, String>>,
    ) {
        val zooms = MutableStateFlow(zoom)
        val places = MutableStateFlow(listOf<MapPlace>())
        val idle = MutableSharedFlow<Unit>()
        val answers = mutableListOf<Map<Long, Map<String, String>>>()
        var asks = 0

        init {
            scope.backgroundScope.launch(UnconfinedTestDispatcher(scope.testScheduler)) {
                poisFoundOf(zooms, places, idle) {
                    asks++
                    tiles
                }.collect { answers += it }
            }
        }
    }

    @Test
    fun zoomedOutUnder14ThePlacesAreDotsUntilTheMapRestsThenItsStationsAreHighlighted() =
        runTest {
            val map = FoundPois(this, PoiZoom.OverPlaceNames, tiles = station + cafe)
            // Asked at once, then each time the map is idle.
            assertEquals(listOf(station + cafe), map.answers)
            map.idle.emit(Unit)
            assertEquals(2, map.asks)
            // Zoomed out under 14 mid-gesture: the tiles of 14 still loaded hold the café, which the
            // highlights under 14 cannot draw; every place is a dot until the map rests.
            map.zooms.value = PoiZoom.UnderPlaceNames
            assertEquals(emptyMap(), map.answers.last())
            assertEquals(2, map.asks)
            // New places meanwhile are not asked of those tiles either.
            map.places.value = listOf(schober)
            assertEquals(emptyMap(), map.answers.last())
            assertEquals(2, map.asks)
            // Rested on the tiles of 13: only the station.
            map.tiles = station
            map.idle.emit(Unit)
            assertEquals(station, map.answers.last())
            // From then on new places are asked at once.
            map.places.value = listOf(schober, kunsthaus)
            assertEquals(4, map.asks)
        }

    @Test
    fun underTheZoomOfThePubkyPlacesNoneIsAskedAndZoomingInAsksAtOnce() =
        runTest {
            val map = FoundPois(this, PoiZoom.None, tiles = station)
            map.idle.emit(Unit)
            assertEquals(listOf(emptyMap<Long, Map<String, String>>()), map.answers)
            assertEquals(0, map.asks)
            // Zoomed in to 12: the stations are asked at once (a start at zoom 13 too).
            map.zooms.value = PoiZoom.UnderPlaceNames
            assertEquals(station, map.answers.last())
            // Zoomed in over 14, asked at once; zoomed out under 12, none.
            map.tiles = station + cafe
            map.zooms.value = PoiZoom.OverPlaceNames
            assertEquals(station + cafe, map.answers.last())
            map.zooms.value = PoiZoom.None
            assertEquals(emptyMap(), map.answers.last())
            assertEquals(2, map.asks)
        }
}
