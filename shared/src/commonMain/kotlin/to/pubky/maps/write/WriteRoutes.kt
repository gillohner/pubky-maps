package to.pubky.maps.write

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import to.pubky.maps.config.AppSettings
import to.pubky.maps.config.DataKind
import to.pubky.maps.config.StorageChoice
import to.pubky.maps.map.savedPointPlace
import to.pubky.maps.place.PlacePageController
import to.pubky.maps.place.PlacePageState
import to.pubky.maps.place.PlaceRef

/** The user's writes as the app's screens use them; [photos] is null where photos cannot be picked. */
class WriteUi(
    val controller: WriteController,
    val photos: PhotoInput?,
)

/**
 * Shows [writes]' lines in [host] while the screen is on: a new line replaces the one showing, and its
 * action (Undo, Try now) runs when tapped.
 */
@Composable
fun WriteMessages(
    writes: WriteController,
    host: SnackbarHostState,
) {
    LaunchedEffect(writes, host) {
        writes.messages.collect { message -> launch { host.showWriteMessage(message) } }
    }
}

/** Shows [message], replacing the snackbar on screen instead of queueing behind it. */
suspend fun SnackbarHostState.showWriteMessage(message: WriteMessage) {
    currentSnackbarData?.dismiss()
    val result =
        showSnackbar(
            message = message.text,
            actionLabel = message.actionLabel,
            duration = SnackbarDuration.Long,
        )
    if (result == SnackbarResult.ActionPerformed) message.action?.invoke()
}

/** The line the sign-in shows when a write waits for it. */
val WriteIntent.signInReason: String
    get() =
        when (this) {
            WriteIntent.Save -> "Sign in to bookmark this place."
            WriteIntent.Tag -> "Sign in to tag this place."
            WriteIntent.Post -> "Sign in to write about this place."
            WriteIntent.Photo -> "Sign in to add a photo of this place."
            WriteIntent.AddToList -> "Sign in to add this place to a collection."
        }

/** The links of [target]'s place, spelled as the core stores them: every link of the open page when it is the same place. */
private fun links(
    writes: WriteController,
    target: WriteTarget,
    page: PlacePageState?,
): Set<String> {
    val shown = page?.takeIf { it.place.uri == target.uri }
    val links = shown?.let { PlacePageController.placeLinks(it.place, it.card) } ?: listOf(target.uri)
    return links.map(writes::normalize).toSet()
}

/** The tag page on the live [writes]; [page] is the open place page (for its tags and category). */
@Composable
fun TagRoute(
    writes: WriteController,
    target: WriteTarget,
    page: PlacePageState?,
    onClose: () -> Unit,
) {
    val own by writes.own.collectAsStateWithLifecycle()
    val links = remember(target, page) { links(writes, target, page) }
    val mine =
        own
            .forPlace(links)
            .tags
            .orEmpty()
            .distinctBy { it.label }
    var text by rememberSaveable { mutableStateOf("") }
    val snackbars = remember { SnackbarHostState() }
    WriteMessages(writes, snackbars)
    TagPage(
        state =
            TagPageState(
                placeName = target.name,
                mine = mine,
                suggestions = tagSuggestions(page?.takeIf { it.place.uri == target.uri }, mine.map { it.label }.toSet()),
                text = text,
                waiting = own.offline,
                busy = own.busy,
            ),
        onTextChange = { text = it },
        onAdd = { label ->
            writes.tag(target, label)
            text = ""
        },
        onRemove = { label -> writes.untag(target, links, label) },
        onRetry = writes::flush,
        onClose = onClose,
        snackbarHostState = snackbars,
    )
}

/** The post page on the live [writes]. */
@Composable
fun PostRoute(
    writes: WriteController,
    target: WriteTarget,
    onClose: () -> Unit,
) {
    var text by rememberSaveable { mutableStateOf("") }
    PostPage(
        placeName = target.name,
        text = text,
        onTextChange = { text = it },
        onPost = {
            if (canPost(text)) {
                writes.post(target, text)
                onClose()
            }
        },
        onClose = onClose,
    )
}

