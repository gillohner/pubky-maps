package to.pubky.maps.map

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import okio.FileSystem
import okio.Path.Companion.toPath
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

private const val OFM_SPRITE = "https://tiles.openfreemap.org/sprites/ofm_f384/ofm"

@OptIn(ExperimentalCoroutinesApi::class)
class StyleIconsTest {
    private fun read(name: String): String = FileSystem.SYSTEM.read("src/commonTest/fixtures/style".toPath() / name) { readUtf8() }

    private fun json(text: String): JsonElement = Json.parseToJsonElement(text)

    private val libertyUrl = "https://tiles.openfreemap.org/styles/liberty"
    private val colorfulUrl = "https://tiles.versatiles.org/assets/styles/colorful/style.json"
    private val liberty = json(read("liberty.style.json")).jsonObject
    private val colorful = json(read("colorful.style.json")).jsonObject
    private val omtPoi = PoiSource("openmaptiles", "poi")
    private val shortbreadPois = PoiSource("versatiles-shortbread", "pois")
    private val shortbreadTransit = PoiSource("versatiles-shortbread", "public_transport")

    private fun images(
        prefix: String,
        index: String,
    ) = parseSpriteIndex(read(index)).mapKeys { prefix + it.key }

    @Test
    fun openFreeMapsIconIsChosenByItsLayersFiltersInTheStylesOrder() {
        val icons = poiIconsOf(liberty, listOf(omtPoi), images("", "ofm.sprite.json"))
        val icon = icons.getValue(omtPoi)
        // poi_r20, poi_r7, poi_r1, poi_transit: each layer's filter (its comparisons guarded), then its icon; none gives no image.
        val choice = icon.image as JsonArray
        assertEquals(JsonPrimitive("case"), choice[0])
        assertEquals(2 * 4 + 2, choice.size)
        val layers = (liberty["layers"] as JsonArray).map { it.jsonObject }
        assertEquals(guardedComparisons(layers[0]["filter"]!!), choice[1])
        assertEquals((layers[0]["layout"] as JsonObject)["icon-image"], choice[2])
        assertEquals(layers[3]["filter"], choice[7])
        assertEquals(json("""["to-string", ["get", "class"]]"""), choice[8])
        assertEquals(JsonPrimitive(""), choice.last())
        assertTrue("cafe" in icon.known)
        // Its icons have their own disc (no SDF): its typical 19 dp icon is drawn at 17 dp.
        assertEquals(SPRITE_ICON_DP.toFloat(), icon.dp)
        assertTrue(abs(icon.size - 17f / 19f) < 0.001f, "size ${icon.size}")
    }

    @Test
    fun aComparisonOnAMissingPropertyIsFalseNotAFailureOfTheWholeChoice() {
        // Liberty's poi_r20: a feature without a rank would fail the case for every layer after it too.
        val r20 = json("""["all", ["match", ["geometry-type"], ["MultiPoint", "Point"], true, false], [">=", ["get", "rank"], 20]]""")
        assertEquals(
            json(
                """["all", ["match", ["geometry-type"], ["MultiPoint", "Point"], true, false],
                  ["all", ["==", ["typeof", ["get", "rank"]], ["typeof", 20]],
                    ["in", ["typeof", ["get", "rank"]], ["literal", ["number", "string"]]],
                    [">=", ["get", "rank"], 20]]]""",
            ),
            guardedComparisons(r20),
        )
        val choice = poiIconsOf(liberty, listOf(omtPoi), images("", "ofm.sprite.json")).getValue(omtPoi).image as JsonArray
        assertEquals(guardedComparisons(r20), choice[1])
        // A collator compares strings only: both sides must be strings; it keeps its collator.
        val collated = json("""["<", ["get", "name"], "m", ["collator", {}]]""")
        assertEquals(
            json(
                """["all", ["==", ["typeof", ["get", "name"]], ["typeof", "m"]],
                  ["in", ["typeof", ["get", "name"]], ["literal", ["string"]]], $collated]""",
            ),
            guardedComparisons(collated),
        )
        // A literal, a match's labels and other operators are left as they are; a match's outputs are guarded.
        val untouched =
            json(
                """["any", ["==", ["get", "kind"], "cafe"], ["literal", [">", 1, 2]], ["to-boolean", ["get", "shop"]],
                  ["match", ["get", "op"], [">", "<", "="], true, false]]""",
            )
        assertEquals(untouched, guardedComparisons(untouched))
        val output = json("""["match", ["get", "kind"], "cafe", [">", ["get", "rank"], 2], false]""")
        assertEquals(guardedComparisons(json("""[">", ["get", "rank"], 2]""")), (guardedComparisons(output) as JsonArray)[3])
    }

