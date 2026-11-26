package to.pubky.maps.offline

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.request.forms.FormDataContent
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import to.pubky.maps.links.OsmId
import to.pubky.maps.links.OsmType
import to.pubky.maps.place.KeptDetails
import to.pubky.maps.place.OsmDetails
import to.pubky.maps.search.Fixtures
import to.pubky.maps.search.SourceAnswer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class OverpassDetailsTest {
    private val zurich = AreaBounds(west = 8.51, south = 47.36, east = 8.56, north = 47.39)

    @Test
    fun theQueryAsksForTheNamedElementsWithADetailAsCsvOfThoseTagsEndingWithTheEndLine() {
        val keys = OsmDetails.KEYS.joinToString("|")
        val columns = OsmDetails.KEYS.joinToString(",") { "\"$it\"" }
        assertEquals(
            "[out:csv(::type,::id,$columns;false)][timeout:180][bbox:47.360000,8.510000,47.390000,8.560000];" +
                "nwr[name][~\"^($keys)$\"~\".\"];out qt;make end;out;",
            overpassDetailsQuery(zurich),
        )
        // Coordinates never in exponent notation, which Overpass does not read.
        assertTrue("[bbox:-0.000100,0.000010,0.500000,-120.000000]" in overpassDetailsQuery(AreaBounds(0.00001, -0.0001, -120.0, 0.5)))
    }

    @Test
    fun aRealAnswerGivesEachElementTheTagsItHasQuotedValuesReadWhole() {
        val details = assertNotNull(parseOverpassDetails(Fixtures.read("zurich.overpass.tsv"))).associateBy { it.osm }
        assertEquals(5, details.size)
        assertEquals(
            mapOf(
                "opening_hours" to "Tu-Su 10:00-18:00; Th 10:00-20:00; PH 10:00-18:00",
                "website" to "https://www.kunsthaus.ch/",
                "wheelchair" to "yes",
            ),
            details.getValue(OsmId(OsmType.Relation, 3768127)).facts,
        )
        val antica = details.getValue(OsmId(OsmType.Node, 317024417)).facts
        // Quoted for its comma.
        assertEquals("Mo-Fr 11:30-14:00,18:00-22:00; Sa 18:00-22:30; Su off", antica["opening_hours"])
        assertEquals("italian", antica["cuisine"])
        assertEquals("no", antica["wheelchair"])
        assertEquals(
            "Mo-Fr 07:45-12:00,13:15-18:00; Sa 09:00-12:00 open \"Rufen Sie uns an!\"",
            details.getValue(OsmId(OsmType.Node, 13206440557)).facts["opening_hours"],
        )
        // A value over two lines stays one value; the element's other cells are read too.
        val orinad = details.getValue(OsmId(OsmType.Node, 2518316000)).facts
        assertEquals(
            "\"By appointment at https://www.phorest.com/salon/orinadcoiffurewestlink\nI can't see a sign with regular hours\"",
            orinad["opening_hours"],
        )
        assertEquals("Orinad Look", orinad["operator"])
        assertEquals("Lärche-Märt VOI Migros Partner", details.getValue(OsmId(OsmType.Node, 353000720)).facts["operator"])
    }

    @Test
    fun anAnswerThatDoesNotEndWithTheEndLineWasCutOff() {
        val whole = Fixtures.read("zurich.overpass.tsv")
        assertNull(parseOverpassDetails(""), "a timeout answers 200 with nothing")
        assertNull(parseOverpassDetails(whole.substringBefore("end\t")))
        // Cut inside a quoted value: the rest would read as one long cell.
        assertNull(parseOverpassDetails(whole.substringBefore("I can't see")))
        assertNull(parseOverpassDetails(whole + "node\t1\tMo-Fr 08:00-12:00" + "\t".repeat(OsmDetails.KEYS.size - 1) + "\n"))
        assertEquals(emptyList(), parseOverpassDetails("end\t1" + "\t".repeat(OsmDetails.KEYS.size) + "\n"), "an area with no details")
    }

    @Test
    fun aRecordWithAnotherNumberOfCellsOrAnUnknownTypeIsLeftOut() {
        val empty = "\t".repeat(OsmDetails.KEYS.size - 1)
        val text =
            "node\t1\t24/7$empty\n" +
                "node\t2\t24/7\n" +
                "area\t3\t24/7$empty\n" +
                "node\tx\t24/7$empty\n" +
                "way\t4$empty\t\n" +
                "end\t1$empty\t\n"
        assertEquals(listOf(ElementDetails(OsmId(OsmType.Node, 1), mapOf("opening_hours" to "24/7"))), parseOverpassDetails(text))
    }

    @Test
    fun everyKeptTagIsOneThePlacePageShows() {
        val values =
            mapOf(
                "email" to "a@example.ch",
                "contact:email" to "a@example.ch",
                "website" to "example.ch",
                "contact:website" to "example.ch",
            )
        for (key in OsmDetails.KEYS) {
            assertNotNull(OsmDetails.from(mapOf(key to (values[key] ?: "yes"))), key)
        }
    }

    private class FakeClock(
        var now: Instant = Instant.fromEpochSeconds(1_800_000_000),
    ) : Clock {
        override fun now(): Instant = now
    }

    @Test
    fun theQueryIsPostedToTheServersInTurnACutOffOrRefusedAnswerGoingToTheNext() =
        runTest {
            val asked = mutableListOf<Pair<String, String?>>()
            val answers = Fixtures.read("zurich.overpass.tsv")
            val engine =
                MockEngine { request ->
                    asked += request.url.host to (request.body as? FormDataContent)?.formData?.get("data")
                    when (request.url.host) {
                        "busy.example" -> respondError(HttpStatusCode.TooManyRequests, headers = headersOf(HttpHeaders.RetryAfter, "120"))
                        "cut.example" -> respond(answers.substringBefore("end\t"), HttpStatusCode.OK)
                        else -> respond(answers, HttpStatusCode.OK)
                    }
                }
            val clock = FakeClock()
            val servers =
                listOf("https://busy.example/api/interpreter", "https://cut.example/api/interpreter", "https://ok.example/api/interpreter")
            val overpass = OverpassPlaceDetails(HttpClient(engine) { expectSuccess = false }, { servers }, clock)

            val answer = assertIs<SourceAnswer.Answered<List<ElementDetails>>>(overpass.detailsIn(zurich))
            assertEquals("https://ok.example/api/interpreter", answer.server)
            assertEquals(5, answer.value.size)
            assertEquals(listOf("busy.example", "cut.example", "ok.example"), asked.map { it.first })
            assertEquals(List(3) { overpassDetailsQuery(zurich) }, asked.map { it.second })

            asked.clear()
            overpass.detailsIn(zurich)
            assertEquals(listOf("cut.example", "ok.example"), asked.map { it.first }, "the server that said 429 waits its Retry-After")
            clock.now += 121.seconds
            asked.clear()
            overpass.detailsIn(zurich)
            assertEquals("busy.example", asked.first().first)

            val none =
                OverpassPlaceDetails(HttpClient(engine) { expectSuccess = false }, { listOf("https://cut.example/api/interpreter") }, clock)
            assertEquals(SourceAnswer.Unreachable, none.detailsIn(zurich))
        }
}

