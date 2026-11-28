package to.pubky.maps.place

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Street-level photos that show the place, nearest first, as Google Maps shows Street View beside a
 * place's photos: each with who took it and when (its licence asks for the author), a tap opens it in
 * Panoramax' viewer, where the user can look around and walk the street. Under them, the licences
 * of the photos shown, each opening its text. A photo whose thumbnail fails is left out; with none
 * left, so is the section.
 */
@Composable
internal fun StreetPhotosSection(
    photos: List<StreetPhoto>,
    onOpenLink: (String) -> Unit,
) {
    val failed = remember(photos) { mutableStateListOf<String>() }
    val shown = photos.filterNot { it.id in failed }
    if (shown.isEmpty()) return
    Column {
        SectionTitle("Street-level photos")
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(shown, key = { it.id }) { photo ->
                StreetPhotoTile(photo, onOpen = { onOpenLink(photo.viewerUrl) }, onFailed = { failed += photo.id })
            }
        }
        val licences = shown.mapNotNull { photo -> photo.licence?.let { it to photo.licenceUrl } }.distinctBy { it.first }
        Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
            if (licences.isEmpty()) LicenceLine("Photos: Panoramax", null, onOpenLink)
            licences.forEach { (licence, url) -> LicenceLine("Photos: Panoramax, ${licenceName(licence)}", url, onOpenLink) }
        }
    }
}

/** A credit line; with a [link] it is a touch target of its own (48 dp high) that opens the licence. */
@Composable
private fun LicenceLine(
    text: String,
    link: String?,
    onOpenLink: (String) -> Unit,
) {
    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(min = if (link != null) 48.dp else 40.dp)
            .then(link?.let { Modifier.clickable(onClickLabel = "Open the licence") { onOpenLink(it) } } ?: Modifier),
        contentAlignment = Alignment.CenterStart,
    ) {
        SmallText(text)
    }
}

/** One photo: the thumbnail, then its author and month in two short lines; one TalkBack stop. */
@Composable
private fun StreetPhotoTile(
    photo: StreetPhoto,
    onOpen: () -> Unit,
    onFailed: () -> Unit,
) {
    val description =
        listOfNotNull(
            "Street-level photo",
            photo.author?.let { "by $it" },
            photo.taken?.let { "taken $it" },
        ).joinToString(" ")
    Column(
        Modifier
            .width(TILE_WIDTH)
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onOpen)
            .clearAndSetSemantics {
                contentDescription = description
                role = Role.Button
                onClick(label = "Open in Panoramax") {
                    onOpen()
                    true
                }
            },
        horizontalAlignment = Alignment.Start,
    ) {
        LocalRemoteImage.current(
            photo.thumbnailUrl,
            null,
            Modifier.size(TILE_WIDTH, TILE_HEIGHT).clip(RoundedCornerShape(8.dp)),
            true,
            onFailed,
        )
        Spacer(Modifier.size(4.dp))
        photo.author?.let { Text(it, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        photo.taken?.let { SmallText(it) }
    }
}

/** 4:3, as most street-level cameras take them. */
private val TILE_WIDTH = 160.dp
private val TILE_HEIGHT = 120.dp