    @Test
    fun versaTilesIconsArePrefixedWithTheirSpritesIdAndDrawnAsSdf() {
        assertEquals(
            listOf(SpriteRef("base:", "https://tiles.versatiles.org/assets/sprites/base")),
            spriteRefsOf(colorful, colorfulUrl),
        )
        val icons = poiIconsOf(colorful, listOf(shortbreadPois, shortbreadTransit), images("base:", "versatiles-base.sprite.json"))
        val pois = icons.getValue(shortbreadPois)
        val choice = pois.image as JsonArray
        // A layer per OSM key: amenity, leisure, tourism, shop, man_made, historic, emergency, highway, office.
        assertEquals(2 * 9 + 2, choice.size)
        assertEquals(json("""["to-boolean", ["get", "amenity"]]"""), choice[1])
        assertTrue("base:icon-cafe" in pois.known)
        assertFalse("icon-cafe" in pois.known)
        assertEquals(SDF_ICON_DP.toFloat(), pois.dp)
        assertTrue(abs(pois.size - 14f / 32f) < 0.001f, "size ${pois.size}")
        // Its stops: one image per layer, picked by the layer's filter.
        val transit = icons.getValue(shortbreadTransit).image as JsonArray
        assertEquals(json("""["==", ["get", "kind"], "bus_stop"]"""), transit[1])
        assertEquals(JsonPrimitive("base:icon-bus"), transit[2])
        assertEquals(JsonPrimitive("base:transport-tram"), transit[4])
    }

    @Test
    fun aStyleWithoutItsPointsOfInterestOrSpriteGivesNone() {
        // OpenFreeMap's dark style draws no points of interest: the schema's source layer has no layer.
        val dark = JsonObject(liberty - "layers" + ("layers" to JsonArray(emptyList())))
        assertEquals(emptyMap(), poiIconsOf(dark, listOf(omtPoi), images("", "ofm.sprite.json")))
        assertEquals(emptyMap(), poiIconsOf(liberty, listOf(omtPoi), emptyMap()))
        assertEquals(emptyMap(), poiIconsOf(liberty, listOf(PoiSource("openmaptiles", "other")), images("", "ofm.sprite.json")))
    }

    @Test
    fun aLayerTheHighlightCannotTakeOverIsLeftOut() {
        val style =
            json(
                """
                {"layers": [
                  {"id": "legacy", "type": "symbol", "source": "s", "source-layer": "poi",
                   "filter": ["==", "class", "cafe"], "layout": {"icon-image": "cafe"}},
                  {"id": "zoomed", "type": "symbol", "source": "s", "source-layer": "poi",
                   "filter": [">=", ["zoom"], 15], "layout": {"icon-image": "bar"}},
                  {"id": "stepped", "type": "symbol", "source": "s", "source-layer": "poi",
                   "layout": {"icon-image": ["step", ["zoom"], "a", 15, "b"]}},
                  {"id": "function", "type": "symbol", "source": "s", "source-layer": "poi",
                   "layout": {"icon-image": {"property": "class", "type": "identity"}}},
                  {"id": "label", "type": "symbol", "source": "s", "source-layer": "poi", "layout": {"text-field": "{name}"}},
                  {"id": "tokens", "type": "symbol", "source": "s", "source-layer": "poi",
                   "filter": ["all", ["has", "class"]], "layout": {"icon-image": "{class}_11"}}
                ]}
                """.trimIndent(),
            ).jsonObject
        val sprite = mapOf("cafe_11" to SpriteImage(11.0, 11.0, 1.0, sdf = false))
        val icon = poiIconsOf(style, listOf(PoiSource("s", "poi")), sprite).getValue(PoiSource("s", "poi"))
        assertEquals(
            json("""["case", ["all", ["has", "class"]], ["concat", ["coalesce", ["to-string", ["get", "class"]], ""], "_11"], ""]"""),
            icon.image,
        )
    }

    @Test
    fun anIconMadeOfTokensIsTheConcatMapLibreMakesOfIt() {
        assertEquals(JsonPrimitive("cafe"), iconImageExpression(JsonPrimitive("cafe")))
        assertEquals(
            json("""["concat", "poi-", ["coalesce", ["to-string", ["get", "class"]], ""]]"""),
            iconImageExpression(JsonPrimitive("poi-{class}")),
        )
        assertEquals(JsonPrimitive("broken{class"), iconImageExpression(JsonPrimitive("broken{class")))
        assertNull(iconImageExpression(json("""["image", "cafe"]""")))
        assertNull(iconImageExpression(json("""["coalesce", ["feature-state", "icon"], "cafe"]""")))
        assertNull(iconImageExpression(JsonPrimitive(3)))
    }

