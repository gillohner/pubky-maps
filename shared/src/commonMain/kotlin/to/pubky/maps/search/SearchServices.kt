package to.pubky.maps.search

/** Search as the app runs it: one controller and the recent searches, for the life of the process. */
class SearchServices(
    val controller: SearchController,
    val recents: RecentSearchStore,
)
