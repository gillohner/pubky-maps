package to.pubky.maps.place

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import to.pubky.maps.search.SourceAnswer
import kotlin.time.Clock

/** The OSM details a card brings; null when there is no card or it has none. */
private fun PlaceCardInfo?.osmDetails(): OsmDetails? = this?.facts?.let(OsmDetails::from)

/** A part of the place page that loads on its own. */
enum class PlacePart { Card, Social, Posts, Lists }

/**
 * The place page of [place]: what each source said so far. A part is in [loading] until its source
 * answered, in [failed] when no server of its list did; a failed part shows what the cache had.
 */
data class PlacePageState(
    val place: PlaceRef,
    val card: PlaceCardInfo? = null,
    val social: PlaceSocial? = null,
    /** Taggers, authors and list owners by pubky id. */
    val people: Map<String, PlacePerson> = emptyMap(),
    /** Replies about the place, newest first. */
    val posts: List<PlacePost> = emptyList(),
    /** More posts can be loaded. */
    val morePosts: Boolean = false,
    val loadingMorePosts: Boolean = false,
    val photos: List<PlacePhoto> = emptyList(),
    val lists: List<PlaceList> = emptyList(),
    val loading: Set<PlacePart> = PlacePart.entries.toSet(),
    val failed: Set<PlacePart> = emptySet(),
    /** When the cached card, posts and lists shown were fetched (epoch ms); null when nothing came from the cache. */
    val savedAt: Long? = null,
    /**
     * When the tags shown were fetched (epoch ms), when they came from the cache or the offline areas;
     * null when Nexus gave them now or none are shown.
     */
    val socialAt: Long? = null,
    /** The signed-in user's pubky id when the page was opened; null when signed out. */
    val viewer: String? = null,
    /**
     * The district and town the offline areas give the place ("Hottingen, Zürich"), when it was
     * opened without an address or a locality; null when no area covers it.
     */
    val locality: String? = null,
    /**
     * The OSM details kept for the offline areas, for a page whose card has none (no card could be
     * loaded, Pinakes has none, or it brings no OSM details); null when no area keeps any for the place.
     */
    val keptDetails: KeptDetails? = null,
) {
    /** Some part could not be loaded: the page shows saved data, or less. */
    val offline: Boolean get() = failed.isNotEmpty()

    /** The date of the oldest saved or kept part shown (epoch ms), for the offline note; null when none is. */
    val shownAt: Long?
        get() = listOfNotNull(savedAt, socialAt, keptDetails?.takeIf { cardOsm == null && osm != null }?.fetchedAt).minOrNull()

    /** Nexus and Pinakes answered and nobody tagged, wrote about or listed the place. */
    val nothingOnPubky: Boolean
        get() =
            PlacePart.Social !in loading && PlacePart.Posts !in loading && PlacePart.Lists !in loading &&
                social != null && social.tags.isEmpty() && posts.isEmpty() && photos.isEmpty() && lists.isEmpty()

    /** How many public lists hold the place: the card's count, at least the lists shown. */
    val listCount: Int get() = maxOf(card?.collections ?: 0, lists.size)

    /**
     * The OSM details of the card, else those kept for the offline areas; null when neither has any.
     * A card with details wins as a whole, so the page never mixes two sources' fields.
     */
    val osm: OsmDetails? get() = cardOsm ?: keptDetails?.facts?.let(OsmDetails::from)

    private val cardOsm: OsmDetails? get() = card.osmDetails()

    /** Tags, the user's own first, then by count; each tag's taggers friends first. */
    val tags: List<PlaceTag>
        get() =
            social
                ?.tags
                .orEmpty()
                .sortedWith(compareByDescending<PlaceTag> { it.mine }.thenByDescending { it.count })
                .map { tag -> tag.copy(taggers = tag.taggers.sortedByDescending { people[it]?.followed == true }) }
}

/**
 * Loads place pages: one at a time, the newest [open] replacing any page still loading. Each part
 * loads on its own and never waits for a part that does not need it: the card from Pinakes, then
 * tags and posts from Nexus for every link of the card, and the lists holding it. What was shown is
 * saved in [cache] and shown first the next time, so a place reopens offline. When Nexus does not
 * answer, the tags kept for the offline areas ([kept]) are shown if they are newer than the cache's,
 * so a place of a downloaded area shows its tags offline also when its page was never opened. A
 * place opened with a point but no address gets its district and town from the offline areas
 * ([localities]), so its page says where it is with no connection too, and a page whose card has no
 * OSM details the opening hours, phone, ... the offline areas keep for its element ([details]).
 */
