package to.pubky.maps.write

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.dp
import to.pubky.maps.ui.AppIcons

/** Write pages are capped at this width and centred on tablets, like the settings pages. */
private val MaxWriteWidth = 640.dp

/** Where a thing of the user's is kept, in two words: "Public", "Private", "On this phone". */
val Kept.badgeText: String
    get() =
        when (this) {
            Kept.Public -> "Public"
            Kept.Private -> "Private"
            Kept.Phone -> "On this phone"
        }

/** What [Kept] means, in one sentence. */
val Kept.explanation: String
    get() =
        when (this) {
            Kept.Public -> "Anyone can read it and see that it is yours."
            Kept.Private -> "On your homeserver, not public. The homeserver's operator can read it."
            Kept.Phone -> "Only on this phone. Pubky Maps never uploads it."
        }

/** A small label saying where something is kept, with its icon. Public is drawn in the tertiary colour, so it stands out. */
@Composable
fun KeptBadge(
    kept: Kept,
    modifier: Modifier = Modifier,
) {
    val public = kept == Kept.Public
    Surface(
        color = if (public) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.secondaryContainer,
        contentColor = if (public) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onSecondaryContainer,
        shape = MaterialTheme.shapes.small,
        modifier = modifier,
    ) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                when (kept) {
                    Kept.Public -> WriteIcons.Public
                    Kept.Private -> WriteIcons.Lock
                    Kept.Phone -> WriteIcons.PhoneOnly
                },
                contentDescription = null,
                modifier = Modifier.size(14.dp),
            )
            Spacer(Modifier.width(4.dp))
            Text(kept.badgeText, style = MaterialTheme.typography.labelLarge)
        }
    }
}

/**
 * The badge and its sentence, shown before a write is sent, at body size in the secondary text
 * colour (WCAG AA in both themes); [more] adds what else the user should know first.
 *
 * The sentence goes beside the badge when it takes no more lines there than on the whole width, else
 * under the badge: at 200 % font the badge takes a third of a phone's width, and beside it the
 * sentence would be a narrow column one line longer or more ("Anyone can read it / and see that it
 * is / yours."). The rule looks at the sentence's own lines, not at the height of the whole note, so
 * the sentence goes under even when the badge's row makes both arrangements about as tall: short
 * ragged lines are what make it hard to read.
 */
@Composable
fun KeptNote(
    kept: Kept,
    modifier: Modifier = Modifier,
    more: String? = null,
) {
    Layout(
        contents =
            listOf(
                { KeptBadge(kept) },
                { NoteText(listOfNotNull(kept.explanation, more).joinToString(" ")) },
            ),
        modifier = modifier,
    ) { (badgeParts, textParts), constraints ->
        val badgePart = badgeParts.single()
        val text = textParts.single()
        val sideGap = KeptNoteSideGap.roundToPx()
        // The badge's own width from its intrinsics, not from its placeable: when a parent asks this
        // layout for its intrinsic height, Compose measures stand-ins as wide as the constraints.
        val badgeWidth = badgePart.maxIntrinsicWidth(Constraints.Infinity)
        val width =
            if (constraints.hasBoundedWidth) {
                constraints.maxWidth
            } else {
                constraints.constrainWidth(badgeWidth + sideGap + text.maxIntrinsicWidth(Constraints.Infinity))
            }
        val badge = badgePart.measure(Constraints(maxWidth = width))
        val sideWidth = (width - badgeWidth - sideGap).coerceAtLeast(0)
        // Text of the same style grows by whole lines, so the taller one is the one with more lines.
        val beside = sideWidth > 0 && text.minIntrinsicHeight(sideWidth) <= text.minIntrinsicHeight(width)
        val note = text.measure(Constraints(maxWidth = if (beside) sideWidth else width))
        val underGap = KeptNoteUnderGap.roundToPx()
        val height =
            constraints.constrainHeight(if (beside) maxOf(badge.height, note.height) else badge.height + underGap + note.height)
        layout(width, height) {
            badge.placeRelative(0, 0)
            if (beside) note.placeRelative(badgeWidth + sideGap, 0) else note.placeRelative(0, badge.height + underGap)
        }
    }
}

/** The space between [KeptBadge] and its sentence beside it, and under it. */
private val KeptNoteSideGap = 8.dp
private val KeptNoteUnderGap = 4.dp

/** A line of explanation on a write page: 14 sp in the secondary text colour, readable in both themes. */
@Composable
fun NoteText(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = modifier)
}

/**
 * A full page for a write: a bar with Close, the [title] and the [place] it is about, then [content]
 * (scrolling, above the keyboard). [action] sits at the end of the bar (Post, Done).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WritePage(
    title: String,
    place: String?,
    onClose: () -> Unit,
    action: (@Composable RowScope.() -> Unit)? = null,
    snackbarHostState: SnackbarHostState? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Scaffold(
        snackbarHost = { snackbarHostState?.let { SnackbarHost(it) } },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        place?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onClose) { Icon(AppIcons.Close, contentDescription = "Close") }
                },
                actions = { action?.invoke(this) },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).imePadding(), contentAlignment = Alignment.TopCenter) {
            Column(
                modifier =
                    Modifier
                        .widthIn(max = MaxWriteWidth)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                content = content,
            )
        }
    }
}

/** A titled group on a write page. */
@Composable
fun WriteSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        content()
    }
}
