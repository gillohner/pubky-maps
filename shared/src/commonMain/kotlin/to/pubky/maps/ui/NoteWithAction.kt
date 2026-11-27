package to.pubky.maps.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth

/**
 * [note] and, when there is one, [action] on one line if the note fits there on a single line (its
 * widest, unwrapped width), else the note on the whole width and the action under it at the end.
 * Both are centred on the line they share. Squeezed beside a wrapped note, the note would get a
 * narrow column (at 200 % font lines of two words, at the normal phone font one word alone on its
 * last line); under the note at the end is where Material's banners and snackbars put an action that
 * does not fit beside long text.
 */
@Composable
internal fun NoteWithAction(
    note: @Composable () -> Unit,
    action: (@Composable () -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Layout(
        contents = listOf(note, action ?: {}),
        modifier = modifier,
    ) { (noteParts, actionParts), constraints ->
        val actionPlaceable = actionParts.firstOrNull()?.measure(constraints.copy(minWidth = 0, minHeight = 0))
        val actionWidth = actionPlaceable?.width ?: 0
        val notePart = noteParts.single()
        val oneLine = notePart.maxIntrinsicWidth(Constraints.Infinity) + actionWidth
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else constraints.constrainWidth(oneLine)
        val beside = actionPlaceable == null || oneLine <= width
        val notePlaceable = notePart.measure(Constraints(maxWidth = (if (beside) width - actionWidth else width).coerceAtLeast(0)))
        val actionHeight = actionPlaceable?.height ?: 0
        val contentHeight = if (beside) maxOf(notePlaceable.height, actionHeight) else notePlaceable.height + actionHeight
        val height = constraints.constrainHeight(contentHeight)
        layout(width, height) {
            if (beside) {
                notePlaceable.placeRelative(0, (height - notePlaceable.height) / 2)
                actionPlaceable?.placeRelative(width - actionWidth, (height - actionHeight) / 2)
            } else {
                notePlaceable.placeRelative(0, 0)
                actionPlaceable.placeRelative(width - actionWidth, notePlaceable.height)
            }
        }
    }
}
