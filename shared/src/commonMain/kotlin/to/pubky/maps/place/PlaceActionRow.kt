package to.pubky.maps.place

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
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
 * The place page's actions as labelled buttons in one row that scrolls sideways when it is wider than
 * the page: on a wide screen (the panel beside the map on a tablet or a phone in landscape), as the
 * owner asked; the 400 dp panel shows the first two or three and scrolls to the rest.
 */
@Composable
internal fun LabelledActionRow(
    actions: List<PlaceAction>,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.horizontalScroll(rememberScrollState()).padding(end = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        actions.forEach { action -> LabelledAction(action) }
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
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                rest.forEach { action ->
                    val icon: @Composable () -> Unit = { Icon(action.icon, contentDescription = null) }
                    if (action.choices.isEmpty()) {
                        MenuItem(action.label, icon) {
                            open = false
                            action.onClick()
                        }
                    } else {
                        action.choices.forEach { choice ->
                            MenuItem(choice.overflowLabel, icon) {
                                open = false
                                choice.onClick()
                            }
                        }
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
