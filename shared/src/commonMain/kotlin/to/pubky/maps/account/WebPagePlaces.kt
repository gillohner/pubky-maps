package to.pubky.maps.account

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import to.pubky.maps.cache.CachedAccount
import to.pubky.maps.place.PlaceData
import to.pubky.maps.search.SourceAnswer
import kotlin.time.Duration.Companion.seconds

/**
 * Which of the web pages the user bookmarked, tagged or put in a collection are places. The
 * bookmarks, tags and collections folders are shared with other apps: a web page is a place only
 * when Pinakes has a place card for it (the app keeps a card without an OpenStreetMap element under
 * the card's URI, e.g. a museum's site), while an article another app bookmarked, tagged or collected
 * is not one. The account refresh and the collections' reads ask the same instance.
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
 * hides a bookmark, tag or collection item only until a refresh after that. At most [kept] answers
 * are kept.
 *
 * At most [PARALLEL_CHECKS] card reads run at a time, whoever asks: the account refresh and a collections
 * read both check pages at sign-in, and they share Pinakes with the place pages and the map's Pubky
 * places, so a burst could make a server refuse them. A page asked about while its read runs waits
 * for that read's answer instead of reading the card again (a page both bookmarked and in a
 * collection); when that read is cancelled (its refresh's budget ran out or a new refresh started),
 * the next one waiting reads it.
 */
class PinakesWebPagePlaces(
    private val rule: (String) -> Boolean,
    private val data: () -> PlaceData,
    private val now: () -> Long,
    private val kept: Int = KEPT,
    private val notPlaceKept: Long = NOT_PLACE_KEPT_MS,
) : WebPagePlaces {
    private val lock = Mutex()
    private val permits = Semaphore(PARALLEL_CHECKS)

    /** By page: the answer of its card read that runs now. */
    private val reading = HashMap<String, CompletableDeferred<Boolean?>>()

    /** By page: whether it is a place, and when a server said so. */
    private val answers = HashMap<String, Pair<Boolean, Long>>()

    override fun needsCard(target: String): Boolean = rule(target)

    override suspend fun known(uri: String): Boolean? = lock.withLock { keptAnswer(uri) }

    /** The kept answer for [uri] that still holds; under [lock]. */
    private fun keptAnswer(uri: String): Boolean? {
        val known = answers[uri] ?: return null
        return known.first.takeIf { it || now() - known.second < notPlaceKept }
    }

    override suspend fun isPlace(uri: String): Boolean? {
        while (true) {
            var own = false
            // One look under the lock, so a read that ends meanwhile is not started again.
            val answer =
                lock.withLock {
                    keptAnswer(uri)?.let { return it }
                    reading.getOrPut(uri) { CompletableDeferred<Boolean?>().also { own = true } }
                }
            if (own) return read(uri, answer)
            try {
                return answer.await()
            } catch (e: CancellationException) {
                // This caller was cancelled itself, or the read it waited for was: then it reads.
                currentCoroutineContext().ensureActive()
            }
        }
    }

    /**
     * Reads the card of [uri] and hands its answer, or its failure (also its cancellation), to those
     * waiting on [answer]. The bookkeeping cannot be cancelled, so no one waits on a read that ended.
     */
    private suspend fun read(
        uri: String,
        answer: CompletableDeferred<Boolean?>,
    ): Boolean? {
        val read =
            runCatching {
                permits.withPermit {
                    when (val card = data().card(listOf(uri))) {
                        is SourceAnswer.Answered -> card.value?.isPlace == true
                        SourceAnswer.Unreachable -> null
                    }
                }
            }
        val place = read.getOrNull()
        val failure = read.exceptionOrNull()
        withContext(NonCancellable) {
            lock.withLock {
                try {
                    if (place != null && (answers.size < kept || uri in answers)) answers[uri] = place to now()
                } finally {
                    reading.remove(uri)
                    if (failure != null) answer.completeExceptionally(failure) else answer.complete(place)
                }
            }
        }
        failure?.let { throw it }
        return place
    }

    private companion object {
        const val KEPT = 2000
        const val NOT_PLACE_KEPT_MS = 60 * 60 * 1000L
    }
}

/**
 * At most this many bookmarked or tagged web pages are asked about in one account refresh, and at
 * most this many web pages in collections in one read of the collections ([to.pubky.maps.write.knownListPages]);
 * the others are kept until a later one asks about them. Pages with a kept answer
 * ([WebPagePlaces.known]) are not asked and do not count, so each refresh or read reaches pages the
 * ones before did not.
 */
internal const val MAX_PAGE_CHECKS = 100

/**
 * Web pages are checked this many at a time, by each caller of [notPlaces] (so it stops soon once no
 * server answers) and by [PinakesWebPagePlaces] for all of them together. With every server down,
 * each caller's pages already waiting for one of the shared reads are still asked about, so the
 * account refresh and a collections read at once make up to twice this many reads, in two rounds.
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
    /**
     * At most [MAX_PAGE_CHECKS] web pages [places] holds no answer for: the bookmarked ones in
     * bookmark order, then the tagged ones in tag order.
     */
    val unchecked: List<String>,
)

/**
 * [account] without the bookmarks and tags of web pages whose kept answer says they are no place
 * (map links and pages without a kept answer stay), and the pages to ask about.
 */
internal suspend fun withKnownPlaces(
    account: CachedAccount,
    places: WebPagePlaces,
): KnownPlaces {
    val notPlaces = HashSet<String>()
    val unchecked = ArrayList<String>()
    val pages =
        (account.bookmarks.map { it.place } + account.tags.map { it.place })
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
    return KnownPlaces(account.withoutPagesOf(notPlaces), unchecked)
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

/** This account without the bookmarks and tags of [pages]; the same account when it has none of them. */
internal fun CachedAccount.withoutPagesOf(pages: Set<String>): CachedAccount =
    if (bookmarks.none { it.place in pages } && tags.none { it.place in pages }) {
        this
    } else {
        copy(bookmarks = bookmarks.filterNot { it.place in pages }, tags = tags.filterNot { it.place in pages })
    }
