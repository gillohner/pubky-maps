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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
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
            Text(kept.badgeText, style = MaterialTheme.typography.labelMedium)
        }
    }
}

/** The badge and its sentence, shown before a write is sent. */
@Composable
fun KeptNote(
    kept: Kept,
    modifier: Modifier = Modifier,
) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        KeptBadge(kept)
        Spacer(Modifier.width(8.dp))
        Text(kept.explanation, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
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
    content: @Composable ColumnScope.() -> Unit,
) {
    Scaffold(
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
