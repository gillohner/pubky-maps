package to.pubky.maps.write

import to.pubky.maps.account.MAX_PAGE_CHECKS
import to.pubky.maps.account.WebPagePlaces

/** The web pages in [lists] that [places] already knows are no place, and at most [MAX_PAGE_CHECKS] it holds no answer for. */
internal class KnownListPages(
    val notPlaces: Set<String>,
    /** In list order, then item order. */
    val unchecked: List<String>,
)

/**
 * Sorts the web pages among the items of [lists] (those [WebPagePlaces.needsCard] takes; map links
 * name a place by themselves) by the answers [places] already holds.
 */
internal suspend fun knownListPages(
    lists: List<OwnList>,
    places: WebPagePlaces,
): KnownListPages {
    val notPlaces = HashSet<String>()
    val unchecked = ArrayList<String>()
    val pages =
        lists
            .flatMap { it.items }
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
    return KnownListPages(notPlaces, unchecked)
}

/**
 * These lists with their items on the web pages [notPlaces] counted as other links (the line "N other
 * links not shown"), as the core counts another app's posts in a collection. A list whose every item
 * is such a page is left out, as the core's scan leaves out a collection of only other links, unless
 * it is in [edited]: as after the core's edit, a list the user just took the last place out of (or
 * has an edit waiting for) stays until the next scan, so it can be ticked again. A list with no items stays. The items stay in the
 * list on the homeserver: an edit keeps them, as the core does.
 */
internal fun List<OwnList>.withoutPagesOf(
    notPlaces: Set<String>,
    edited: Set<String> = emptySet(),
): List<OwnList> =
    if (notPlaces.isEmpty()) {
        this
    } else {
        mapNotNull { list ->
            val kept = list.items.filterNot { it in notPlaces }
            when {
                kept.size == list.items.size -> list
                kept.isEmpty() && list.uri !in edited -> null
                else -> list.copy(items = kept, otherItems = list.otherItems + list.items.size - kept.size)
            }
        }
    }
