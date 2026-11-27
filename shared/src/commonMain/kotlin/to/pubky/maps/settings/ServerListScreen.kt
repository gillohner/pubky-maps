package to.pubky.maps.settings

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import to.pubky.maps.config.AppSettings
import to.pubky.maps.config.ServerEntry
import to.pubky.maps.config.Service
import to.pubky.maps.config.SynonymHosts
import to.pubky.maps.ui.AppIcons

/**
 * One service's servers in the order the app tries them: drag a row (by its handle, or anywhere after
 * a long press) to change the order, remove one, and reset to the defaults; [onAddServer] opens the page
 * that adds one. [onUpdate] applies a change to the stored settings.
 *
 * A drag moves the rows on screen only; the new order is saved once, where the row is dropped, so a
 * drag across three rows is one change of the stored list, not three. A drag the system cancels, or
 * one cut short by leaving the page, is saved where the row was let go too: what is on screen is what
 * is kept. TalkBack users get Move up and Move down on each row instead of the drag (WCAG 2.5.7).
 */
@Composable
fun ServerListScreen(
    service: Service,
    settings: AppSettings,
    onUpdate: ((AppSettings) -> AppSettings) -> Unit,
    onAddServer: () -> Unit,
    onBack: () -> Unit,
) {
    val list = settings.servers[service]
    var confirmingReset by rememberSaveable { mutableStateOf(false) }
    // The library starts a row's drag gesture once and keeps the drop it was given then, so the drop
    // reads the list and the drag's order through these two holders, which last as long as the page.
    val stored by rememberUpdatedState(list)
    val save by rememberUpdatedState(onUpdate)
    var dragged by remember { mutableStateOf<DragOrder?>(null) }
    // A drag's order is drawn only over the list it started from: once the drop is stored (or another
    // change is), the stored list is drawn again.
    val shown = dragged?.takeIf { it.from == list }?.order ?: list
    // Forgotten once the list moves on, so a later list equal to that start (Reset to defaults after a
    // drag) is not drawn in the old drag's order.
    LaunchedEffect(list) { if (dragged?.from != list) dragged = null }
    val haptics = LocalHapticFeedback.current
    SettingsScaffold(title = service.title, onBack = onBack) { barsPadding ->
        val listState = rememberLazyListState()
        val reorder =
            rememberReorderableLazyListState(listState, scrollThresholdPadding = barsPadding) { from, to ->
                val order = dragged?.takeIf { it.from == stored }?.order ?: stored
                val fromIndex = order.indexOfFirst { it.url == from.key }
                val toIndex = order.indexOfFirst { it.url == to.key }
                if (fromIndex >= 0 && toIndex >= 0) {
                    dragged = DragOrder(stored, order.toMutableList().apply { add(toIndex, removeAt(fromIndex)) })
                    haptics.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
                }
            }
        val canDrag = shown.size > 1
        SettingsList(barsPadding, listState) {
            item {
                val howToMove = if (canDrag) " Drag a server to change the order." else ""
                PageIntro("${service.description} Listed in the order they are tried.$howToMove")
            }
            shown.forEachIndexed { index, entry ->
                item(key = entry.url) {
                    ReorderableItem(reorder, key = entry.url, enabled = canDrag) { dragging ->
                        val url = entry.url
                        val drop = {
                            val order = dragged?.takeIf { it.from == stored }?.order
                            val at = order?.indexOfFirst { it.url == url } ?: -1
                            if (at >= 0) save { it.withServerMovedTo(service, url, at) }
                        }
                        val lift = { _: Offset -> haptics.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate) }
                        ServerRow(
                            position = index + 1,
                            entry = entry,
                            dragging = dragging,
                            canMoveUp = index > 0,
                            canMoveDown = index < shown.lastIndex,
                            canRemove = shown.size > 1,
                            onMoveUp = { onUpdate { it.withServerMoved(service, entry.url, -1) } },
                            onMoveDown = { onUpdate { it.withServerMoved(service, entry.url, 1) } },
                            onRemove = { onUpdate { it.withServerRemoved(service, entry.url) } },
                            rowDrag = Modifier.longPressDraggableHandle(canDrag, onDragStarted = lift, onDragStopped = drop),
                            handle =
                                if (canDrag) {
                                    Modifier.draggableHandle(onDragStarted = lift, onDragStopped = drop)
                                } else {
                                    null
                                },
                        )
                    }
                }
            }
            item {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(16.dp),
                ) {
                    FilledTonalButton(onClick = onAddServer) {
                        Icon(AppIcons.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(8.dp))
                        Text("Add server")
                    }
                    if (!settings.isDefaultServerList(service)) {
                        TextButton(onClick = { confirmingReset = true }) { Text("Reset to defaults") }
                    }
                }
            }
            // Only where it explains a badge on this page.
            if (shown.any { SynonymHosts.isSynonymServer(it.url) }) {
                item { PageIntro(SYNONYM_EXPLANATION) }
            }
        }
    }
    if (confirmingReset) {
        AlertDialog(
            onDismissRequest = { confirmingReset = false },
            title = { Text("Reset to the default servers?") },
            text = { Text("Servers you added to this list are removed and the order goes back to the start.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmingReset = false
                    onUpdate { it.withServersReset(service) }
                }) { Text("Reset") }
            },
            dismissButton = { TextButton(onClick = { confirmingReset = false }) { Text("Cancel") } },
        )
    }
}

