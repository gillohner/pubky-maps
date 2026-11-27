package to.pubky.maps.osm

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import to.pubky.maps.links.GeoPoint
import to.pubky.maps.links.OsmId
import to.pubky.maps.links.OsmType
import to.pubky.maps.osm.db.NoteOutboxDatabase
import java.io.File
import java.nio.file.Files
import java.util.Properties
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** The notes waiting for a connection on this machine's SQLite (the app uses Android's). */
class SqlNoteOutboxTest {
    private val dir: File = Files.createTempDirectory("notes").toFile()

    @AfterTest
    fun cleanUp() {
        dir.deleteRecursively()
    }

    private fun open(create: Boolean): SqlNoteOutbox {
        val driver = JdbcSqliteDriver("jdbc:sqlite:${File(dir, "note-outbox.db").path}", Properties())
        if (create) NoteOutboxDatabase.Schema.create(driver)
        return SqlNoteOutbox(driver)
    }

    @Test
    fun waitingNotesAreKeptOverARestartOldestFirstWithTheirPlace() {
        val schober = NoteTarget(GeoPoint(47.3722, 8.5446), "Conditorei Schober", OsmId(OsmType.Way, 270803095))
        val pin = NoteTarget(GeoPoint(-33.8568, 151.2153))
        open(create = true).apply {
            put(WaitingNote("b", pin, "A bench is missing here.", 20))
            put(WaitingNote("a", schober, "Closed for good.", 10))
            put(WaitingNote("c", schober, "Gone.", 30))
            delete("c")
            setOnItsWay("b", true)
        }
        assertEquals(
            listOf(
                WaitingNote("a", schober, "Closed for good.", 10),
                WaitingNote("b", pin, "A bench is missing here.", 20, onItsWay = true),
            ),
            open(create = false).waiting(),
        )
        open(create = false).setOnItsWay("b", false)
        assertEquals(listOf(false, false), open(create = false).waiting().map { it.onItsWay })
    }
}
