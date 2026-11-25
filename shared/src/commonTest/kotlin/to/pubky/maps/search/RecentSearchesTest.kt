package to.pubky.maps.search

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import to.pubky.maps.links.GeoPoint
import to.pubky.maps.links.OsmId
import to.pubky.maps.links.OsmType
import to.pubky.maps.place.PlaceRef
import to.pubky.maps.place.PlaceSource
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

class RecentSearchesTest {
    private val kunsthaus =
        PlaceRef(
            uri = "https://www.openstreetmap.org/relation/3768127",
            name = "Kunsthaus",
            point = GeoPoint(47.3702241, 8.5479797),
            osm = OsmId(OsmType.Relation, 3768127),
            source = PlaceSource.Pinakes,
            zoom = 17.0,
            address = "Heimplatz 1, 8001 Zürich",
            category = "Museum",
        )

    @Test
    fun newestFirstOnePerTextAndPlace() {
        val list =
            emptyList<RecentSearch>()
                .withAdded(RecentSearch("Zoo"))
                .withAdded(RecentSearch("kunsthaus", kunsthaus))
                .withAdded(RecentSearch("zoo"))
                .withAdded(RecentSearch("Kunst", kunsthaus))
        assertEquals(listOf("Kunst", "zoo"), list.map { it.query })
        assertEquals(kunsthaus, list.first().place)
        // A place picked for a text replaces the text's own entry.
        val picked = listOf(RecentSearch("kunsthaus"), RecentSearch("zoo")).withAdded(RecentSearch("Kunsthaus", kunsthaus))
        assertEquals(listOf(RecentSearch("Kunsthaus", kunsthaus), RecentSearch("zoo")), picked)
        // Accents and case do not make another entry.
        assertEquals(1, listOf(RecentSearch("Zürich")).withAdded(RecentSearch("zurich")).size)
    }

    @Test
    fun keepsAtMostThirty() {
        val list = (1..40).fold(emptyList<RecentSearch>()) { acc, i -> acc.withAdded(RecentSearch("query $i")) }
        assertEquals(RecentSearchStore.MAX_ENTRIES, list.size)
        assertEquals("query 40", list.first().query)
    }

    @Test
    fun theFileKeepsTextsAndPlaces() {
        val entries = listOf(RecentSearch("kunsthaus", kunsthaus), RecentSearch("47.37, 8.54"))
        assertEquals(entries, decodeRecentSearches(encodeRecentSearches(entries)))
    }

    @Test
    fun entriesThisVersionCannotReadAreSkipped() {
        val json =
            """
            {"version":2,"entries":[
              {"query":"ok"},
              {"query":"from the future","place":{"uri":"x:1","source":"Teleporter"}},
              {"query":"bad point","place":{"uri":"x:2","source":"Photon","lat":95.0,"lon":8.0}},
              {"query":"  "},
              {"query":"place","place":{"uri":"geo:1,2","source":"Coordinates","lat":1.0,"lon":2.0,"later":true}}
            ]}
            """.trimIndent()
        assertEquals(listOf("ok", "place"), decodeRecentSearches(json).map { it.query })
    }

    private val fileSystem = FakeFileSystem()
    private val path = "/data/no_backup/recent-searches.json".toPath()

    @AfterTest
    fun noFileLeftOpen() {
        fileSystem.checkNoOpenFiles()
    }

    /** Runs [block] with a store on the test file, then closes it (DataStore allows one per file). */
    private suspend fun TestScope.withStore(block: suspend (RecentSearchStore) -> Unit) {
        val job = Job()
        val store = DataStoreRecentSearchStore(fileSystem, path, CoroutineScope(coroutineContext + job))
        try {
            block(store)
        } finally {
            job.cancelAndJoin()
        }
    }

    @Test
    fun theStoreKeepsEntriesAcrossRestartsAndClears() =
        runTest {
            fileSystem.createDirectories(path.parent!!)
            withStore { store ->
                store.add(RecentSearch("zoo"))
                store.add(RecentSearch("kunsthaus", kunsthaus))
                store.entries.first { it.size == 2 }
            }
            withStore { store ->
                assertEquals(listOf("kunsthaus", "zoo"), store.entries.first { it.isNotEmpty() }.map { it.query })
                assertEquals(
                    kunsthaus,
                    store.entries.value
                        .first()
                        .place,
                )
                store.clear()
                store.entries.first { it.isEmpty() }
            }
            withStore { store ->
                // Nothing comes back after a restart.
                store.add(RecentSearch("lugano"))
                assertEquals(listOf("lugano"), store.entries.first { it.isNotEmpty() }.map { it.query })
            }
        }

    @Test
    fun anUnreadableFileStartsAnEmptyHistory() =
        runTest {
            fileSystem.createDirectories(path.parent!!)
            fileSystem.write(path) { writeUtf8("{not json") }
            withStore { store ->
                store.add(RecentSearch("zoo"))
                assertEquals(listOf("zoo"), store.entries.first { it.isNotEmpty() }.map { it.query })
            }
        }
}