/** Details of any area: one element per area, named after its west edge. */
private class FakeOverpass : PlaceDetailsSource {
    val asked = mutableListOf<Pair<AreaBounds, Long>>()
    var down = false
    var clock: () -> Long = { 0 }

    override suspend fun detailsIn(bounds: AreaBounds): SourceAnswer<List<ElementDetails>> {
        asked += bounds to clock()
        if (down) return SourceAnswer.Unreachable
        return SourceAnswer.Answered(
            listOf(ElementDetails(OsmId(OsmType.Node, (bounds.west * 100).toLong()), mapOf("phone" to "+41"))),
            "o",
        )
    }
}

private class MemoryDetails : OfflineDetailsStore {
    val kept = mutableMapOf<String, Pair<Long, List<ElementDetails>>>()
    var broken = false

    override fun areas(): Map<String, Long> {
        if (broken) error("disk")
        return kept.mapValues { it.value.first }
    }

    override fun replace(
        area: String,
        fetchedAt: Long,
        details: List<ElementDetails>,
    ) {
        kept[area] = fetchedAt to details
    }

    override fun delete(area: String) {
        kept.remove(area)
    }

    override fun detailsOf(osm: OsmId): KeptDetails? = error("not used")
}

@OptIn(ExperimentalCoroutinesApi::class)
class OfflinePlaceDetailsTest {
    private val zurich = AreaBounds(west = 8.51, south = 47.36, east = 8.56, north = 47.39)
    private val bern = AreaBounds(west = 7.42, south = 46.93, east = 7.47, north = 46.96)
    private val start = 1_800_000_000_000

    private fun area(
        id: String,
        bounds: AreaBounds?,
        status: OfflineMapStatus = OfflineMapStatus.Ready,
    ) = OfflineMap(id = id, name = id, createdAt = 0, status = status, bytes = 0, bounds = bounds)

