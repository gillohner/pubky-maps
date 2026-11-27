package to.pubky.maps.place

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import to.pubky.maps.account.shortPubkyId
import to.pubky.maps.links.LinkProvider
import to.pubky.maps.links.OpenInAction
import to.pubky.maps.routing.DirectionsCard
import to.pubky.maps.routing.DirectionsUi
import to.pubky.maps.routing.RoutingIcons
import to.pubky.maps.ui.AppIcons
import to.pubky.maps.ui.TextIcon
import to.pubky.maps.write.Kept
import to.pubky.maps.write.KeptBadge
import to.pubky.maps.write.OwnList
import to.pubky.maps.write.OwnPlace
import to.pubky.maps.write.PublicTagDialog
import to.pubky.maps.write.WriteIcons
import to.pubky.maps.write.mergeOwnTags
import to.pubky.maps.write.ownPostRows
import to.pubky.maps.write.pendingText
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** What the place page can do, wired by the map. */
data class PlacePageActions(
    val onClose: () -> Unit,
    /** Shares the place; null where sharing is not available. */
    val onShare: (() -> Unit)?,
    /** The "Open in" apps switched on in settings, installed ones first. */
    val openIn: List<OpenInAction>,
    val onOpenIn: (OpenInAction) -> Unit,
    /** Opens a web, `tel:` or `mailto:` link in another app. */
    val onOpenLink: (String) -> Unit,
    val onLoadMorePosts: () -> Unit,
    /** The user's writes about the place (Save, Tag, Post, Photo, Add to list); null where there are none. */
    val writes: PlaceWriteActions? = null,
    /** Opens directions to the place; null where routing is not available or the place has no point. */
    val onDirections: (() -> Unit)? = null,
    /** The directions open to the place, shown under the actions; null when none are open. */
    val directions: DirectionsUi? = null,
)

/** What the user can do on the place page, and what they have about it. */
data class PlaceWriteActions(
    val own: OwnPlace,
    /** The signed-in user, null when signed out. */
    val viewer: String?,
    /** Saves the place, or removes it when it is saved. */
    val onToggleSave: () -> Unit,
    /** Opens the tag page. */
    val onTag: () -> Unit,
    /** Tags the place with a label people used, or removes the user's own tag. */
    val onToggleTag: (String) -> Unit,
    val onPost: () -> Unit,
    val onPhoto: () -> Unit,
    val onAddToList: () -> Unit,
    /** The user was told on this phone that tags are public; until then a one-tap tag asks first. */
    val publicTagsExplained: Boolean = true,
    val onPublicTagsExplained: () -> Unit = {},
    /** Writes wait for the homeserver (no connection, or [busy]). */
    val waiting: Boolean = false,
    val busy: Boolean = false,
)

/** The place page's clock and language, passed in so screenshots and tests are fixed. */
data class PlacePageContext(
    val now: LocalDateTime,
    val nowMillis: Long,
    val timeZone: TimeZone,
    /** The phone's language (ISO 639-1), for which Wikipedia to link. */
    val language: String,
    /** How far the place is from the user ("1.2 km"), null when unknown. */
    val distance: String?,
    /** The app whose link opened the place, null when it was not a link. */
    val provider: LinkProvider? = null,
)

private val Green = Color(0xFF188038)
private val GreenDark = Color(0xFF81C995)
private val Red = Color(0xFFD93025)
private val RedDark = Color(0xFFF28B82)

/**
 * The top of the place page: what it is and the actions. On a phone it is the part of the sheet that
 * shows at its lowest stop.
 */
