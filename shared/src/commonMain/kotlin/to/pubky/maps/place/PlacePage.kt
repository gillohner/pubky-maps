package to.pubky.maps.place

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
 * The sections under the header, each shown only when it has something, each from its own source:
 * the card's photo and text, OSM details, Pubky tags, photos, posts, lists, "Also on" and the data
 * credits.
 */
fun LazyListScope.placeSections(
    page: PlacePageState,
    context: PlacePageContext,
    actions: PlacePageActions,
) {
    val card = page.card
    if (card == null && PlacePart.Card in page.loading) {
        item(key = "loading") { LoadingBlock() }
    }
    card?.image?.let { image ->
        item(key = "image") { CardImage(image, card.credit("image"), actions) }
    }
    card?.description?.let { description ->
        item(key = "description") { Description(description, card.credit("description"), actions) }
    }
    page.osm?.let { osm ->
        item(key = "details") { OsmDetailsSection(osm, context, actions) }
    }
    val tags = page.tags
    if (tags.isNotEmpty()) item(key = "tags") { TagsSection(tags, page.people, page.viewer) }
    if (page.photos.isNotEmpty()) item(key = "photos") { PhotosSection(page.photos, actions) }
    if (page.posts.isNotEmpty()) {
        item(key = "posts-title") { SectionTitle("Posts") }
        items(page.posts, key = { it.uri }) { post -> PostRow(post, page.people[post.author], context) }
        if (page.morePosts) {
            item(key = "posts-more") {
                TextButton(
                    onClick = actions.onLoadMorePosts,
                    enabled = !page.loadingMorePosts,
                    modifier = Modifier.padding(horizontal = 8.dp),
                ) { Text(if (page.loadingMorePosts) "Loading…" else "More posts") }
            }
        }
    }
    if (page.lists.isNotEmpty()) item(key = "lists") { ListsSection(page.lists, page.people) }
    if (page.nothingOnPubky) item(key = "nothing-on-pubky") { NothingOnPubky() }
    val alsoOn = card?.let { alsoOnShown(it.alsoOn, page.place.uri, context.language) }.orEmpty()
    if (alsoOn.isNotEmpty()) item(key = "also-on") { AlsoOnSection(alsoOn, actions) }
    item(key = "credits") { DataCredits(page) }
}

@Composable
private fun SectionTitle(text: String) {
    Column {
        HorizontalDivider(Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
        Text(
            text,
            style = MaterialTheme.typography.titleMedium,
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

@Composable
private fun RemoteImage(
    url: String,
    contentDescription: String?,
    modifier: Modifier,
) {
    val placeholder = ColorPainter(MaterialTheme.colorScheme.surfaceContainerHighest)
    AsyncImage(
        model = url,
        contentDescription = contentDescription,
        modifier = modifier,
        placeholder = placeholder,
        error = placeholder,
        fallback = placeholder,
        contentScale = ContentScale.Crop,
    )
}

@Composable
private fun CardImage(
    url: String,
    credit: PlaceCredit?,
    actions: PlacePageActions,
) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        RemoteImage(
            url,
            contentDescription = "Photo of the place",
            modifier =
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(12.dp)),
        )
        credit?.let { CreditLine(creditText("Photo", it), it, actions) }
    }
}

@Composable
private fun Description(
    text: String,
    credit: PlaceCredit?,
    actions: PlacePageActions,
) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(text, style = MaterialTheme.typography.bodyLarge)
        credit?.let { CreditLine(creditText("Text", it), it, actions) }
    }
}

/** A credit under an image or a text; tapping opens its source page. */
@Composable
private fun CreditLine(
    text: String,
    credit: PlaceCredit,
    actions: PlacePageActions,
) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier =
            Modifier
                .padding(top = 4.dp)
                .clickable { actions.onOpenLink(credit.licenseUrl ?: credit.sourceUrl) },
    )
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
            .padding(horizontal = 16.dp, vertical = 10.dp),
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
        if (schedule?.mentionsHolidays == true) {
            Text(
                "Hours may differ on holidays.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 40.dp, top = 4.dp),
            )
        }
    }
}