class PlacePageController(
    private val data: PlaceData,
    private val cache: PlaceCache?,
    private val scope: CoroutineScope,
    private val kept: KeptPlaceSocial? = null,
    /** The signed-in user's pubky id, for their own tags and lists and friends first; null when signed out. */
    private val viewer: () -> String? = { null },
    private val clock: Clock = Clock.System,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val localities: OfflineLocality? = null,
    private val details: KeptPlaceDetails? = null,
) {
    private val _state = MutableStateFlow<PlacePageState?>(null)

    /** The open page, null when none is. */
    val state: StateFlow<PlacePageState?> = _state.asStateFlow()

    private var job: Job? = null
    private var moreJob: Job? = null
    private var localityJob: Job? = null

    /** Per Nexus resource: how many replies it has and how many were loaded. */
    private val replyPages = mutableMapOf<String, ReplyPage>()

    private data class ReplyPage(
        val total: Int,
        val loaded: Int,
    )

    /**
     * Opens the page of [place]; the same place again keeps what is loaded, its district and town
     * too (a search result's, when the same place is then tapped on the map).
     */
    fun open(place: PlaceRef) {
        val current = _state.value
        if (current != null && current.place.uri == place.uri) {
            if (current.place != place) {
                val locality = current.locality ?: current.place.locality
                _state.value = current.copy(place = place, locality = locality)
                if (locality == null && localityJob?.isActive != true) localityJob = scope.launch { loadLocality(place) }
            }
            return
        }
        job?.cancel()
        moreJob?.cancel()
        localityJob?.cancel()
        localityJob = null
        replyPages.clear()
        _state.value = PlacePageState(place, viewer = viewer())
        job = scope.launch { load(place) }
    }

    /** Closes the page. */
    fun close() {
        job?.cancel()
        moreJob?.cancel()
        localityJob?.cancel()
        job = null
        localityJob = null
        _state.value = null
    }

    /** Loads the next posts of the open page. */
    fun loadMorePosts() {
        val page = _state.value ?: return
        if (!page.morePosts || page.loadingMorePosts) return
        update(page.place) { it.copy(loadingMorePosts = true) }
        moreJob =
            scope.launch {
                val posts = morePosts()
                update(page.place) {
                    it.copy(
                        posts = (it.posts + posts.orEmpty()).distinctBy { post -> post.uri }.sortedByDescending { post -> post.indexedAt },
                        morePosts = hasMorePosts(),
                        loadingMorePosts = false,
                        failed = if (posts == null) it.failed + PlacePart.Posts else it.failed,
                    )
                }
            }
    }

    /**
     * The district and town of a pinned place opened without them or an address (a map tap, a Pubky
     * pin, a saved place, a link), from the phone alone: also for typed coordinates.
     */
    private suspend fun loadLocality(place: PlaceRef) {
        val localities = localities ?: return
        val point = place.point?.takeIf { place.pinned && place.address == null && place.locality == null } ?: return
        val locality = withContext(io) { runCatching { localities.localityOf(place.osm, point, place.name) }.getOrNull() } ?: return
        update(place) { it.copy(locality = locality) }
    }

    /** The OSM details the offline areas keep for the element of a place whose card has none. */
    private suspend fun loadDetails(place: PlaceRef) {
        val details = details ?: return
        val osm = place.osm ?: return
        val kept = withContext(io) { runCatching { details.detailsOf(osm) }.getOrNull() } ?: return
        update(place) { it.copy(keptDetails = kept) }
    }

    private suspend fun load(place: PlaceRef) {
        // Coordinates the user typed never leave the phone (search's rule): no source is asked; the
        // offline areas are on the phone.
        if (place.source == PlaceSource.Coordinates) {
            update(place) { it.copy(loading = emptySet()) }
            loadLocality(place)
            return
        }
        val viewer = viewer()
        // Saved for another account (or signed in when the user now is not): nothing in it is theirs.
        val cached = cache?.let { withContext(io) { runCatching { it.read(place.uri) }.getOrNull() } }?.forViewer(viewer)
        if (cached != null) {
            update(place) {
                it.copy(
                    card = cached.card,
                    social = cached.social,
                    people = cached.people.associateBy { p -> p.id },
                    posts = cached.posts,
                    photos = cached.photos,
                    lists = cached.lists,
                    savedAt = cached.contentAt ?: cached.savedAt,
                    socialAt = cached.social?.let { cached.socialAt ?: cached.savedAt },
                )
            }
        }
        // After the cache, so a saved street address is not replaced by the district and town for a moment.
        if (cached?.card?.address == null) localityJob = scope.launch { loadLocality(place) }
        val card =
            when (val answer = data.card(listOfNotNull(place.uri, place.osm?.url))) {
                is SourceAnswer.Answered -> {
                    update(place) { it.copy(card = answer.value, loading = it.loading - PlacePart.Card) }
                    answer.value
                }

                SourceAnswer.Unreachable -> {
                    update(place) { it.copy(loading = it.loading - PlacePart.Card, failed = it.failed + PlacePart.Card) }
                    cached?.card
                }
            }
        if (card.osmDetails() == null) loadDetails(place)
        coroutineScope {
            launch { loadSocial(place, card, viewer) }
            launch { loadLists(place, card, viewer) }
        }
        save(place)
    }

    /** Tags and counts of every link of the place, then its posts and photos, then the people in them. */
    private suspend fun loadSocial(
        place: PlaceRef,
        card: PlaceCardInfo?,
        viewer: String?,
    ) {
        val views =
            when (val answer = data.views(placeLinks(place, card), viewer)) {
                is SourceAnswer.Answered -> {
                    answer.value
                }

                SourceAnswer.Unreachable -> {
                    val keptSocial = kept?.let { withContext(io) { runCatching { it.socialOf(placeLinks(place, card)) }.getOrNull() } }
                    update(place) {
                        val newer = keptSocial?.takeIf { k -> it.social == null || (it.socialAt ?: 0) < k.fetchedAt }
                        it.copy(
                            social = newer?.social?.forViewer(viewer) ?: it.social,
                            socialAt = newer?.fetchedAt ?: it.socialAt,
                            loading = it.loading - PlacePart.Social - PlacePart.Posts,
                            failed = it.failed + PlacePart.Social + PlacePart.Posts,
                        )
                    }
                    return
                }
            }
        val social = mergeResourceViews(views)
        update(place) { it.copy(social = social, socialAt = null, loading = it.loading - PlacePart.Social) }
        views.filter { it.replies > 0 }.forEach { replyPages[it.id] = ReplyPage(total = it.replies, loaded = 0) }

        val (replies, embeds) =
            coroutineScope {
                val replies = async { morePosts() }
                val embeds =
                    async {
                        views
                            .filter { it.embeds > 0 }
                            .map { view -> async { data.posts(view.id, embeds = true, skip = 0, limit = PHOTO_POSTS) } }
                            .awaitAll()
                    }
                replies.await() to embeds.await()
            }
        val embedPosts = embeds.filterIsInstance<SourceAnswer.Answered<List<PlacePost>>>().flatMap { it.value }
        val posts = replies.orEmpty().sortedByDescending { it.indexedAt }
        val photos = (embedPosts + posts).sortedByDescending { it.indexedAt }.flatMap { it.images }.distinctBy { it.url }
        val postsFailed = replies == null || embeds.any { it == SourceAnswer.Unreachable }
        update(place) {
            it.copy(
                posts = if (replies == null) it.posts else posts,
                photos = if (postsFailed && photos.isEmpty()) it.photos else photos,
                morePosts = replies != null && hasMorePosts(),
                loading = it.loading - PlacePart.Posts,
                failed = if (postsFailed) it.failed + PlacePart.Posts else it.failed - PlacePart.Posts,
            )
        }
        loadPeople(place, social.tags.flatMap { it.taggers } + posts.map { it.author }, viewer)
    }

    /**
     * The next page of replies of every resource that has more, or null when Nexus did not answer for
     * one of them; then no page counts as read, so a retry asks for the same pages.
     */
    private suspend fun morePosts(): List<PlacePost>? {
        val asked = replyPages.filter { (_, page) -> page.loaded < page.total }
        val answers =
            coroutineScope {
                asked
                    .map { (id, page) -> async { id to data.posts(id, embeds = false, skip = page.loaded, limit = POSTS_PAGE) } }
                    .awaitAll()
            }
        if (answers.any { it.second == SourceAnswer.Unreachable }) return null
        return answers.flatMap { (id, answer) ->
            val posts = (answer as SourceAnswer.Answered).value
            val page = asked.getValue(id)
            // An empty page means Nexus has no more (some were deleted or are not posts).
            replyPages[id] = page.copy(loaded = if (posts.isEmpty()) page.total else page.loaded + POSTS_PAGE)
            posts
        }
    }

    private fun hasMorePosts(): Boolean = replyPages.values.any { it.loaded < it.total }

    /** The public lists holding the place, with their names from Nexus. */
    private suspend fun loadLists(
        place: PlaceRef,
        card: PlaceCardInfo?,
        viewer: String?,
    ) {
        if (card == null || card.collections == 0) {
            update(place) { it.copy(lists = if (card == null) it.lists else emptyList(), loading = it.loading - PlacePart.Lists) }
            return
        }
        val refs =
            when (val answer = data.collections(card.id, viewer)) {
                is SourceAnswer.Answered -> answer.value.items
                SourceAnswer.Unreachable -> null
            }
        val lists =
            refs?.let {
                if (it.isEmpty()) {
                    emptyList()
                } else {
                    when (val answer = data.lists(it.map { ref -> ref.uri }, viewer)) {
                        is SourceAnswer.Answered -> {
                            // Pinakes' order: the viewer's own first, then newest first.
                            val order = it.withIndex().associate { (i, ref) -> ref.uri to i }
                            answer.value.sortedBy { list -> order[list.uri] ?: Int.MAX_VALUE }
                        }

                        SourceAnswer.Unreachable -> {
                            null
                        }
                    }
                }
            }
        update(place) {
            it.copy(
                lists = lists ?: it.lists,
                loading = it.loading - PlacePart.Lists,
                failed = if (lists == null) it.failed + PlacePart.Lists else it.failed,
            )
        }
        if (lists != null) loadPeople(place, lists.map { it.author }, viewer)
    }

    /** Names and pictures of [ids] not known yet. Optional: without them the page shows short keys. */
    private suspend fun loadPeople(
        place: PlaceRef,
        ids: List<String>,
        viewer: String?,
    ) {
        val known = _state.value?.people.orEmpty()
        val wanted = ids.distinct().filter { it !in known }
        if (wanted.isEmpty()) return
        val answer = data.people(wanted, viewer) as? SourceAnswer.Answered ?: return
        update(place) { it.copy(people = it.people + answer.value.associateBy { p -> p.id }) }
    }

    private suspend fun save(place: PlaceRef) {
        val page = _state.value?.takeIf { it.place.uri == place.uri } ?: return
        // Nothing new to keep when every part failed.
        if (page.failed.containsAll(listOf(PlacePart.Card, PlacePart.Social))) return
        val now = clock.now().toEpochMilliseconds()
        val snapshot =
            PlaceSnapshot(
                uri = place.uri,
                card = page.card,
                social = page.social,
                people = page.people.values.toList(),
                posts = page.posts.take(POSTS_PAGE),
                photos = page.photos,
                lists = page.lists,
                // The cache keeps the newest; the parts shown from a saved or kept copy keep their dates, for
                // the offline note, and the tags' also to be compared with kept ones.
                savedAt = now,
                contentAt = page.savedAt?.takeIf { page.failed.any { it != PlacePart.Social } } ?: now,
                socialAt = page.social?.let { page.socialAt ?: now },
                viewer = page.viewer,
            )
        cache?.let { withContext(io) { runCatching { it.write(snapshot) } } }
    }

    /**
     * Kept tags were read with no viewer: the [viewer]'s own are the ones they are listed under. Nexus
     * lists a few taggers per label, so on a label more people used the user's own can show as not theirs.
     */
    private fun PlaceSocial.forViewer(viewer: String?): PlaceSocial =
        if (viewer == null) this else copy(tags = tags.map { it.copy(mine = it.mine || viewer in it.taggers) })

    /** Applies [change] to the page of [place], if it is still the open one. */
    private fun update(
        place: PlaceRef,
        change: (PlacePageState) -> PlacePageState,
    ) = _state.update { if (it != null && it.place.uri == place.uri) change(it) else it }

    companion object {
        const val POSTS_PAGE = 20

        /** Embedding posts read for photos. */
        const val PHOTO_POSTS = 40

        /**
         * The links Nexus is asked about for [place]: the link the user had, its OSM element, the card's
         * own link and the links people referenced for it, and the card's "Also on" links people
         * referenced (the same place on another service; not the ones a source derived), at most 100.
         */
        fun placeLinks(
            place: PlaceRef,
            card: PlaceCardInfo?,
        ): List<String> =
            (
                listOfNotNull(place.uri, place.osm?.url, card?.uri) + card?.refs.orEmpty() +
                    card
                        ?.alsoOn
                        ?.filterNot { it.derived }
                        ?.map { it.uri }
                        .orEmpty()
            ).distinct()
                .take(100)
    }
}