@Composable
fun PlaceHeader(
    page: PlacePageState,
    context: PlacePageContext,
    actions: PlacePageActions,
    modifier: Modifier = Modifier,
) {
    Column(modifier.padding(start = 16.dp, end = 8.dp, bottom = 12.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            Text(
                text = page.title,
                style = MaterialTheme.typography.headlineSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier =
                    Modifier
                        .weight(1f)
                        .padding(top = 8.dp)
                        .semantics { heading() },
            )
            IconButton(onClick = actions.onClose) { Icon(AppIcons.Close, contentDescription = "Close") }
        }
        Column(Modifier.padding(end = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            val facts = listOfNotNull(page.categoryText(), context.distance)
            if (facts.isNotEmpty()) Text(facts.joinToString(" · "), style = MaterialTheme.typography.bodyMedium)
            page.detailText(context.provider)?.let { DetailLine(it, page.streetAddress) }
            page.osm
                ?.openingHours
                ?.let(OpeningSchedule::parse)
                ?.let { openingLine(it.status(context.now), context.now) }
                ?.let { line -> OpeningText(line) }
            OfflineNote(page, context)
        }
        // Google Maps' order: Directions, the place's own actions, then Share and Open in; the row
        // scrolls sideways when it is wider than the page.
        Row(
            modifier = Modifier.padding(top = 12.dp).horizontalScroll(rememberScrollState()).padding(end = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            actions.onDirections?.let { directions ->
                Button(onClick = directions) {
                    Icon(RoutingIcons.Directions, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Directions")
                }
            }
            val writes = actions.writes
            if (writes != null) {
                val saved = writes.own.isSaved
                FilledTonalButton(onClick = writes.onToggleSave) {
                    Icon(
                        if (saved) WriteIcons.Bookmark else WriteIcons.BookmarkBorder,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(if (saved) "Saved" else "Save")
                }
                ActionButton(WriteIcons.Tag, "Tag", writes.onTag)
                ActionButton(WriteIcons.Post, "Post", writes.onPost)
                ActionButton(WriteIcons.AddToList, "Add to list", writes.onAddToList)
            }
            actions.onShare?.let { share ->
                if (writes != null) {
                    ActionButton(AppIcons.Share, "Share", share)
                } else {
                    FilledTonalButton(onClick = share) {
                        Icon(AppIcons.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Share")
                    }
                }
            }
            if (actions.openIn.isNotEmpty()) OpenInButton(actions)
        }
        actions.directions?.let { DirectionsCard(it, Modifier.padding(top = 12.dp, end = 8.dp)) }
    }
}

@Composable
private fun ActionButton(
    icon: ImageVector,
    text: String,
    onClick: () -> Unit,
) {
    OutlinedButton(onClick = onClick) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(text)
    }
}

/** Whether the place is open now and when that changes, green when open and red when closed, as Google Maps shows it. */
@Composable
internal fun OpeningText(line: OpeningLine) {
    val dark = MaterialTheme.colorScheme.surface.luminanceIsDark()
    val color =
        when {
            line.open && dark -> GreenDark
            line.open -> Green
            dark -> RedDark
            else -> Red
        }
    Text(line.text, style = MaterialTheme.typography.bodyMedium, color = color, fontWeight = FontWeight.Medium)
}

private fun Color.luminanceIsDark(): Boolean = (0.299f * red + 0.587f * green + 0.114f * blue) < 0.5f

@Composable
private fun OfflineNote(
    page: PlacePageState,
    context: PlacePageContext,
) {
    if (!page.offline) return
    val saved = page.shownAt?.let { Instant.fromEpochMilliseconds(it).toLocalDateTime(context.timeZone) }
    val text =
        when {
            saved != null -> "Offline: showing what was saved on ${savedText(saved)}."
            PlacePart.Card in page.failed && PlacePart.Social in page.failed -> "Offline: Pubky details could not load."
            else -> "Some Pubky details could not load."
        }
    Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        TextIcon(
            AppIcons.CloudOff,
            contentDescription = null,
            size = 16.sp,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** "Open in" with the apps in a menu, like Google Maps' and CoMaps' share/open menus. */
@Composable
private fun OpenInButton(actions: PlacePageActions) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }) {
            Icon(PlaceIcons.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Open in")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            actions.openIn.forEach { action ->
                DropdownMenuItem(
                    text = { Text(if (action.inBrowser) "${action.title} (web)" else action.title) },
                    onClick = {
                        open = false
                        actions.onOpenIn(action)
                    },
                )
            }
        }
    }
}

/** Draws a remote image (see [LocalRemoteImage]). */
typealias RemoteImageContent = @Composable (
    url: String,
    contentDescription: String?,
    modifier: Modifier,
    placeholder: Boolean,
    onError: () -> Unit,
) -> Unit

/**
 * Draws a remote image: a grey placeholder while it loads when [placeholder] is set (else nothing, so
 * what is under it shows), [onError] when it cannot be loaded (the caller then leaves it out). The
 * app uses Coil; screenshot tests provide a local stand-in, since images load over the network.
 */
val LocalRemoteImage =
    staticCompositionLocalOf<RemoteImageContent> {
        { url, contentDescription, modifier, placeholder, onError ->
            val grey = if (placeholder) ColorPainter(MaterialTheme.colorScheme.surfaceContainerHighest) else null
            AsyncImage(
                model = url,
                contentDescription = contentDescription,
                modifier = modifier,
                placeholder = grey,
                error = grey,
                onError = { onError() },
                contentScale = ContentScale.Crop,
            )
        }
    }

/** A remote image that is left out once it fails to load. */
@Composable
private fun RemoteImage(
    url: String,
    contentDescription: String?,
    modifier: Modifier,
    onFailed: () -> Unit = {},
    overlay: @Composable BoxScope.() -> Unit = {},
) {
    var failed by remember(url) { mutableStateOf(false) }
    if (failed) return
    Box(modifier) {
        LocalRemoteImage.current(url, contentDescription, Modifier.matchParentSize(), true) {
            failed = true
            onFailed()
        }
        overlay()
    }
}

/** Section titles: clearly stronger than the text under them. */
private val SectionTitleWeight = FontWeight.SemiBold

/**
 * The sections under the header, each shown only when it has something, each from its own source:
 * the card's photo and text, OSM details, Pubky tags, photos, posts, lists, "Also on" and the data
 * credits (with the card text's credit).
 */
@Composable
fun PlaceSections(
    page: PlacePageState,
    context: PlacePageContext,
    actions: PlacePageActions,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        val card = page.card
        if (page.cardLoading) LoadingBlock()
        card?.image?.let { image -> CardImage(image, card.credit("image"), actions) }
        card?.description?.let { Description(it) }
        val osm = page.osm
        // The details come with the card: with no card shown yet, a section of coordinates alone would
        // grow and then jump when the card's rows arrive above them. A card from the cache is the page as it will
        // stay, so its coordinates show at once: waiting for the fresh card would push every section
        // under Details down by a row when it comes.
        val coordinates = page.coordinatesText?.takeUnless { page.cardLoading }
        if (osm != null || coordinates != null) DetailsSection(osm, coordinates, context, actions)
        val writes = actions.writes
        val viewer = writes?.viewer ?: page.viewer
        val tags = mergeOwnTags(page.tags, writes?.own?.labels, viewer)
        val pendingTags =
            writes
                ?.own
                ?.tags
                .orEmpty()
                .filter { it.pending }
                .map { it.label }
                .toSet()
        if (tags.isNotEmpty() || writes != null) TagsSection(tags, page.people, viewer, pendingTags, writes)
        val ownRows =
            if (writes != null &&
                viewer != null
            ) {
                ownPostRows(writes.own.posts + writes.own.photos, page.posts, viewer)
            } else {
                emptyList()
            }
        val photos = (ownRows.flatMap { it.images } + page.photos).distinctBy { it.url }
        if (photos.isNotEmpty() || writes != null) PhotosSection(photos, actions)
        val posts = ownRows.filter { it.images.isEmpty() || it.content.isNotEmpty() } + page.posts
        if (posts.isNotEmpty() || writes != null) {
            SectionTitle("Posts", action = writes?.let { w -> { SectionAction(WriteIcons.Post, "Write a post", w.onPost) } })
            posts.forEach { post -> PostRow(post, page.people[post.author], context) }
            if (page.morePosts) MorePostsRow(page.loadingMorePosts, actions.onLoadMorePosts)
        }
        val ownLists = writes?.own?.lists.orEmpty()
        // The user's own lists are known from their homeserver: one that no longer holds the place is left out.
        val lists = page.lists.filterNot { it.uri in writes?.own?.allLists.orEmpty() && ownLists.none { own -> own.uri == it.uri } }
        if (lists.isNotEmpty() || ownLists.isNotEmpty() ||
            writes != null
        ) {
            ListsSection(lists, page.listCount, ownLists, page.people, writes)
        }
        // With writes, each empty section says so beside its add button.
        if (page.nothingOnPubky && writes == null) NothingOnPubky()
        val alsoOn = card?.let { alsoOnShown(it.alsoOn, page.place.uri, context.language, hasWebsite = osm?.website != null) }.orEmpty()
        if (alsoOn.isNotEmpty()) AlsoOnSection(alsoOn, actions)
        DataCredits(page, actions)
    }
}

@Composable
private fun SectionTitle(
    text: String,
    action: (@Composable () -> Unit)? = null,
) {
    Column {
        HorizontalDivider(Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = SectionTitleWeight,
                modifier =
                    Modifier
                        .weight(1f)
                        .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp)
                        .semantics { heading() },
            )
            action?.let { Box(Modifier.padding(end = 8.dp, top = 8.dp)) { it() } }
        }
    }
}

