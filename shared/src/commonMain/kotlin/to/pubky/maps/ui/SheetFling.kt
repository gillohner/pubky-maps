package to.pubky.maps.ui

import androidx.compose.ui.unit.dp

/**
 * How fast (per second) a finger must be moving when it lets go of a sheet for the sheet to go on to
 * its next height in that direction; a slower release goes to the nearest height. One rule for every
 * sheet (the search results and the place page) and for every part of it a finger moves it by (the
 * handle, the header, the list), so the same flick does the same everywhere, and in dp so it is the
 * same flick on a 2x and a 4x phone screen. 400 dp/s is a quick flick, not a drag let go of: it is
 * above Compose's default for draggable anchors (125 dp/s), which moves a sheet on after a slow drag
 * that ends a little away from where it started.
 */
internal val SheetFlingVelocity = 400.dp