    private class Running(
        val maps: MutableStateFlow<OfflineMapsState>,
        val overpass: FakeOverpass,
        val store: MemoryDetails,
        var unmetered: Boolean,
    )

    private fun keeping(
        vararg maps: OfflineMap,
        unmetered: Boolean = true,
        store: MemoryDetails = MemoryDetails(),
        test: suspend TestScope.(Running) -> Unit,
    ) = runTest {
        val running = Running(MutableStateFlow<OfflineMapsState>(OfflineMapsState.Ready(maps.toList())), FakeOverpass(), store, unmetered)
        val clock =
            object : Clock {
                override fun now() = Instant.fromEpochMilliseconds(start + testScheduler.currentTime)
            }
        running.overpass.clock = { testScheduler.currentTime }
        val job = Job()
        OfflinePlaceDetails(
            maps = running.maps,
            source = running.overpass,
            store = running.store,
            scope = CoroutineScope(coroutineContext + job),
            unmetered = { running.unmetered },
            clock = clock,
            io = StandardTestDispatcher(testScheduler),
        ).start()
        try {
            test(running)
        } finally {
            job.cancel()
        }
    }

    private fun TestScope.after(time: Duration) {
        advanceTimeBy(time)
        runCurrent()
    }

    @Test
    fun eachListedAreasDetailsAreFetchedOneAtATimeTenSecondsApartAndKept() =
        keeping(area("zh", zurich, OfflineMapStatus.Downloading(percent = 10)), area("be", bern), area("lost", null)) { running ->
            after(1.seconds)
            assertEquals(listOf(zurich to 0L), running.overpass.asked)
            after(10.seconds)
            assertEquals(listOf(zurich to 0L, bern to 10_000L), running.overpass.asked)
            assertEquals(setOf("zh", "be"), running.store.kept.keys)
            assertEquals(
                start,
                running.store.kept
                    .getValue("zh")
                    .first,
            )
            assertEquals(
                listOf(OsmId(OsmType.Node, 851)),
                running.store.kept
                    .getValue("zh")
                    .second
                    .map { it.osm },
            )
        }

    @Test
    fun keptDetailsAreFetchedAgainAfterAWeekOnlyOnAnUnmeteredNetwork() =
        keeping(area("zh", zurich), unmetered = false) { running ->
            after(1.seconds)
            assertEquals(1, running.overpass.asked.size, "the first fetch goes out on any network, with the download")
            after(7.days)
            assertEquals(1, running.overpass.asked.size, "a week later, on mobile data: not yet")
            running.unmetered = true
            after(30.minutes)
            assertEquals(2, running.overpass.asked.size)
            assertTrue(
                running.store.kept
                    .getValue("zh")
                    .first > start + 7.days.inWholeMilliseconds,
            )
            after(1.days)
            assertEquals(2, running.overpass.asked.size)
        }

    @Test
    fun aDeletedAreasDetailsAreDeleted() =
        keeping(area("zh", zurich), area("be", bern)) { running ->
            after(15.seconds)
            running.maps.value = OfflineMapsState.Ready(listOf(area("be", bern)))
            after(1.seconds)
            assertEquals(setOf("be"), running.store.kept.keys)
            assertEquals(2, running.overpass.asked.size, "a list with fewer areas fetches nothing")
        }

    @Test
    fun anAreaThatFailedIsTriedAgainLaterAfterTheOthersAndASyncStopsAtTheSecondFailure() =
        keeping(area("zh", zurich), area("be", bern), area("ge", AreaBounds(6.1, 46.18, 6.17, 46.22))) { running ->
            running.overpass.down = true
            after(15.seconds)
            assertEquals(listOf(zurich, bern), running.overpass.asked.map { it.first }, "no connection fails every area alike")
            running.overpass.down = false
            running.overpass.asked.clear()
            after(30.minutes + 20.seconds)
            assertEquals(listOf(6.1, 8.51, 7.42), running.overpass.asked.map { it.first.west }, "the areas that failed go last")
            assertEquals(setOf("zh", "be", "ge"), running.store.kept.keys)
        }

    @Test
    fun aDiskThatFailsIsTriedAgainAtTheNextTick() =
        keeping(area("zh", zurich), store = MemoryDetails().apply { broken = true }) { running ->
            after(1.seconds)
            assertEquals(emptyList(), running.overpass.asked)
            running.store.broken = false
            after(30.minutes)
            assertEquals(1, running.overpass.asked.size)
        }
}