/** A section's add button beside its title ("Write a post", "Add to list"). */
@Composable
private fun SectionAction(
    icon: ImageVector,
    text: String,
    onClick: () -> Unit,
) {
    TextButton(onClick = onClick, modifier = Modifier.heightIn(min = 48.dp)) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(text)
    }
}

/** Placeholder lines while the card loads; static, so the page does not flicker. */
@Composable
private fun LoadingBlock() {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        listOf(1f, 0.8f, 0.6f).forEach { share ->
            Box(
                Modifier
                    .fillMaxWidth(share)
                    .height(14.dp)
                    .clip(RoundedCornerShape(7.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest),
            )
        }
    }
}

/** Small text that must stay readable: 12 sp, the theme's secondary text colour (WCAG AA on its surfaces). */
@Composable
private fun SmallText(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = modifier)
}

/** The card's image with its credit as a small strip over its bottom edge; tapping the strip opens the licence. */
@Composable
private fun CardImage(
    url: String,
    credit: PlaceCredit?,
    actions: PlacePageActions,
) {
    RemoteImage(
        url,
        contentDescription = "Photo of the place",
        modifier =
            Modifier
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(12.dp)),
    ) {
        credit?.let {
            Text(
                creditText("Photo", it),
                style = MaterialTheme.typography.bodySmall,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier =
                    Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .background(Color.Black.copy(alpha = 0.6f))
                        .then(it.link?.let { link -> Modifier.clickable { actions.onOpenLink(link) } } ?: Modifier)
                        .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
    }
}

@Composable
private fun Description(text: String) {
    Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
}

/** OpenStreetMap's details of the place, then its coordinates (last, as CoMaps and OsmAnd list them). */
@Composable
private fun DetailsSection(
    osm: OsmDetails?,
    coordinates: String?,
    context: PlacePageContext,
    actions: PlacePageActions,
) {
    Column {
        SectionTitle("Details")
        osm?.let { OsmDetailRows(it, context, actions) }
        coordinates?.let { CoordinatesRow(it) }
    }
}

@Composable
private fun OsmDetailRows(
    osm: OsmDetails,
    context: PlacePageContext,
    actions: PlacePageActions,
) {
    Column {
        osm.openingHours?.let { HoursRow(it, context) }
        osm.phone?.let { phone ->
            DetailRow(PlaceIcons.Phone, phone, onClick = { actions.onOpenLink("tel:" + phone.filter { it.isDigit() || it == '+' }) })
        }
        osm.website?.let { site ->
            DetailRow(
                PlaceIcons.Website,
                site.removePrefix("https://").removePrefix("http://").trimEnd('/'),
                onClick = { actions.onOpenLink(site) },
            )
        }
        osm.email?.let { mail -> DetailRow(PlaceIcons.Mail, mail, onClick = { actions.onOpenLink("mailto:$mail") }) }
        osm.wheelchair?.let { DetailRow(PlaceIcons.Accessible, it.text) }
        if (osm.cuisine.isNotEmpty()) DetailRow(PlaceIcons.Restaurant, osm.cuisine.joinToString(", "))
        osm.level?.let { DetailRow(PlaceIcons.Floor, it) }
        osm.operator?.let { DetailRow(PlaceIcons.Business, "Run by $it") }
        if (osm.bitcoin.isNotEmpty()) DetailRow(PlaceIcons.Bolt, "Accepts " + osm.bitcoin.joinToString(", "))
    }
}

@Composable
private fun DetailRow(
    icon: ImageVector,
    text: String,
    onClick: (() -> Unit)? = null,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
                .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(16.dp))
        Text(
            text,
            style = MaterialTheme.typography.bodyLarge,
            color = if (onClick != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        )
    }
}

