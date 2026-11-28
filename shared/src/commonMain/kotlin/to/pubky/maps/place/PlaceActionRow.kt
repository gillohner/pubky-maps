package to.pubky.maps.place

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import to.pubky.maps.routing.RoutingIcons
import to.pubky.maps.ui.AppIcons
import to.pubky.maps.write.WriteIcons

/**
 * How a labelled place page action stands out: the main one filled, the place's own one (Bookmark, or
 * Share without writes) and a chosen start tonal, the others outlined. Icons have no words to say
 * which tonal one is on, so only Directions is filled and only an [PlaceAction.on] one tonal there.
 */
internal enum class ActionLook { Filled, Tonal, Outlined }

/** A choice in an action's menu ("Open in" lists the apps); [overflowLabel] names it alone in the More menu. */
internal class ActionChoice(
    val label: String,
    val overflowLabel: String,
    val onClick: () -> Unit,
)

/**
 * One action of the place page. [label] is what the button says, or, as an icon, what its tooltip and
 * TalkBack say; it is the state where the action toggles one ("Bookmarked", "Starting here"), and
 * [on] is that state being on. With [choices] the action opens a menu of them instead of acting.
 */
internal class PlaceAction(
    val icon: ImageVector,
    val label: String,
    val look: ActionLook,
    val onClick: () -> Unit = {},
    val choices: List<ActionChoice> = emptyList(),
    val on: Boolean = false,
) {
    /** The look as an icon button. */
    val iconLook: ActionLook
        get() =
            when {
                look == ActionLook.Filled -> ActionLook.Filled
                on -> ActionLook.Tonal
                else -> ActionLook.Outlined
            }
}

/** An icon action's width: its 48 dp touch target, so neighbours' 40 dp circles sit 8 dp apart. */
internal val IconActionSize = 48.dp

/**
 * How many of [count] icon actions show in a row with room for [slots]: all when they fit, else one
 * fewer than the room so the last slot holds More with the rest.
 */
internal fun iconActionsShown(
    count: Int,
    slots: Int,
): Int = if (count <= slots) count else (slots - 1).coerceAtLeast(0)

/** The place page's actions, in Google Maps' order: Directions, the place's own actions, then Share and Open in. */
internal fun placeActions(actions: PlacePageActions): List<PlaceAction> =
    buildList {
        actions.onDirections?.let { add(PlaceAction(RoutingIcons.Directions, "Directions", ActionLook.Filled, it)) }
        // "Start here", as CoMaps' "Route from" beside its "Route to": directions to the places opened
        // next start from this one (a hotel, a station) instead of the user's position.
        actions.routeStart?.let { start ->
            if (start.chosen) {
                add(PlaceAction(RoutingIcons.TripOrigin, "Starting here", ActionLook.Tonal, start.onToggle, on = true))
            } else {
                add(PlaceAction(RoutingIcons.TripOrigin, "Start here", ActionLook.Outlined, start.onToggle))
            }
        }
        val writes = actions.writes
        if (writes != null) {
            val saved = writes.own.isSaved
            add(
                PlaceAction(
                    if (saved) WriteIcons.Bookmark else WriteIcons.BookmarkBorder,
                    if (saved) "Bookmarked" else "Bookmark",
                    ActionLook.Tonal,
                    writes.onToggleSave,
                    on = saved,
                ),
            )
            add(PlaceAction(WriteIcons.Tag, "Tag", ActionLook.Outlined, writes.onTag))
            add(PlaceAction(WriteIcons.Post, "Post", ActionLook.Outlined, writes.onPost))
            add(PlaceAction(WriteIcons.AddToList, "Add to collection", ActionLook.Outlined, writes.onAddToList))
        }
        // Without writes Share is the place's own action, so it takes the tonal look Bookmark has.
        actions.onShare?.let {
            add(PlaceAction(AppIcons.Share, "Share", if (writes != null) ActionLook.Outlined else ActionLook.Tonal, it))
        }
        if (actions.openIn.isNotEmpty()) {
            val choices =
                actions.openIn.map { app ->
                    val name = if (app.inBrowser) "${app.title} (web)" else app.title
                    ActionChoice(name, "Open in $name") { actions.onOpenIn(app) }
                }
            add(PlaceAction(PlaceIcons.OpenInNew, "Open in", ActionLook.Outlined, choices = choices))
        }
    }

/**
 * The lines of a flow of buttons [widths] wide with [gap] between them, in [maxWidth]: each line the
 * indices that fit beside each other, a button wider than [maxWidth] alone on its line.
 */