/** Test tag of a server row's drag handle, which has no text or TalkBack label to find it by. */
const val DRAG_HANDLE = "server-drag-handle"

/**
 * One server: its place in the list, name, address and badge, the options menu (Remove) and, when
 * [handle] is given, the drag handle. [rowDrag] makes the whole row draggable after a long press.
 * A [dragging] row is lifted above the others.
 */
@Composable
private fun ServerRow(
    position: Int,
    entry: ServerEntry,
    dragging: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    canRemove: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onRemove: () -> Unit,
    rowDrag: Modifier,
    handle: Modifier?,
) {
    val isSynonym = SynonymHosts.isSynonymServer(entry.url)
    var menuOpen by remember { mutableStateOf(false) }
    val lift by animateDpAsState(if (dragging) 6.dp else 0.dp)
    // Move up and Move down stand in for the drag, which TalkBack cannot do; Remove is also in the menu.
    val actions =
        buildList {
            if (canMoveUp) add(accessibilityAction("Move up", onMoveUp))
            if (canMoveDown) add(accessibilityAction("Move down", onMoveDown))
            if (canRemove) add(accessibilityAction("Remove", onRemove))
        }
    ListItem(
        leadingContent = {
            Text("$position", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        },
        headlineContent = { Text(entry.name) },
        supportingContent = {
            Column {
                Text(entry.url.removePrefix("https://"))
                if (isSynonym) Badge(SYNONYM_BADGE, Modifier.padding(top = 4.dp))
            }
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(AppIcons.MoreVert, contentDescription = "Options for ${entry.name}")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text(if (canRemove) "Remove" else "Remove (a list needs one server)") },
                            enabled = canRemove,
                            onClick = {
                                menuOpen = false
                                onRemove()
                            },
                        )
                    }
                }
                if (handle != null) {
                    // Not a TalkBack stop: the row's Move up and Move down do what the drag does.
                    Box(handle.size(48.dp).testTag(DRAG_HANDLE), contentAlignment = Alignment.Center) {
                        Icon(AppIcons.DragHandle, contentDescription = null)
                    }
                }
            }
        },
        colors =
            if (dragging) {
                ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
            } else {
                ListItemDefaults.colors()
            },
        shadowElevation = lift,
        // One TalkBack stop per server (position, name, address, badge), with the actions on it; the
        // options button stays a stop of its own.
        modifier = rowDrag.semantics(mergeDescendants = true) { customActions = actions },
    )
}

/** The order a drag has put the rows in, over the stored list [from] it started from. */
private data class DragOrder(
    val from: List<ServerEntry>,
    val order: List<ServerEntry>,
)

private fun accessibilityAction(
    label: String,
    run: () -> Unit,
) = CustomAccessibilityAction(label) {
    run()
    true
}