    @Test
    fun oldStyleFiltersAreToldFromExpressionsAsMapLibreDoes() {
        for (old in listOf(
            """["==", "class", "cafe"]""",
            """["in", "class", "cafe", "bar"]""",
            """["!in", "class", "cafe"]""",
            """["!has", "name"]""",
            """["has", "${'$'}type"]""",
            """["all", ["==", "class", "cafe"], ["has", "name"]]""",
            """["none", ["==", "class", "cafe"]]""",
        )) {
            assertFalse(isExpressionFilter(json(old)), old)
        }
        for (expression in listOf(
            "true",
            """["==", ["get", "class"], "cafe"]""",
            """["in", ["get", "class"], ["literal", ["cafe", "bar"]]]""",
            """["has", "name"]""",
            """["to-boolean", ["get", "amenity"]]""",
            """["all", ["match", ["geometry-type"], ["Point"], true, false], [">=", ["get", "rank"], 7]]""",
        )) {
            assertTrue(isExpressionFilter(json(expression)), expression)
        }
    }

    @Test
    fun aSpritesUrlIsResolvedAgainstItsStyle() {
        val listed =
            json(
                """
                {"sprite": [{"id": "default", "url": "sprites/one"}, {"id": "x", "url": "/two"},
                  {"id": "y", "url": "mapbox://sprites/a/b"}]}
                """.trimIndent(),
            )
        assertEquals(
            listOf(SpriteRef("", "https://example.org/styles/sprites/one"), SpriteRef("x:", "https://example.org/two")),
            spriteRefsOf(listed.jsonObject, "https://example.org/styles/style.json"),
        )
        assertEquals(listOf(SpriteRef("", OFM_SPRITE)), spriteRefsOf(liberty, libertyUrl))
        assertEquals("$OFM_SPRITE.json", spriteIndexUrl(OFM_SPRITE))
        assertEquals("https://example.org/sprite.json?key=1", spriteIndexUrl("https://example.org/sprite?key=1"))
    }

    @Test
    fun theIconsAreReadOnceFromTheStyleAndItsSpriteIndex() =
        runTest {
            val requests = ArrayList<String>()
            var spriteDown = true
            val engine =
                MockEngine { request ->
                    val url = request.url.toString()
                    requests += url
                    val jsonType = headersOf(HttpHeaders.ContentType, "application/json")
                    when {
                        url == libertyUrl -> respond(read("liberty.style.json"), HttpStatusCode.OK, jsonType)
                        url == "$OFM_SPRITE.json" && !spriteDown -> respond(read("ofm.sprite.json"), HttpStatusCode.OK, jsonType)
                        else -> respondError(HttpStatusCode.ServiceUnavailable)
                    }
                }
            val icons = StyleIcons(HttpClient(engine))
            // A sprite index that cannot be read: not read (the badges stay), asked again the next time.
            assertNull(icons.of(libertyUrl, listOf(omtPoi)))
            spriteDown = false
            assertEquals(setOf(omtPoi), icons.of(libertyUrl, listOf(omtPoi))?.keys)
            assertEquals(setOf(omtPoi), icons.of(libertyUrl, listOf(omtPoi))?.keys)
            assertEquals(
                listOf(libertyUrl, "$OFM_SPRITE.json").let { it + it },
                requests,
            )
        }

    @Test
    fun theIconsAreReadOnceAPlaceIsHighlightedAgainAfterAFailureAndAfreshForAnotherStyle() =
        runTest {
            val light = IconsAsk(libertyUrl, listOf(omtPoi))
            val dark = IconsAsk("https://tiles.openfreemap.org/styles/dark", listOf(omtPoi))
            val icons = mapOf(omtPoi to PoiIcon(JsonPrimitive("cafe"), listOf("cafe"), 1f, 17f))
            val asks = MutableStateFlow<IconsAsk?>(null)
            val highlighted = MutableStateFlow(false)
            var reachable = false
            val reads = ArrayList<IconsAsk>()
            val seen = ArrayList<Map<PoiSource, PoiIcon>>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                styleIconsOf(asks, highlighted, retry = 30.seconds) { ask ->
                    reads += ask
                    if (reachable) icons else null
                }.collect { seen += it }
            }
            asks.value = light
            runCurrent()
            // A map without a highlighted place reads nothing.
            assertEquals(emptyList(), reads)
            highlighted.value = true
            runCurrent()
            assertEquals(listOf(light), reads)
            // Unreadable: asked again 30 s later, not before.
            reachable = true
            advanceTimeBy(29.seconds)
            assertEquals(1, reads.size)
            advanceTimeBy(2.seconds)
            assertEquals(listOf(light, light), reads)
            assertEquals(icons, seen.last())
            // Read once: kept, not asked again.
            advanceTimeBy(5.minutes)
            assertEquals(2, reads.size)
            // Another style: its own icons, none until they are read.
            asks.value = dark
            runCurrent()
            assertEquals(listOf(light, light, dark), reads)
            assertEquals(listOf(emptyMap(), emptyMap(), icons, emptyMap(), icons), seen)
            // No style in place: none.
            asks.value = null
            runCurrent()
            assertEquals(emptyMap(), seen.last())
        }
}