internal fun flowLines(
    widths: List<Int>,
    maxWidth: Int,
    gap: Int,
): List<IntRange> {
    val lines = mutableListOf<IntRange>()
    var first = 0
    var used = 0
    widths.forEachIndexed { index, width ->
        if (index > first && used + gap + width > maxWidth) {
            lines += first until index
            first = index
            used = width
        } else {
            used += if (index > first) gap + width else width
        }
    }
    if (widths.isNotEmpty()) lines += first until widths.size
    return lines
}

/**
 * How many of the labelled actions [widths] wide show in at most [lines] lines of [maxWidth]: all when
 * they fit, else as many as fit with a More button [more] wide after them.
 */
internal fun labelledActionsShown(
    widths: List<Int>,
    more: Int,
    maxWidth: Int,
    gap: Int,
    lines: Int = LABELLED_ACTION_LINES,
): Int {
    if (flowLines(widths, maxWidth, gap).size <= lines) return widths.size
    return (widths.size - 1 downTo 1).firstOrNull { shown ->
        flowLines(widths.take(shown) + more, maxWidth, gap).size <= lines
    } ?: 0
}

/**
 * Lines of labelled actions in the panel. Two hold Directions, Start here, Bookmark and Tag at the
 * 400 dp panel's normal font, the page's own sections (Tags, Posts, Collections) start one line lower,
 * and the rest is one tap away under More; all eight wrap to four lines.
 */
private const val LABELLED_ACTION_LINES = 2

/** The space between labelled actions, and between their lines where the touch targets do not leave it. */
private val LabelledActionGap: Dp = 8.dp

/**
 * The place page's actions as labelled buttons on a wide screen (the panel beside the map on a tablet
 * or a phone in landscape), as the owner asked: the ones that fit in [LABELLED_ACTION_LINES] lines, the
 * rest under a labelled More with the phone's menu, so none hides off the side of the 400 dp panel.
 * The ones under More are measured but not placed, so TalkBack does not reach them in the row either.
 */
@Composable
internal fun LabelledActionRow(
    actions: List<PlaceAction>,
    modifier: Modifier = Modifier,
) {
    // How many show, decided by the layout; the More menu, open or not, follows it a frame later.
    var shownCount by remember { mutableIntStateOf(actions.size) }
    Layout(
        content = {
            actions.forEach { action -> LabelledAction(action) }
            LabelledMore(actions.drop(shownCount))
        },
        modifier = modifier.fillMaxWidth().padding(end = 8.dp),
    ) { measurables, constraints ->
        // Only ever in the page's bounded column; an unbounded one would not wrap at all.
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else Int.MAX_VALUE / 2
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val placeables = measurables.map { it.measure(loose) }
        val buttons = placeables.dropLast(1)
        val more = placeables.last()
        val gap = LabelledActionGap.roundToPx()
        val shown = labelledActionsShown(buttons.map { it.width }, more.width, width, gap)
        shownCount = shown
        val placed = buttons.take(shown) + listOfNotNull(more.takeIf { shown < buttons.size })
        val lines = flowLines(placed.map { it.width }, width, gap)
        val heights = lines.map { line -> line.maxOf { placed[it].height } }
        // A 40 dp button sits in its 48 dp target, 8 dp from the next line (a little less from
        // a slightly larger font); one taller than its target (a large font) fills it, so the lines
        // get the 8 dp between them.
        val target = IconActionSize.roundToPx()
        val spaces = heights.dropLast(1).map { if (it > target) gap else 0 }
        val rowWidth =
            if (constraints.hasBoundedWidth) {
                constraints.maxWidth
            } else {
                lines.maxOfOrNull { line -> line.sumOf { placed[it].width } + gap * (line.count() - 1) } ?: 0
            }
        layout(rowWidth, heights.sum() + spaces.sum()) {
            var y = 0
            lines.forEachIndexed { index, line ->
                var x = 0
                line.forEach { i ->
                    val button = placed[i]
                    button.placeRelative(x, y + (heights[index] - button.height) / 2)
                    x += button.width + gap
                }
                y += heights[index] + spaces.getOrElse(index) { 0 }
            }
        }
    }
}

/** "More" as a labelled button, with the actions that did not fit in the menu the phone's More has. */
@Composable
private fun LabelledMore(rest: List<PlaceAction>) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }) {
            Icon(AppIcons.MoreVert, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("More")
        }
        OverflowMenu(rest, open) { open = false }
    }
}

