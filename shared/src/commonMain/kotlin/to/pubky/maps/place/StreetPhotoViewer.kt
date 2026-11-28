package to.pubky.maps.place

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import to.pubky.maps.links.GeoPoint
import to.pubky.maps.ui.AppIcons
import to.pubky.maps.ui.rememberSafeLinkOpener
import kotlin.math.roundToInt

/**
 * The street-level photos of a place in the app, as Google Maps opens a place's photos and Street
 * View in its own viewer: the [selected] photo large on a dark stage (photos are seen best on dark,
 * the bars keep the app's theme so the system bars stay readable), who took it, when and under which
 * licence, the place's other photos in a row to switch to, and Open in Panoramax for walking the
 * street. A flat photo is fitted whole; a 360° one fills the stage, turned to face the place at
 * [place], and drags around. The thumbnail the page showed stands in until the larger copy has loaded. [photos] is null or loading until Panoramax'
 * answer is known (a page restored after the app was closed asks again; [onRetry] asks once more
 * after every server failed).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StreetPhotoViewer(
    photos: StreetPhotosState?,
    place: GeoPoint,
    selected: String,
    onSelect: (String) -> Unit,
    onRetry: () -> Unit,
    onOpenLink: (String) -> Unit,
    onBack: () -> Unit,
) {
    val list = photos?.photos.orEmpty()
    val index = list.indexOfFirst { it.id == selected }.coerceAtLeast(0)
    val photo = list.getOrNull(index)
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Street-level photos", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (list.size > 1) {
                            Text(
                                "${index + 1} of ${list.size}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(AppIcons.ArrowBack, contentDescription = "Back") }
                },
            )
        },
    ) { padding ->
        BoxWithConstraints(Modifier.fillMaxSize().padding(padding)) {
            val stage =
                @Composable { modifier: Modifier ->
                    Box(modifier.background(Stage), contentAlignment = Alignment.Center) {
                        when {
                            photo != null && photo.panorama -> Panorama(photo, place)
                            photo != null -> FlatPhoto(photo)
                            photos == null || photos.loading -> StageText("Loading the photos…")
                            photos.failed -> StageText("No Panoramax server could be reached.", onRetry)
                            else -> StageText("Panoramax has no street-level photos of this place now.")
                        }
                    }
                }
            // A phone on its side has too little height for the details under the photo: they go beside it.
            val detailsMaxHeight = maxHeight / 2
            if (maxWidth > maxHeight && maxHeight < SIDE_BY_SIDE_BELOW) {
                Row(Modifier.fillMaxSize()) {
                    stage(Modifier.weight(1f).fillMaxHeight())
                    if (photo != null) {
                        PhotoDetails(photo, list, index, onSelect, onOpenLink, Modifier.width(SIDE_DETAILS_WIDTH).fillMaxHeight())
                    }
                }
            } else {
                Column(Modifier.fillMaxSize()) {
                    stage(Modifier.weight(1f).fillMaxWidth())
                    // At most half the height (a large font scrolls them), so the photo keeps the rest.
                    if (photo != null) {
                        PhotoDetails(
                            photo,
                            list,
                            index,
                            onSelect,
                            onOpenLink,
                            Modifier.fillMaxWidth().heightIn(max = detailsMaxHeight),
                        )
                    }
                }
            }
        }
    }
}

/** A flat photo, fitted whole on the stage; one TalkBack stop that names it. */
@Composable
private fun FlatPhoto(photo: StreetPhoto) {
    val description = streetPhotoDescription(photo)
    PhotoImage(photo, ContentScale.Fit, Modifier.fillMaxSize().semantics { contentDescription = description })
}

/**
 * A 360° photo at the least size that covers the stage (on a phone as high as the stage, so about a
 * third of the turn shows; on a wide tablet the whole turn across it): the copy the viewer loads is
 * 2048 px wide, so larger only blurs it. It opens turned so the place is in the middle. Loaded once
 * and drawn three times side by side, starting in the middle one, so the user can turn all the way
 * around either way. Left to right in every language: the turn is the photo's, not the text's. No
 * thumbnail stands in, as Panoramax' thumbnail of a 360° photo is a crop of its front, not the turn.
 */
@Composable
private fun Panorama(
    photo: StreetPhoto,
    place: GeoPoint,
) {
    var failed by remember(photo.id) { mutableStateOf(false) }
    if (failed) return StageText("The photo could not be loaded.")
    val description = "${streetPhotoDescription(photo)}, 360°, turned to face the place"
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        BoxWithConstraints(
            Modifier.fillMaxSize().clipToBounds().semantics { contentDescription = description },
            contentAlignment = Alignment.Center,
        ) {
            StageText("Loading the photo…")
            val turnWidth = max(maxWidth, maxHeight * 2)
            val scroll = rememberScrollState()
            val turnPx = with(LocalDensity.current) { turnWidth.toPx() }
            val stagePx = constraints.maxWidth
            LaunchedEffect(photo.id, turnPx, stagePx) {
                val facing = turnPx + panoramaFacing(photo, place) * turnPx
                scroll.scrollTo((facing - stagePx / 2f).roundToInt())
            }
            Box(Modifier.fillMaxHeight().horizontalScroll(scroll).width(turnWidth * 3), contentAlignment = Alignment.CenterStart) {
                LocalRemoteImage.current(
                    photo.photoUrl,
                    null,
                    Modifier
                        .offset(x = turnWidth)
                        .requiredSize(turnWidth, turnWidth / 2)
                        .drawWithContent {
                            translate(left = -size.width) { this@drawWithContent.drawContent() }
                            drawContent()
                            translate(left = size.width) { this@drawWithContent.drawContent() }
                        },
                    false,
                    ContentScale.FillBounds,
                ) { failed = true }
            }
        }
    }
}