/**
 * The place's coordinates; a tap copies them, as on CoMaps' and OsmAnd's place pages (Google Maps copies
 * its address and plus code rows the same way). The copy icon says the row copies rather than opens.
 */
@Composable
private fun CoordinatesRow(coordinates: String) {
    val copy = rememberCopyText()
    val mark = rememberCopiedMark()
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clickable(onClickLabel = "Copy coordinates") {
                    copy(coordinates)
                    mark.copied()
                }.padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(AppIcons.Place, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(16.dp))
        Text(coordinates, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(8.dp))
        if (mark.shown) {
            CopiedText("Copied")
        } else {
            Icon(PlaceIcons.ContentCopy, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * The line under what the place is (its address, else its district and town, else its coordinates, and where
 * a link came from). When it holds a postal address, a long press copies the address alone, as in CoMaps and OsmAnd; TalkBack offers it as
 * the long-press action. The line then says "Address copied" for a moment: Android 12 and older show
 * nothing when an app copies, and a long press has no other mark of having worked.
 */
@Composable
private fun DetailLine(
    text: String,
    address: String?,
) {
    val copy = rememberCopyText()
    val mark = rememberCopiedMark()
    val haptics = LocalHapticFeedback.current
    val style = MaterialTheme.typography.bodyMedium
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    if (address == null) {
        Text(text, style = style, color = color)
        return
    }
    val copyAddress = {
        copy(address)
        mark.copied()
    }
    Box(
        Modifier
            .pointerInput(address) {
                detectTapGestures(
                    onLongPress = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        copyAddress()
                    },
                )
            }.semantics(mergeDescendants = true) {
                onLongClick("Copy address") {
                    copyAddress()
                    true
                }
            },
    ) {
        // The line stays laid out, hidden from sight and TalkBack, so it keeps its height while it says
        // "Address copied".
        if (mark.shown) {
            Text(text, style = style, color = Color.Transparent, modifier = Modifier.clearAndSetSemantics {})
            CopiedText("Address copied")
        } else {
            Text(text, style = style, color = color)
        }
    }
}

@Composable
private fun CopiedText(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
    )
}

/** Whether a row says it copied: for two seconds after its last copy. */
@Stable
private class CopiedMark {
    private var copies by mutableIntStateOf(0)
    var shown by mutableStateOf(false)
        private set

    fun copied() {
        copies++
        shown = true
    }

    @Composable
    fun Timer() {
        // Keyed on the count, so a second copy within the two seconds starts them again.
        LaunchedEffect(copies) {
            if (shown) {
                delay(2.seconds)
                shown = false
            }
        }
    }
}

@Composable
private fun rememberCopiedMark(): CopiedMark = remember { CopiedMark() }.also { it.Timer() }

/**
 * Puts plain text on the clipboard. The newer `LocalClipboard` takes a platform `ClipEntry` that common
 * code cannot build from text on Android; the older clipboard manager takes text on every target.
 */
@Suppress("DEPRECATION")
@Composable
private fun rememberCopyText(): (String) -> Unit {
    val clipboard = LocalClipboardManager.current
    return remember(clipboard) { { text -> clipboard.setText(AnnotatedString(text)) } }
}

/** The opening hours: the week from today, opened with a tap; as written when the page cannot read them. */
@Composable
private fun HoursRow(
    text: String,
    context: PlacePageContext,
) {
    val schedule = OpeningSchedule.parse(text)
    val week = schedule?.let { weekRows(it, context.now.date) }
    var expanded by remember { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxWidth()
            .then(if (week != null) Modifier.clickable { expanded = !expanded } else Modifier)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(PlaceIcons.Schedule, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(16.dp))
            val today = week?.first()
            Text(
                text = if (today != null) "Today: ${today.hours}" else text,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            if (week != null) {
                Icon(
                    if (expanded) PlaceIcons.ExpandLess else PlaceIcons.ExpandMore,
                    contentDescription = if (expanded) "Hide the week" else "Show the week",
                )
            }
        }
        if (week != null && expanded) {
            Column(Modifier.padding(start = 40.dp, top = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                week.forEach { row ->
                    val weight = if (row.today) FontWeight.Bold else FontWeight.Normal
                    Row {
                        Text(row.day, style = MaterialTheme.typography.bodyMedium, fontWeight = weight, modifier = Modifier.width(112.dp))
                        Text(row.hours, style = MaterialTheme.typography.bodyMedium, fontWeight = weight)
                    }
                }
            }
        }
        if (schedule?.mentionsHolidays == true) SmallText("Hours may differ on holidays.", Modifier.padding(start = 40.dp, top = 4.dp))
    }
}

/** A person's name, else their short key. */
private fun personName(
    id: String,
    person: PlacePerson?,
): String = person?.name ?: shortPubkyId(id)

/** A person's picture, else the first letter of their name, else a person icon (never a key's first character). */
@Composable
private fun Avatar(
    person: PlacePerson?,
    size: Int = 32,
) {
    Box(
        Modifier
            .size(size.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.secondaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        val initial = person?.name?.firstOrNull { it.isLetter() }
        if (initial != null) {
            Text(initial.uppercase(), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSecondaryContainer)
        } else {
            Icon(
                AppIcons.AccountCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.size((size * 3 / 4).dp),
            )
        }
        person?.avatarUrl?.let { url ->
            // No placeholder: the initial or icon under it shows while it loads and when it fails.
            LocalRemoteImage.current(url, null, Modifier.size(size.dp), false) {}
        }
    }
}

/** Pubky tags with their counts, the user's own marked; who tagged, friends first. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TagsSection(
    tags: List<PlaceTag>,
    people: Map<String, PlacePerson>,
    viewer: String?,
    pending: Set<String>,
    writes: PlaceWriteActions?,
) {
    // A one-tap tag on a label the user has not used asks once per phone whether they know it is public.
    var asking by remember { mutableStateOf<String?>(null) }
    asking?.let { label ->
        if (writes != null) {
            PublicTagDialog(
                label = label,
                onConfirm = {
                    asking = null
                    writes.onPublicTagsExplained()
                    writes.onToggleTag(label)
                },
                onDismiss = { asking = null },
            )
        }
    }
    Column {
        // Tags are the one write a single tap sends: the section says they are public.
        SectionTitle("Tags on Pubky", action = writes?.let { { KeptBadge(Kept.Public) } })
        FlowRow(
            modifier = Modifier.padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            tags.forEach { tag ->
                // 48 dp tall like a touch target: s2c makes the chips toggle the user's own tag.
                // With writes, a tap adds the same label (one-tap tag) or removes the user's own.
                val toggle =
                    writes?.let { w ->
                        {
                            if (!tag.mine && !w.publicTagsExplained) asking = tag.label else w.onToggleTag(tag.label)
                        }
                    }
                Box(
                    Modifier
                        .heightIn(min = 48.dp)
                        .then(
                            if (toggle != null) {
                                Modifier.clickable(
                                    onClickLabel = if (tag.mine) "Remove your tag" else "Tag it ${tag.label} too",
                                    onClick = toggle,
                                )
                            } else {
                                Modifier
                            },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    val colors = MaterialTheme.colorScheme
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = if (tag.mine) colors.primaryContainer else colors.surfaceContainerHigh,
                        contentColor = if (tag.mine) colors.onPrimaryContainer else colors.onSurface,
                    ) {
                        Row(
                            Modifier.heightIn(min = 36.dp).padding(horizontal = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (tag.label in pending) {
                                TextIcon(
                                    WriteIcons.Pending,
                                    contentDescription = pendingText(writes?.waiting == true, writes?.busy == true),
                                    size = 14.sp,
                                )
                                Spacer(Modifier.width(4.dp))
                            }
                            Text(tag.label, style = MaterialTheme.typography.labelLarge)
                            Spacer(Modifier.width(6.dp))
                            Text(tag.count.toString(), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
        if (writes != null) {
            Box(Modifier.heightIn(min = 48.dp).padding(start = 16.dp), contentAlignment = Alignment.CenterStart) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surface,
                    contentColor = MaterialTheme.colorScheme.primary,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = writes.onTag),
                ) {
                    Row(Modifier.heightIn(min = 36.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        TextIcon(AppIcons.Add, contentDescription = null, size = 16.sp)
                        Spacer(Modifier.width(4.dp))
                        Text("Add tag", style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
            if (tags.isEmpty()) SmallText("Nobody tagged it yet.", Modifier.padding(horizontal = 16.dp))
            if (pending.isNotEmpty()) {
                SmallText(
                    "Tags with a clock: ${pendingText(writes.waiting, writes.busy).replaceFirstChar { it.lowercase() }}",
                    Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp),
                )
            }
        }
        val line = taggersLine(tags, people, viewer)
        if (line != null) {
            Row(Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                line.shown.forEach { id ->
                    Avatar(people[id])
                    Spacer(Modifier.width(4.dp))
                }
                Spacer(Modifier.width(4.dp))
                Text(line.text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        val mine = tags.filter { it.mine }
        if (mine.isNotEmpty()) {
            SmallText("You tagged it ${mine.joinToString(", ") { it.label }}.", Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp))
        }
    }
}

/** The people shown with avatars and the line beside them; one count for both. */
internal data class TaggersLine(
    val shown: List<String>,
    val text: String,
)

/**
 * "Tagged by Anna, Ben and 3 others": the first two people (the ones the user follows first) are
 * named and shown; the others are the distinct people over all labels, a person who used several
 * labels counted once, and the people Nexus counts on a label but does not list (it lists five per
 * label): when those could be people already counted, the line says "at least" the sure number. The user is said apart ("You tagged it …") and counted nowhere. Null when nobody but the
 * user tagged.
 */
internal fun taggersLine(
    tags: List<PlaceTag>,
    people: Map<String, PlacePerson>,
    viewer: String?,
): TaggersLine? {
    val listed =
        tags
            .flatMap { it.taggers }
            .distinct()
            .filter { it != viewer }
            .sortedByDescending { people[it]?.followed == true }
    if (listed.isEmpty()) return null
    val named = listed.take(2)
    // Unlisted people per label; the user is one of them on a label they used but Nexus did not list them for.
    val unlisted =
        tags.map { tag ->
            val hidden = (tag.count - tag.taggers.size).coerceAtLeast(0)
            if (tag.mine && viewer !in tag.taggers) (hidden - 1).coerceAtLeast(0) else hidden
        }
    // At most every unlisted person is someone new; at least the largest label is (its people are
    // distinct). Nexus does not say who the unlisted are, so a range is said as "at least".
    val most = listed.size + unlisted.sum()
    val least = maxOf(listed.size, tags.maxOf { tag -> tag.count - if (tag.mine) 1 else 0 })
    val others = least - named.size
    val atLeast = if (least < most) "at least " else ""
    val names = named.map { personName(it, people[it]) }
    val text =
        "Tagged by " +
            when {
                others == 0 && names.size == 1 -> names[0]
                others == 0 -> "${names[0]} and ${names[1]}"
                else -> "${names.joinToString(", ")} and $atLeast$others ${if (others == 1) "other" else "others"}"
            }
    return TaggersLine(named, text)
}

@Composable
private fun PhotosSection(
    photos: List<PlacePhoto>,
    actions: PlacePageActions,
) {
    // Photos that fail to load are left out; with none left, so is the section.
    val failed = remember(photos) { mutableStateListOf<String>() }
    val shown = photos.filterNot { it.url in failed }
    if (shown.isEmpty() && actions.writes == null) return
    Column {
        SectionTitle("Photos")
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            actions.writes?.let { writes ->
                item(key = "add-photo") {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        contentColor = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(120.dp).clip(RoundedCornerShape(8.dp)).clickable(onClick = writes.onPhoto),
                    ) {
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Icon(WriteIcons.AddPhoto, contentDescription = null)
                            Spacer(Modifier.height(8.dp))
                            // At a large font the label wraps: centred it stays under the icon, and the
                            // padding keeps its lines off the tile's rounded edges.
                            Text(
                                "Add photo",
                                style = MaterialTheme.typography.labelLarge,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(horizontal = 8.dp),
                            )
                        }
                    }
                }
            }
            items(shown, key = { it.url }) { photo ->
                RemoteImage(
                    photo.thumbnailUrl,
                    contentDescription = "Photo",
                    modifier =
                        Modifier
                            .size(120.dp)
                            .clip(RoundedCornerShape(8.dp))
                            // A photo not sent yet is a file on this phone: nothing to open.
                            .then(if (!photo.url.startsWith("file://")) Modifier.clickable { actions.onOpenLink(photo.url) } else Modifier),
                    onFailed = { failed += photo.url },
                )
            }
        }
    }
}

