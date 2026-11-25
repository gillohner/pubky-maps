package to.pubky.maps.place

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import to.pubky.maps.account.shortPubkyId
import to.pubky.maps.links.LinkProvider
import to.pubky.maps.links.OpenInAction
import to.pubky.maps.ui.AppIcons
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
            page.detailText(context.provider)?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            page.osm
                ?.openingHours
                ?.let(OpeningSchedule::parse)
                ?.let { openingLine(it.status(context.now), context.now) }
                ?.let { line -> OpeningText(line) }
            OfflineNote(page, context)
        }
        Row(
            modifier = Modifier.padding(top = 12.dp, end = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            actions.onShare?.let { share ->
                FilledTonalButton(onClick = share) {
                    Icon(AppIcons.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Share")
                }
            }
            if (actions.openIn.isNotEmpty()) OpenInButton(actions)
        }
    }
}

@Composable
private fun OpeningText(line: OpeningLine) {
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
    val saved = page.savedAt?.let { Instant.fromEpochMilliseconds(it).toLocalDateTime(context.timeZone) }
    val text =
        when {
            saved != null -> "Offline: showing what was saved on ${savedText(saved)}."
            PlacePart.Card in page.failed && PlacePart.Social in page.failed -> "Offline: Pubky details could not load."
            else -> "Some Pubky details could not load."
        }
    Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(
            AppIcons.CloudOff,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
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

/**
 * Draws a remote image: [onError] when it cannot be loaded (the caller then leaves it out). The app
 * uses Coil; screenshot tests provide a local stand-in, since images load over the network.
 */
val LocalRemoteImage =
    staticCompositionLocalOf<@Composable (url: String, contentDescription: String?, modifier: Modifier, onError: () -> Unit) -> Unit> {
        { url, contentDescription, modifier, onError ->
            val placeholder = ColorPainter(MaterialTheme.colorScheme.surfaceContainerHighest)
            AsyncImage(
                model = url,
                contentDescription = contentDescription,
                modifier = modifier,
                placeholder = placeholder,
                error = placeholder,
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
    overlay: @Composable BoxScope.() -> Unit = {},
) {
    var failed by remember(url) { mutableStateOf(false) }
    if (failed) return
    Box(modifier) {
        LocalRemoteImage.current(url, contentDescription, Modifier.matchParentSize()) { failed = true }
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
        if (card == null && PlacePart.Card in page.loading) LoadingBlock()
        card?.image?.let { image -> CardImage(image, card.credit("image"), actions) }
        card?.description?.let { Description(it) }
        val osm = page.osm
        osm?.let { OsmDetailsSection(it, context, actions) }
        val tags = page.tags
        if (tags.isNotEmpty()) TagsSection(tags, page.people, page.viewer, page.taggerCount)
        if (page.photos.isNotEmpty()) PhotosSection(page.photos, actions)
        if (page.posts.isNotEmpty()) {
            SectionTitle("Posts")
            page.posts.forEach { post -> PostRow(post, page.people[post.author], context) }
            if (page.morePosts) MorePostsRow(page.loadingMorePosts, actions.onLoadMorePosts)
        }
        if (page.lists.isNotEmpty()) ListsSection(page.lists, page.listCount, page.people)
        if (page.nothingOnPubky) NothingOnPubky()
        val alsoOn = card?.let { alsoOnShown(it.alsoOn, page.place.uri, context.language, hasWebsite = osm?.website != null) }.orEmpty()
        if (alsoOn.isNotEmpty()) AlsoOnSection(alsoOn, actions)
        DataCredits(page, actions)
    }
}

@Composable
private fun SectionTitle(text: String) {
    Column {
        HorizontalDivider(Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
        Text(
            text,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = SectionTitleWeight,
            modifier =
                Modifier
                    .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp)
                    .semantics { heading() },
        )
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
                        .clickable { actions.onOpenLink(it.licenseUrl ?: it.sourceUrl) }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
    }
}

@Composable
private fun Description(text: String) {
    Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
}

@Composable
private fun OsmDetailsSection(
    osm: OsmDetails,
    context: PlacePageContext,
    actions: PlacePageActions,
) {
    Column {
        SectionTitle("Details")
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
            LocalRemoteImage.current(url, null, Modifier.size(size.dp)) {}
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
    taggerCount: Int,
) {
    Column {
        SectionTitle("Tags on Pubky")
        FlowRow(
            modifier = Modifier.padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            tags.forEach { tag ->
                // 48 dp tall like a touch target: s2c makes the chips toggle the user's own tag.
                Box(Modifier.heightIn(min = 48.dp), contentAlignment = Alignment.Center) {
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
                            Text(tag.label, style = MaterialTheme.typography.labelLarge)
                            Spacer(Modifier.width(6.dp))
                            Text(tag.count.toString(), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
        val line = taggersLine(tags, people, viewer, taggerCount)
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
 * "Tagged by Anna, Ben and 3 others": the first two named people the user follows first, and the
 * others counted from Nexus' tagger count of the place (people Nexus did not list included); the
 * user's own tags are said apart. The avatars are the people named. Null when nobody but the user
 * tagged.
 */
internal fun taggersLine(
    tags: List<PlaceTag>,
    people: Map<String, PlacePerson>,
    viewer: String?,
    taggerCount: Int,
): TaggersLine? {
    val listed =
        tags
            .flatMap { it.taggers }
            .distinct()
            .filter { it != viewer }
            .sortedByDescending { people[it]?.followed == true }
    if (listed.isEmpty()) return null
    val named = listed.take(2)
    val mine = if (tags.any { it.mine }) 1 else 0
    val others = (maxOf(taggerCount - mine, listed.size) - named.size).coerceAtLeast(0)
    val names = named.map { personName(it, people[it]) }
    val text =
        "Tagged by " +
            when {
                others == 0 && names.size == 1 -> names[0]
                others == 0 -> "${names[0]} and ${names[1]}"
                else -> "${names.joinToString(", ")} and $others ${if (others == 1) "other" else "others"}"
            }
    return TaggersLine(named, text)
}

@Composable
private fun PhotosSection(
    photos: List<PlacePhoto>,
    actions: PlacePageActions,
) {
    Column {
        SectionTitle("Photos")
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(photos, key = { it.url }) { photo ->
                RemoteImage(
                    photo.thumbnailUrl,
                    contentDescription = "Photo",
                    modifier =
                        Modifier
                            .size(120.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { actions.onOpenLink(photo.url) },
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
            Text(
                "${personName(post.author, author)} · ${ageText(context.nowMillis, post.indexedAt, then.date)}",
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
    count: Int,
    people: Map<String, PlacePerson>,
) {
    Column {
        SectionTitle(if (count == 1) "In 1 list" else "In $count lists")
        lists.forEach { list ->
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text(list.name ?: "A list", style = MaterialTheme.typography.bodyLarge)
                val by = if (list.mine) "Your list" else "By ${personName(list.author, people[list.author])}"
                val places = list.itemCount?.let { if (it == 1) "1 place" else "$it places" }
                SmallText(listOfNotNull(by, places).joinToString(" · "))
            }
        }
        if (count > lists.size) SmallText("Showing ${lists.size} of $count.", Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
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
                Modifier.clickable { actions.onOpenLink(credit.licenseUrl ?: credit.sourceUrl) },
            )
        }
        lines.forEach { SmallText(it) }
    }
}
