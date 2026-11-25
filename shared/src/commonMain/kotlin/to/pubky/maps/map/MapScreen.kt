package to.pubky.maps.map

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import to.pubky.maps.config.AttributionLink
import to.pubky.maps.ui.AppIcons

/** Space between the screen's safe area and the map controls. */
internal val MapControlMargin = 12.dp

/** Size of the round buttons at the top end of the map (settings, compass). */
internal val TopControlSize = 48.dp

/**
 * The map screen: [map] fills the screen, the controls sit on top of it inside the safe area.
 * Settings top end, my location bottom end, the data credits bottom start.
 */
@Composable
fun MapScreen(
    attribution: List<AttributionLink>,
    mapUnavailable: Boolean,
    onRetryMap: () -> Unit,
    onMyLocation: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
    map: @Composable () -> Unit,
) {
    Box(modifier.fillMaxSize()) {
        map()
        Box(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(MapControlMargin),
        ) {
            MapButton(
                icon = AppIcons.Settings,
                contentDescription = "Settings",
                onClick = onOpenSettings,
                size = TopControlSize,
                modifier = Modifier.align(Alignment.TopEnd),
            )
            if (mapUnavailable) {
                MapUnavailableNotice(
                    onRetry = onRetryMap,
                    modifier =
                        Modifier
                            .align(Alignment.TopCenter)
                            .padding(horizontal = TopControlSize + MapControlMargin)
                            .widthIn(max = 480.dp),
                )
            }
            MapButton(
                icon = AppIcons.MyLocation,
                contentDescription = "Show my location",
                onClick = onMyLocation,
                size = 56.dp,
                tinted = true,
                modifier = Modifier.align(Alignment.BottomEnd),
            )
            AttributionBar(
                links = attribution,
                modifier =
                    Modifier
                        .align(Alignment.BottomStart)
                        .padding(end = 56.dp + MapControlMargin),
            )
            SnackbarHost(
                hostState = snackbarHostState,
                modifier =
                    Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 56.dp + MapControlMargin),
            )
        }
    }
}

@Composable
private fun MapButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    size: Dp,
    modifier: Modifier = Modifier,
    tinted: Boolean = false,
) {
    Surface(
        onClick = onClick,
        modifier = modifier.size(size),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        contentColor = if (tinted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        shadowElevation = 3.dp,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = contentDescription)
        }
    }
}

/** The credits for the map's data and tiles, always visible, each a link to its source. */
@Composable
internal fun AttributionBar(
    links: List<AttributionLink>,
    modifier: Modifier = Modifier,
) {
    val linkStyles = TextLinkStyles(style = SpanStyle(color = MaterialTheme.colorScheme.onSurface))
    val text =
        buildAnnotatedString {
            links.forEachIndexed { i, link ->
                if (i > 0) append("  ")
                withLink(LinkAnnotation.Url(link.url, linkStyles)) { append(link.label) }
            }
        }
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(4.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.75f),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun MapUnavailableNotice(
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shadowElevation = 3.dp,
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "The map could not load. Check your connection.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onRetry) { Text("Retry") }
        }
    }
}