@Composable
private fun LabelledAction(action: PlaceAction) {
    var open by remember { mutableStateOf(false) }
    val onClick = if (action.choices.isEmpty()) action.onClick else ({ open = true })
    val content: @Composable RowScope.() -> Unit = {
        Icon(action.icon, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(action.label)
    }
    Box {
        when (action.look) {
            ActionLook.Filled -> Button(onClick = onClick, content = content)
            ActionLook.Tonal -> FilledTonalButton(onClick = onClick, content = content)
            ActionLook.Outlined -> OutlinedButton(onClick = onClick, content = content)
        }
        ChoicesMenu(action.choices, open) { open = false }
    }
}

/**
 * The place page's actions as one row of icon buttons on a phone, where labelled buttons run off the
 * side of the sheet (Google Maps scrolls them sideways; CoMaps keeps one row of icons with small words
 * under them). Each is a 48 dp target whose words are its long-press tooltip and its TalkBack label;
 * the ones that do not fit go into a More menu in the last slot, with their words.
 */
@Composable
internal fun IconActionRow(
    actions: List<PlaceAction>,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        // The row starts 4 dp early so the first circle (40 dp inside its 48 dp target) lines up
        // with the title; it is measured that much wider, so the 4 dp is room for the row too.
        val slots = ((maxWidth + IconActionInset) / IconActionSize).toInt()
        val shown = iconActionsShown(actions.size, slots)
        Row(
            Modifier.layout { measurable, constraints ->
                val inset = IconActionInset.roundToPx()
                val row = measurable.measure(constraints.copy(maxWidth = constraints.maxWidth + inset))
                layout(constraints.maxWidth, row.height) { row.placeRelative(-inset, 0) }
            },
        ) {
            actions.take(shown).forEach { action -> IconAction(action) }
            if (shown < actions.size) MoreAction(actions.drop(shown))
        }
    }
}

/** Half the gap between an icon action's 40 dp circle and its 48 dp target. */
private val IconActionInset: Dp = 4.dp

@Composable
private fun IconAction(action: PlaceAction) {
    var open by remember { mutableStateOf(false) }
    val onClick = if (action.choices.isEmpty()) action.onClick else ({ open = true })
    val icon: @Composable () -> Unit = { Icon(action.icon, contentDescription = action.label) }
    ActionTooltip(action.label) {
        Box(Modifier.size(IconActionSize), contentAlignment = Alignment.Center) {
            when (action.iconLook) {
                ActionLook.Filled -> {
                    FilledIconButton(onClick = onClick, content = icon)
                }

                ActionLook.Tonal -> {
                    FilledTonalIconButton(onClick = onClick, content = icon)
                }

                // The labelled buttons' light outline, not the icon button's darker one, so a row
                // reads the same in either form.
                ActionLook.Outlined -> {
                    OutlinedIconButton(onClick = onClick, border = ButtonDefaults.outlinedButtonBorder(), content = icon)
                }
            }
            ChoicesMenu(action.choices, open) { open = false }
        }
    }
}

/** "More" with the actions that did not fit, each by its words; a menu action's choices each by name. */
@Composable
private fun MoreAction(rest: List<PlaceAction>) {
    var open by remember { mutableStateOf(false) }
    ActionTooltip("More") {
        Box(Modifier.size(IconActionSize), contentAlignment = Alignment.Center) {
            IconButton(onClick = { open = true }) { Icon(AppIcons.MoreVert, contentDescription = "More") }
            OverflowMenu(rest, open) { open = false }
        }
    }
}

/** The menu of the actions under More, each by its words; a menu action's choices each by name. */
@Composable
private fun OverflowMenu(
    rest: List<PlaceAction>,
    open: Boolean,
    onDismiss: () -> Unit,
) {
    DropdownMenu(expanded = open, onDismissRequest = onDismiss) {
        rest.forEach { action ->
            val icon: @Composable () -> Unit = { Icon(action.icon, contentDescription = null) }
            if (action.choices.isEmpty()) {
                MenuItem(action.label, icon) {
                    onDismiss()
                    action.onClick()
                }
            } else {
                action.choices.forEach { choice ->
                    MenuItem(choice.overflowLabel, icon) {
                        onDismiss()
                        choice.onClick()
                    }
                }
            }
        }
    }
}

@Composable
private fun MenuItem(
    text: String,
    icon: @Composable () -> Unit,
    onClick: () -> Unit,
) {
    DropdownMenuItem(text = { Text(text) }, leadingIcon = icon, onClick = onClick)
}

/** The menu of an action's [choices] ("Open in" with its apps), like Google Maps' and CoMaps' open menus. */
@Composable
private fun ChoicesMenu(
    choices: List<ActionChoice>,
    open: Boolean,
    onDismiss: () -> Unit,
) {
    if (choices.isEmpty()) return
    DropdownMenu(expanded = open, onDismissRequest = onDismiss) {
        choices.forEach { choice ->
            DropdownMenuItem(
                text = { Text(choice.label) },
                onClick = {
                    onDismiss()
                    choice.onClick()
                },
            )
        }
    }
}

/** The words of an icon button on a long press, above it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ActionTooltip(
    text: String,
    content: @Composable () -> Unit,
) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
        tooltip = { PlainTooltip { Text(text) } },
        state = rememberTooltipState(),
        content = content,
    )
}
