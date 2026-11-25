package to.pubky.maps.write

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage

/** Picks or takes a photo for the photo page. On Android: the system photo picker and the camera app. */
class PhotoInput(
    /**
     * The page's launchers, remembered in composition. Each prepares the photo (re-encoded on the phone,
     * metadata stripped; the GPS spot kept only when asked) and hands back the result.
     */
    val rememberLaunchers: @Composable (onResult: (PhotoResult) -> Unit) -> PhotoLaunchers,
)

/** Starts the picker or the camera; [take] is null where there is no camera app. [keepLocation] keeps the photo's GPS spot. */
class PhotoLaunchers(
    val choose: (keepLocation: Boolean) -> Unit,
    val take: ((keepLocation: Boolean) -> Unit)?,
)

sealed interface PhotoResult {
    data object Preparing : PhotoResult

    class Ready(
        val photo: PreparedPhoto,
    ) : PhotoResult

    data class Failed(
        val reason: String,
    ) : PhotoResult

    data object Cancelled : PhotoResult
}

/** What the photo page shows. */
data class PhotoPageState(
    val placeName: String?,
    val photo: PreparedPhoto? = null,
    val preparing: Boolean = false,
    val error: String? = null,
    val alt: String = "",
    val caption: String = "",
    /** The user's Photo locations choice: Public keeps a photo's GPS spot. */
    val keepLocation: Boolean = false,
    val canTake: Boolean = true,
)

/** The spec's limit for a photo's description (alt text). */
const val ALT_MAX = 1000

/**
 * Post a photo of a place: choose one or take one, describe it, post it. The photo is re-encoded on
 * the phone, so camera and device details never leave it; its GPS spot goes only when the user's
 * Photo locations choice is Public.
 */
@Composable
fun PhotoPage(
    state: PhotoPageState,
    onChoose: () -> Unit,
    onTake: (() -> Unit)?,
    onAltChange: (String) -> Unit,
    onCaptionChange: (String) -> Unit,
    onPost: () -> Unit,
    onClose: () -> Unit,
) {
    val photo = state.photo
    WritePage(title = "Add a photo", place = state.placeName, onClose = onClose, action = {
        Button(
            onClick = onPost,
            enabled = photo != null && state.alt.length <= ALT_MAX && postLength(state.caption) <= POST_MAX,
        ) { Text("Post") }
    }) {
        KeptNote(Kept.Public)
        when {
            state.preparing -> {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(4f / 3f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator() }
            }

            photo != null -> {
                AsyncImage(
                    model = photo.bytes,
                    contentDescription = state.alt.ifBlank { "The photo to post" },
                    placeholder = ColorPainter(MaterialTheme.colorScheme.surfaceContainerHighest),
                    contentScale = ContentScale.Fit,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .aspectRatio(photo.width.toFloat().coerceAtLeast(1f) / photo.height.toFloat().coerceAtLeast(1f))
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                )
            }
        }
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onChoose, enabled = !state.preparing) {
                Icon(WriteIcons.Gallery, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(if (photo == null) "Choose a photo" else "Choose another")
            }
            if (onTake != null && state.canTake) {
                OutlinedButton(onClick = onTake, enabled = !state.preparing) {
                    Icon(WriteIcons.Camera, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Take a photo")
                }
            }
        }
        if (photo != null) {
            OutlinedTextField(
                value = state.alt,
                onValueChange = onAltChange,
                label = { Text("Describe the photo") },
                supportingText = { Text("For people who cannot see it. ${state.alt.length} / $ALT_MAX") },
                isError = state.alt.length > ALT_MAX,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = state.caption,
                onValueChange = onCaptionChange,
                label = { Text("Caption (optional)") },
                minLines = 2,
                modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp),
            )
        }
        Text(
            locationText(photo, state.keepLocation),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** What happens to the photo's details, in words. */
internal fun locationText(
    photo: PreparedPhoto?,
    keepLocation: Boolean,
): String =
    when {
        photo?.keptLocation == true -> {
            "This photo keeps its location, because Photo locations is set to Public. Camera and phone details are removed."
        }

        photo?.removedLocation == true -> {
            "The photo's location was removed, with the camera and phone details. It shows at this place."
        }

        keepLocation -> {
            "Camera and phone details are removed from the photo. A location in it is kept (Photo locations is set to Public); " +
                "photos chosen from the gallery usually come without one."
        }

        else -> {
            "The photo's location, camera and phone details are removed before it is posted. It shows at this place."
        }
    }