/** The photo page on the live [writes], picking with [photos]. */
@Composable
fun PhotoRoute(
    writes: WriteController,
    photos: PhotoInput,
    settings: AppSettings,
    target: WriteTarget,
    onClose: () -> Unit,
) {
    val keepLocation = settings.storage(DataKind.PhotoLocations) == StorageChoice.Public
    var state by remember(target) { mutableStateOf(PhotoPageState(placeName = target.name, keepLocation = keepLocation)) }
    val launchers =
        photos.rememberLaunchers { result ->
            state =
                when (result) {
                    PhotoResult.Preparing -> state.copy(preparing = true, error = null)
                    is PhotoResult.Ready -> state.copy(photo = result.photo, preparing = false, error = null)
                    is PhotoResult.Failed -> state.copy(preparing = false, error = result.reason)
                    PhotoResult.Cancelled -> state.copy(preparing = false)
                }
        }
    PhotoPage(
        state = state.copy(canTake = launchers.take != null),
        onChoose = { launchers.choose(keepLocation) },
        onTake = launchers.take?.let { take -> { take(keepLocation) } },
        onAltChange = { state = state.copy(alt = it) },
        onCaptionChange = { state = state.copy(caption = it) },
        onPost = {
            val photo = state.photo
            if (photo != null) {
                writes.photo(target, photo, state.alt, state.caption)
                onClose()
            }
        },
        onClose = onClose,
    )
}

/** The list page on the live [writes]: the user's lists are read again when it opens. */
@Composable
fun ListRoute(
    writes: WriteController,
    settings: AppSettings,
    target: WriteTarget,
    page: PlacePageState?,
    signedIn: Boolean,
    onClose: () -> Unit,
) {
    val own by writes.own.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { writes.refreshLists() }
    val links = remember(target, page) { links(writes, target, page) }
    val choices =
        DataKind.Lists.choices
            .map(Kept::of)
            .filter { signedIn || it == Kept.Phone }
    val preferred = Kept.of(settings.storage(DataKind.Lists))
    var newName by rememberSaveable { mutableStateOf("") }
    var newKept by rememberSaveable { mutableStateOf(if (preferred in choices) preferred else Kept.Phone) }
    val snackbars = remember { SnackbarHostState() }
    WriteMessages(writes, snackbars)
    ListPage(
        state =
            ListPageState(
                placeName = target.name,
                lists =
                    own.lists
                        .filter { signedIn || it.kept == Kept.Phone }
                        .map { ListChoice(it, holds = it.items.any { item -> item in links }) },
                loading = own.listsLoading,
                stale = signedIn && !own.listsLoaded && !own.listsLoading,
                newName = newName,
                newKept = newKept,
                keptChoices = choices,
            ),
        onToggle = { choice ->
            if (choice.holds) writes.removeFromList(target, links, choice.list) else writes.addToList(target, choice.list)
        },
        onNewNameChange = { newName = it },
        onNewKeptChange = { newKept = it },
        onCreate = {
            writes.createList(target, newName, newKept)
            newName = ""
        },
        onClose = onClose,
        snackbarHostState = snackbars,
    )
}

/**
 * The Saved screen on the live [writes]; [onRefresh] reads the account again when it opens. [onOpen]
 * hears the point's page for a place saved from the page of a point of its own ([savedPagePoint]).
 */
@Composable
fun SavedRoute(
    writes: WriteController,
    signedIn: Boolean,
    refreshing: Boolean,
    onRefresh: () -> Unit,
    onOpen: (place: String, point: PlaceRef?) -> Unit,
    onBack: () -> Unit,
) {
    val own by writes.own.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) {
        onRefresh()
        writes.refreshLists()
    }
    SavedScreen(
        state =
            SavedScreenState(
                places =
                    own.saved.map {
                        SavedRow(
                            it.place,
                            it.name ?: own.names[it.place],
                            it.kept,
                            it.pending,
                            it.point,
                            it.summary ?: own.summaries[it.place],
                        )
                    },
                lists = own.lists,
                names = own.names,
                summaries = own.summaries,
                refreshing = refreshing || own.listsLoading,
                signedIn = signedIn,
            ),
        onOpen = { place, name -> onOpen(place, savedPagePoint(place, name, own)) },
        onBack = onBack,
    )
}

/**
 * The page a Saved-page row opens without asking any server: a place saved or listed from the page
 * of a point of its own ([OwnData.pointPlaces]) is that point again ([savedPointPlace]). Null for any
 * other place, which is read as its link is.
 */
internal fun savedPagePoint(
    place: String,
    name: String?,
    own: OwnData,
): PlaceRef? = if (place in own.pointPlaces) savedPointPlace(place, name) else null