/** A person's name, else their short key. */
private fun personName(
    id: String,
    person: PlacePerson?,
): String = person?.name ?: shortPubkyId(id)

@Composable
private fun Avatar(
    id: String,
    person: PlacePerson?,
    size: Int = 28,
) {
    Box(
        Modifier
            .size(size.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.secondaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            personName(id, person).removePrefix("pubky").take(1).uppercase(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
        person?.avatarUrl?.let { url ->
            AsyncImage(model = url, contentDescription = null, modifier = Modifier.size(size.dp), contentScale = ContentScale.Crop)
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
) {
    Column {
        SectionTitle("Tags on Pubky")
        FlowRow(
            modifier = Modifier.padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            tags.forEach { tag ->
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = if (tag.mine) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                    contentColor = if (tag.mine) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                ) {
                    Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(tag.label, style = MaterialTheme.typography.labelLarge)
                        Spacer(Modifier.width(6.dp))
                        Text(tag.count.toString(), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
        // The user's own tags are said below, in words.
        val taggers =
            tags
                .flatMap { it.taggers }
                .distinct()
                .filter { it != viewer }
                .sortedByDescending { people[it]?.followed == true }
        if (taggers.isNotEmpty()) {
            Row(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                taggers.take(4).forEach { id ->
                    Avatar(id, people[id])
                    Spacer(Modifier.width(4.dp))
                }
                Spacer(Modifier.width(4.dp))
                Text(
                    taggersText(taggers, people),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        tags.firstOrNull { it.mine }?.let {
            Text(
                "You tagged it ${tags.filter { t -> t.mine }.joinToString(", ") { t -> t.label }}.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp),
            )
        }
    }
}

/** "Tagged by Anna and Ben", "Tagged by Anna, Ben and 3 others". */
internal fun taggersText(
    taggers: List<String>,
    people: Map<String, PlacePerson>,
): String {
    val names = taggers.take(2).map { personName(it, people[it]) }
    val rest = taggers.size - names.size
    return "Tagged by " +
        when {
            rest == 0 && names.size == 1 -> names[0]
            rest == 0 -> "${names[0]} and ${names[1]}"
            else -> "${names.joinToString(", ")} and $rest ${if (rest == 1) "other" else "others"}"
        }
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
        Avatar(post.author, author, size = 36)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            val then = Instant.fromEpochMilliseconds(post.indexedAt).toLocalDateTime(context.timeZone)
            Text(
                "${personName(post.author, author)} · ${ageText(context.nowMillis, post.indexedAt, then.date)}",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (post.content.isNotEmpty()) {
                Text(post.content, style = MaterialTheme.typography.bodyMedium, maxLines = 8, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun ListsSection(
    lists: List<PlaceList>,
    people: Map<String, PlacePerson>,
) {
    Column {
        SectionTitle(if (lists.size == 1) "In 1 list" else "In ${lists.size} lists")
        lists.forEach { list ->
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text(list.name ?: "A list", style = MaterialTheme.typography.bodyLarge)
                val by = if (list.mine) "Your list" else "By ${personName(list.author, people[list.author])}"
                val count = list.itemCount?.let { if (it == 1) "1 place" else "$it places" }
                Text(
                    listOfNotNull(by, count).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
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
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            links.forEach { link ->
                OutlinedButton(onClick = { actions.onOpenLink(link.uri) }, contentPadding = PaddingValues(horizontal = 12.dp)) {
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

/** Where the page's data came from, as the sources' licences ask. */
@Composable
private fun DataCredits(page: PlacePageState) {
    val parts =
        listOfNotNull(
            page.card?.let { "Place card: Pinakes" },
            page.card?.attribution ?: page.osm?.let { "© OpenStreetMap contributors (ODbL)" },
            "Tags and posts: Pubky (Nexus)".takeIf { page.tags.isNotEmpty() || page.posts.isNotEmpty() || page.photos.isNotEmpty() },
        )
    if (parts.isEmpty()) return
    Text(
        parts.joinToString(" · "),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 16.dp),
    )
}
