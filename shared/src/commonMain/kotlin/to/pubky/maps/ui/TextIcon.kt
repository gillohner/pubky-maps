package to.pubky.maps.ui

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.TextUnit

/**
 * An icon that sits in a line of text (a badge, a chip, a note), [size] in sp, so it grows with the
 * user's font size as the text beside it does, through the same font-scale curve (Android's is
 * non-linear: at 200 % 14 sp is drawn 26 dp tall). In dp a 14 dp icon beside 14 sp text drawn at
 * 26 dp reads as a dot; icons that stand alone or in buttons stay in dp, as Material's do.
 */
@Composable
internal fun TextIcon(
    icon: ImageVector,
    contentDescription: String?,
    size: TextUnit,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
) {
    Icon(icon, contentDescription, modifier.size(with(LocalDensity.current) { size.toDp() }), tint)
}
