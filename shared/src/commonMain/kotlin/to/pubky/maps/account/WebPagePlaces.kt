package to.pubky.maps.account

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import to.pubky.maps.cache.CachedAccount
import to.pubky.maps.place.PlaceData
import to.pubky.maps.search.SourceAnswer
import kotlin.time.Duration.Companion.seconds

/**
 * Which of the user's bookmarked web pages are places. The bookmarks folder is shared with other
 * apps: a web page is a place only when Pinakes has a place card for it (the app keeps a card
 * without an OpenStreetMap element under the card's URI, e.g. a museum's site), while an article
 * another app bookmarked is not one.
 */
interface WebPagePlaces {
    /** Whether [target] is a web page that needs a place card to be a place (the core's `needsPlaceCard`); map links do not. */
    fun needsCard(target: String): Boolean

    /** The answer [isPlace] gave for [uri] and still holds, without asking; null when there is none. */
    suspend fun known(uri: String): Boolean?

    /**
     * Whether the web page [uri] is a place: true when Pinakes' card for it is a place, false when
     * Pinakes does not know the page or knows it as something else, null when no Pinakes server
     * answered.
     */
    suspend fun isPlace(uri: String): Boolean?
}

/**
 * [WebPagePlaces] by the card reads of [data] (Pinakes through the user's server list). A place
 * answer is kept while the process lives; a not-a-place answer for [notPlaceKept] ms ([now] in epoch
 * ms), so a 404 from a later server of the list while the first was down, or a card retyped since,
 * hides a bookmark only until a refresh after that. At most [kept] answers are kept.
 */
class PinakesWebPagePlaces(
    private val rule: (String) -> Boolean,
    private val data: () -> PlaceData,
    private val now: () -> Long,
    private val kept: Int = KEPT,
    private val notPlaceKept: Long = NOT_PLACE_KEPT_MS,
) : WebPagePlaces {
    private val lock = Mutex()

    /** By page: whether it is a place, and when a server said so. */
    private val answers = HashMap<String, Pair<Boolean, Long>>()

    override fun needsCard(target: String): Boolean = rule(target)

    override suspend fun known(uri: String): Boolean? {
        val known = lock.withLock { answers[uri] } ?: return null
        return known.first.takeIf { it || now() - known.second < notPlaceKept }
    }

    override suspend fun isPlace(uri: String): Boolean? {
        known(uri)?.let { return it }
        val place =
            when (val answer = data().card(listOf(uri))) {
                is SourceAnswer.Answered -> answer.value?.isPlace == true
                SourceAnswer.Unreachable -> return null
            }
        lock.withLock { if (answers.size < kept || uri in answers) answers[uri] = place to now() }
        return place
    }

    private companion object {
        const val KEPT = 2000
        const val NOT_PLACE_KEPT_MS = 60 * 60 * 1000L
    }
}

/**
 * At most this many bookmarked web pages are asked about in one refresh; the others are kept until a
 * later refresh asks about them. Pages with a kept answer ([WebPagePlaces.known]) are not asked and
 * do not count, so each refresh reaches pages the ones before did not.
 */
internal const val MAX_PAGE_CHECKS = 100

/**
 * Web pages are checked this many at a time: the place pages and the map's Pubky places share
 * Pinakes, and a burst could make a server refuse them.
 */
private const val PARALLEL_CHECKS = 3

/**
 * The longest the checks of one refresh take; the pages not answered by then are kept. The user's
 * data from the homeserver is shown before them, so they never hold it back.
 */
internal val CHECKS_BUDGET = 10.seconds

/** [account] after the answers [places] already holds, and the web pages to ask about. */
internal class KnownPlaces(
    val account: CachedAccount,
    /** At most [MAX_PAGE_CHECKS] bookmarked web pages [places] holds no answer for, in bookmark order. */
    val unchecked: List<String>,
)

/**
 * [account] without the bookmarked web pages whose kept answer says they are no place (map links
 * and pages without a kept answer stay), and the pages to ask about.
 */
internal suspend fun withKnownPlaces(
    account: CachedAccount,
    places: WebPagePlaces,
): KnownPlaces {
    val notPlaces = HashSet<String>()
    val unchecked = ArrayList<String>()
    val pages =
        account.bookmarks
            .map { it.place }
            .filter(places::needsCard)
            .distinct()
    for (page in pages) {
        when (places.known(page)) {
            false -> {
                notPlaces += page
            }

            null -> {
                if (unchecked.size < MAX_PAGE_CHECKS) unchecked += page
            }

            true -> {}
        }
    }
    return KnownPlaces(account.withoutBookmarksOf(notPlaces), unchecked)
}

/**
 * Which of [pages] [places] says are no place. A page is not in the answer when its card is a place,
 * when no Pinakes server answered for it (a missing service never hides the user's data; once no
 * server answered, the pages not asked yet are not asked) and when no answer came within
 * [CHECKS_BUDGET].
 */
internal suspend fun notPlaces(
    pages: List<String>,
    places: WebPagePlaces,
): Set<String> {
    if (pages.isEmpty()) return emptySet()
    val permits = Semaphore(PARALLEL_CHECKS)
    val lock = Mutex()
    val notPlaces = HashSet<String>()
    var unreachable = false
    withTimeoutOrNull(CHECKS_BUDGET) {
        coroutineScope {
            pages
                .map { page ->
                    launch {
                        permits.withPermit {
                            if (lock.withLock { unreachable }) return@withPermit
                            val place = places.isPlace(page)
                            lock.withLock {
                                if (place == null) unreachable = true
                                if (place == false) notPlaces += page
                            }
                        }
                    }
                }.joinAll()
        }
    }
    return lock.withLock { notPlaces.toSet() }
}

/** This account without the bookmarks of [pages]; the same account when it has none of them. */
internal fun CachedAccount.withoutBookmarksOf(pages: Set<String>): CachedAccount =
    if (bookmarks.none { it.place in pages }) this else copy(bookmarks = bookmarks.filterNot { it.place in pages })