@Composable
private fun PostRow(
    post: PlacePost,
    author: PlacePerson?,
    context: PlacePageContext,
) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Avatar(author, size = 40)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            val then = Instant.fromEpochMilliseconds(post.indexedAt).toLocalDateTime(context.timeZone)
            val age = if (post.pending) "not sent yet" else ageText(context.nowMillis, post.indexedAt, then.date)
            Text(
                "${personName(post.author, author)} · $age",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (post.content.isNotEmpty()) {
                Text(post.content, style = MaterialTheme.typography.bodyLarge, maxLines = 8, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** "More posts" as a full-width row at the section's margin. */
@Composable
private fun MorePostsRow(
    loading: Boolean,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(enabled = !loading, onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            if (loading) "Loading…" else "More posts",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f),
        )
        if (!loading) Icon(PlaceIcons.ExpandMore, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
    }
}

/**
 * The public lists holding the place, as text: there is no list screen yet, so the rows do not look
 * tappable (a chevron would promise one).
 */
@Composable
private fun ListsSection(
    lists: List<PlaceList>,
    listCount: Int,
    ownLists: List<OwnList>,
    people: Map<String, PlacePerson>,
    writes: PlaceWriteActions?,
) {
    // The user's own lists come from their homeserver (Pinakes lags, and private ones are not public).
    val others = lists.filterNot { list -> ownLists.any { it.uri == list.uri } }
    val ownPublicCounted = lists.size - others.size
    val count = maxOf(listCount - ownPublicCounted, others.size) + ownLists.size
    Column {
        SectionTitle(
            when (count) {
                0 -> "Lists"
                1 -> "In 1 list"
                else -> "In $count lists"
            },
            action = writes?.let { w -> { SectionAction(WriteIcons.AddToList, "Add to list", w.onAddToList) } },
        )
        ownLists.forEach { list ->
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text(list.name, style = MaterialTheme.typography.bodyLarge)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    KeptBadge(list.kept)
                    Spacer(Modifier.width(8.dp))
                    val places = if (list.items.size == 1) "1 place" else "${list.items.size} places"
                    SmallText("Your list · $places" + if (list.pending) " · not sent yet" else "")
                }
            }
        }
        if (count == 0) SmallText("Not in any list yet.", Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        others.forEach { list ->
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text(list.name ?: "A list", style = MaterialTheme.typography.bodyLarge)
                val by = if (list.mine) "Your list" else "By ${personName(list.author, people[list.author])}"
                val places = list.itemCount?.let { if (it == 1) "1 place" else "$it places" }
                SmallText(listOfNotNull(by, places).joinToString(" · "))
            }
        }
        if (count > others.size + ownLists.size) {
            SmallText("Showing ${others.size + ownLists.size} of $count.", Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AlsoOnSection(
    links: List<AlsoOnLink>,
    actions: PlacePageActions,
) {
    Column {
        SectionTitle("Also on")
        FlowRow(
            modifier = Modifier.padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            links.forEach { link ->
                OutlinedButton(
                    onClick = { actions.onOpenLink(link.uri) },
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text(link.site)
                }
            }
        }
    }
}

@Composable
private fun NothingOnPubky() {
    Text(
        "Nobody on Pubky has tagged or written about this place yet.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
    )
}

/**
 * Where the page's data came from, as the sources' licences ask: one line per source at the end,
 * the card text's credit among them (tap opens its licence or source).
 */
@Composable
private fun DataCredits(
    page: PlacePageState,
    actions: PlacePageActions,
) {
    val textCredit = page.card?.takeIf { it.description != null }?.credit("description")
    val lines =
        listOfNotNull(
            page.card?.let { "Place card: Pinakes" },
            page.card?.attribution ?: page.osm?.let { "© OpenStreetMap contributors (ODbL)" },
            "Tags and posts: Pubky (Nexus)".takeIf { page.tags.isNotEmpty() || page.posts.isNotEmpty() || page.photos.isNotEmpty() },
        )
    if (lines.isEmpty() && textCredit == null) return
    Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        textCredit?.let { credit ->
            SmallText(
                creditText("Text", credit),
                credit.link?.let { link -> Modifier.clickable { actions.onOpenLink(link) } } ?: Modifier,
            )
        }
        lines.forEach { SmallText(it) }
    }
}