/**
 * A flat photo's larger copy over its thumbnail, which the page loaded already: the thumbnail (the
 * whole photo, smaller) shows at once and the larger copy covers it once loaded, both fitted the same
 * way. Neither loading says so.
 */
@Composable
private fun PhotoImage(
    photo: StreetPhoto,
    scale: ContentScale,
    modifier: Modifier,
) {
    var thumbnailFailed by remember(photo.id) { mutableStateOf(false) }
    var photoFailed by remember(photo.id) { mutableStateOf(photo.photoUrl == photo.thumbnailUrl) }
    val image = LocalRemoteImage.current
    Box(modifier, contentAlignment = Alignment.Center) {
        if (thumbnailFailed && photoFailed) {
            StageText("The photo could not be loaded.")
            return@Box
        }
        if (!thumbnailFailed) image(photo.thumbnailUrl, null, Modifier.matchParentSize(), false, scale) { thumbnailFailed = true }
        if (!photoFailed) image(photo.photoUrl, null, Modifier.matchParentSize(), false, scale) { photoFailed = true }
    }
}

/** A line on the dark stage, with Try again when [onRetry] is given. */
@Composable
private fun StageText(
    text: String,
    onRetry: (() -> Unit)? = null,
) {
    Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text, color = Color.White, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyLarge)
        if (onRetry != null) {
            TextButton(onClick = onRetry) { Text("Try again", color = Color.White) }
        }
    }
}

/** Under the stage: who took the photo and when, its licence, Open in Panoramax, the place's other photos. */
@Composable
private fun PhotoDetails(
    photo: StreetPhoto,
    photos: List<StreetPhoto>,
    index: Int,
    onSelect: (String) -> Unit,
    onOpenLink: (String) -> Unit,
    modifier: Modifier,
) {
    Column(modifier.verticalScroll(rememberScrollState()).padding(top = 12.dp, bottom = 8.dp)) {
        Column(Modifier.padding(horizontal = 16.dp)) {
            photo.author?.let { Text(it, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            val about = listOfNotNull(photo.taken, if (photo.panorama) "360° photo, drag to look around" else null)
            if (about.isNotEmpty()) SmallText(about.joinToString(" · "))
            LicenceLine(
                photo.licence?.let { "Photo: Panoramax, ${licenceName(it)}" } ?: "Photo: Panoramax",
                photo.licenceUrl,
                onOpenLink,
            )
            OutlinedButton(onClick = { onOpenLink(photo.viewerUrl) }) {
                Icon(PlaceIcons.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(8.dp))
                Text("Open in Panoramax")
            }
        }
        if (photos.size > 1) {
            val row = rememberLazyListState()
            // The photo shown is in view in the row, also the sixth opened from the page on a phone.
            LaunchedEffect(index) {
                val info = row.layoutInfo
                val item = info.visibleItemsInfo.firstOrNull { it.index == index }
                if (item == null || item.offset < info.viewportStartOffset || item.offset + item.size > info.viewportEndOffset) {
                    row.scrollToItem(index)
                }
            }
            LazyRow(
                Modifier.padding(top = 12.dp),
                state = row,
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                itemsIndexed(photos, key = { _, it -> it.id }) { i, other ->
                    PhotoChoice(other, i, photos.size, chosen = i == index, onClick = { onSelect(other.id) })
                }
            }
        }
    }
}

/** One of the place's photos to switch to; the one shown is outlined. */
@Composable
private fun PhotoChoice(
    photo: StreetPhoto,
    index: Int,
    count: Int,
    chosen: Boolean,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(6.dp)
    val outline =
        if (chosen) BorderStroke(3.dp, MaterialTheme.colorScheme.primary) else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    val description = "Photo ${index + 1} of $count, ${streetPhotoDescription(photo).replaceFirstChar { it.lowercase() }}"
    Box(
        Modifier
            .size(CHOICE_WIDTH, CHOICE_HEIGHT)
            .clip(shape)
            .border(outline, shape)
            .selectable(selected = chosen, role = Role.Tab, onClick = onClick)
            .semantics { contentDescription = description },
    ) {
        LocalRemoteImage.current(
            photo.thumbnailUrl,
            null,
            Modifier.matchParentSize().padding(if (chosen) 3.dp else 1.dp),
            true,
            ContentScale.Crop,
        ) {
        }
    }
}

/** The photo viewer on the live [controller]: asks for [place]'s photos if they are not known (the app was closed meanwhile). */
@Composable
fun StreetPhotoRoute(
    controller: StreetPhotosController,
    place: GeoPoint,
    photo: String,
    onBack: () -> Unit,
) {
    LaunchedEffect(controller, place) { controller.open(place) }
    val state by controller.state.collectAsStateWithLifecycle()
    var selected by rememberSaveable { mutableStateOf(photo) }
    StreetPhotoViewer(
        photos = state?.takeIf { it.point == place },
        place = place,
        selected = selected,
        onSelect = { selected = it },
        onRetry = { controller.open(place) },
        onOpenLink = rememberSafeLinkOpener(),
        onBack = onBack,
    )
}

/** Below this height, on a phone on its side, the details go beside the photo. */
private val SIDE_BY_SIDE_BELOW = 480.dp
private val SIDE_DETAILS_WIDTH = 300.dp

/** Photos are seen best on dark, in either theme. */
private val Stage = Color(0xFF111111)

private val CHOICE_WIDTH = 72.dp
private val CHOICE_HEIGHT = 54.dp
