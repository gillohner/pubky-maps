package to.pubky.maps.osm

/**
 * Darwin's errors are not told apart yet, so on iOS no failure counts as a note that never left (Ktor's
 * Darwin engine reports a connect timeout as a socket timeout): a note that fails there is
 * [NoteAnswer.Failed] or [NoteAnswer.NoAnswer], never kept to be sent again.
 */
internal actual fun failedBeforeSending(e: Throwable): Boolean = false
