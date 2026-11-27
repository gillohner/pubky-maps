package to.pubky.maps.osm

import app.cash.sqldelight.db.SqlDriver
import to.pubky.maps.links.GeoPoint
import to.pubky.maps.osm.db.NoteOutboxDatabase

/** A note no server could take when it was written, waiting on the phone for a connection. */
data class WaitingNote(
    /** The id of the note page it was written on, so a page still open shows when it went. */
    val id: String,
    val target: NoteTarget,
    /** What the user wrote, without the place and the app's tag ([noteText] adds them when it goes). */
    val text: String,
    /** When it was written, in epoch milliseconds: notes go in this order. */
    val writtenAt: Long,
    /** A try to send it was under way and its answer never came back: it may be on the map. */
    val onItsWay: Boolean = false,
)

/** The notes waiting for a connection, oldest first. Calls block on disk. */
interface NoteOutbox {
    fun waiting(): List<WaitingNote>

    fun put(note: WaitingNote)

    fun delete(id: String)

    /** Marks note [id] as on its way (before a try to send it) or as waiting again (no server reached). */
    fun setOnItsWay(
        id: String,
        onItsWay: Boolean,
    )
}

/** [NoteOutbox] in its own SQLite database: kept when the user signs out (notes are anonymous). */
class SqlNoteOutbox(
    driver: SqlDriver,
) : NoteOutbox {
    private val queries = NoteOutboxDatabase(driver).noteOutboxQueries

    override fun waiting(): List<WaitingNote> =
        queries
            .waitingNotes { id, lat, lon, name, osm, body, writtenAt, onItsWay ->
                WaitingNote(id, NoteTarget(GeoPoint(lat, lon), name, osm?.let(::osmIdOfRouteText)), body, writtenAt, onItsWay != 0L)
            }.executeAsList()

    override fun put(note: WaitingNote) {
        queries.putWaitingNote(
            note.id,
            note.target.point.lat,
            note.target.point.lon,
            note.target.name,
            note.target.osm?.routeText,
            note.text,
            note.writtenAt,
        )
    }

    override fun delete(id: String) {
        queries.deleteWaitingNote(id)
    }

    override fun setOnItsWay(
        id: String,
        onItsWay: Boolean,
    ) {
        queries.setOnItsWay(if (onItsWay) 1L else 0L, id)
    }
}
