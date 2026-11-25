package to.pubky.maps.write

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import to.pubky.maps.account.AccountState
import to.pubky.maps.account.Connection
import to.pubky.maps.account.CoreFailure
import to.pubky.maps.cache.CachedAccount
import to.pubky.maps.cache.CachedBookmark
import to.pubky.maps.cache.CachedTag
import to.pubky.maps.config.AppSettings
import to.pubky.maps.config.DataKind
import to.pubky.maps.config.StorageChoice
import kotlin.concurrent.Volatile
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Photos waiting in the outbox, as files on this phone (in the no-backup folder on Android). */
interface OutboxFiles {
    /** Stores [bytes]; returns the file's path. */
    fun write(bytes: ByteArray): String

    /** The bytes at [path], null when the file is gone. */
    fun read(path: String): ByteArray?

    fun delete(path: String)

    /** Deletes every file not in [keep]. */
    fun deleteAllBut(keep: Set<String>)
}

internal const val OFFLINE_MESSAGE = "You're offline. Your changes will be sent when you're back online."
internal const val SESSION_ENDED_MESSAGE = "Your sign-in has ended, so the change was not sent. Please sign in again."

/**
 * The user's writes about places: tags, posts, photos, lists and saved places.
 *
 * Every write shows at once ([own]) and goes into an outbox on this phone, which is sent in order
 * while the user is signed in and their homeserver answers. A write that fails for lack of a
 * connection stays and is retried ([flush], and on a timer); one the homeserver refuses is dropped,
 * which takes it off the screen again, with a [messages] line saying so. Where the user keeps a kind
 * of data on this phone only ("What is stored online"), it never goes to the outbox.
 *
 * All changes run in order on one queue; network calls run beside it, one write at a time.
 */
class WriteController(
    private val writes: suspend () -> PubkyWrites,
    private val store: WriteStore,
    private val phone: PhoneStore,
    private val files: OutboxFiles,
    private val account: StateFlow<AccountState>,
    /** Applies a change to the account's cached data (tags, saved places) once the homeserver took it. */
    private val updateAccount: ((CachedAccount) -> CachedAccount) -> Unit,
    /** Reads the account again (a write found the session ended). */
    private val refreshAccount: () -> Unit,
    private val settings: () -> AppSettings,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    private val now: () -> Long,
    /** Waits between attempts while offline; the last one repeats. */
    private val retryDelays: List<Duration> = listOf(15.seconds, 30.seconds, 60.seconds, 120.seconds, 300.seconds),
) {
    private val mutableOwn = MutableStateFlow(OwnData())

    /** The user's own data with every write made on this phone. */
    val own: StateFlow<OwnData> = mutableOwn.asStateFlow()

    private val mutableMessages = MutableSharedFlow<String>(extraBufferCapacity = 8)

    /** One line for the user per write that could not be made, and when writes wait for a connection. */
    val messages: SharedFlow<String> = mutableMessages.asSharedFlow()

    private val mutableSignInFirst = MutableStateFlow<SignInFirst?>(null)

    /** A write that waits for the user to sign in; the app shows the sign-in while it is set. */
    val signInFirst: StateFlow<SignInFirst?> = mutableSignInFirst.asStateFlow()

    private val actions = Channel<suspend () -> Unit>(Channel.UNLIMITED)

    @Volatile
    private var core: PubkyWrites? = null
    private var queue: List<QueuedOp> = emptyList()
    private var confirmed: List<Confirmed> = emptyList()
    private var lists: List<OwnList> = emptyList()
    private var phoneData = PhoneData()
    private var labels: Map<String, String> = emptyMap()
    private var viewer: String? = null
    private var listsLoaded = false
    private var listsLoading = false
    private var offline = false
    private var toldOffline = false
    private var retries = 0
    private var sendJob: Job? = null
    private var retryJob: Job? = null
    private var listsJob: Job? = null

    /** Counts sign-ins and sign-outs: a result is applied only for the account it was made for. */
    private var generation = 0

    init {
        scope.launch {
            for (action in actions) {
                try {
                    action()
                } catch (e: CancellationException) {
                    throw e
                } catch (
                    // A disk failure must not stop the queue or the app.
                    @Suppress("TooGenericExceptionCaught") e: Exception,
                ) {
                    tell("Pubky Maps could not store your change on this phone (${e::class.simpleName}).")
                    publish()
                }
            }
        }
        enqueue {
            phoneData = io { phone.read() }
            publish()
        }
        // The core spells places for comparing them; it is built once, beside the queue.
        scope.launch {
            val built = attempt { writes() }.getOrNull()
            enqueue {
                if (core == null) core = built
                publish()
            }
        }
        scope.launch { account.collect { state -> enqueue { accountChanged(state) } } }
    }

    private fun enqueue(action: suspend () -> Unit) {
        actions.trySend(action)
    }

    private suspend fun <T> io(block: () -> T): T = withContext(io) { block() }

    private fun tell(message: String) {
        mutableMessages.tryEmit(message)
    }

    private val signedIn: AccountState.SignedIn?
        get() = (account.value as? AccountState.SignedIn)?.takeUnless { it.signingOut }

    // --- What the user can do ---------------------------------------------------------------

    /** True when [intent] needs the user to sign in first (the data goes to their homeserver). */
    fun needsSignIn(intent: WriteIntent): Boolean {
        if (signedIn != null) return false
        return when (intent) {
            WriteIntent.Save -> settings().storage(DataKind.SavedPlaces) != StorageChoice.PhoneOnly
            WriteIntent.AddToList -> settings().storage(DataKind.Lists) != StorageChoice.PhoneOnly
            WriteIntent.Tag, WriteIntent.Post, WriteIntent.Photo -> true
        }
    }

    /** Asks the user to sign in, then to continue with [intent] on [target]. */
    fun signInFirst(
        target: WriteTarget,
        intent: WriteIntent,
    ) {
        mutableSignInFirst.value = SignInFirst(target, intent)
    }

    /** The waiting write, once: the app continues with it after the sign-in, or drops it when the user went back. */
    fun takeSignInFirst(): SignInFirst? = mutableSignInFirst.value.also { mutableSignInFirst.value = null }

    /** Tags the place with [label] (a valid label, see [tagLabel]). */
    fun tag(
        target: WriteTarget,
        label: String,
    ) = write(target, WriteOp.Tag(target.uri, label))

    /**
     * Removes the user's tag [label] from the place whose links are [links]: from every link they
     * tagged it on (the tag lives on the link the user had then), else from [target]'s.
     */
    fun untag(
        target: WriteTarget,
        links: Set<String>,
        label: String,
    ) = enqueue {
        val tagged =
            own.value.tags
                .filter { it.label == label && it.place in links }
                .map { it.place }
        (tagged.ifEmpty { listOf(target.uri) }).distinct().forEach { place -> add(target, WriteOp.Untag(place, label)) }
        send()
    }

    /** Posts [text] about the place (a review). */
    fun post(
        target: WriteTarget,
        text: String,
    ) = write(target, WriteOp.Post(target.uri, text.trim(), now()))

    /** Posts [photo] of the place with its [alt] text and [caption]. */
    fun photo(
        target: WriteTarget,
        photo: PreparedPhoto,
        alt: String?,
        caption: String?,
    ) = enqueue {
        val file = io { files.write(photo.bytes) }
        add(
            target,
            WriteOp.Photo(
                place = target.uri,
                file = file,
                mimeType = photo.mimeType,
                alt = alt?.trim()?.ifEmpty { null },
                caption = caption?.trim()?.ifEmpty { null },
                at = now(),
            ),
        )
        send()
    }

    /** Saves the place where the user keeps saved places: on this phone, or privately on the homeserver. */
    fun save(target: WriteTarget) =
        enqueue {
            if (settings().storage(DataKind.SavedPlaces) == StorageChoice.PhoneOnly) {
                val saved = PhoneSaved(normalize(target.uri), target.name, now())
                io { phone.save(saved) }
                phoneData = phoneData.copy(saved = listOf(saved) + phoneData.saved.filterNot { it.place == saved.place })
                publish()
            } else {
                add(target, WriteOp.Save(target.uri))
                send()
            }
        }

    /** Removes every saved copy of the place whose links are [links] (on this phone and on the homeserver). */
    fun unsave(
        target: WriteTarget,
        links: Set<String>,
    ) = enqueue {
        val entries = own.value.saved.filter { it.place in links }
        entries.filter { it.kept == Kept.Phone }.forEach { entry ->
            io { phone.unsave(entry.place) }
            phoneData = phoneData.copy(saved = phoneData.saved.filterNot { it.place == entry.place })
        }
        val homeserver = entries.filter { it.kept != Kept.Phone }.map { it.place }
        homeserver.distinct().forEach { add(target, WriteOp.Unsave(it)) }
        publish()
        send()
    }

    /** Adds the place to [list]; a full list (100 places) refuses it. */
    fun addToList(
        target: WriteTarget,
        list: OwnList,
    ) = enqueue {
        if (list.full) {
            tell("“${list.name}” already holds ${OwnList.MAX_LIST_ITEMS} places, the most a list can hold.")
            return@enqueue
        }
        editList(target, list, remove = false)
    }

    /** Removes the place whose links are [links] from [list]. */
    fun removeFromList(
        target: WriteTarget,
        links: Set<String>,
        list: OwnList,
    ) = enqueue {
        val stored = list.items.filter { normalize(it) in links }.ifEmpty { listOf(target.uri) }
        stored.forEach { editList(target.copy(uri = it), list, remove = true) }
    }

    private suspend fun editList(
        target: WriteTarget,
        list: OwnList,
        remove: Boolean,
    ) {
        if (list.kept == Kept.Phone) {
            val place = normalize(target.uri)
            val current = phoneData.lists.firstOrNull { it.uri == list.uri } ?: list
            val items = if (remove) current.items.filterNot { normalize(it) == place } else (current.items + place).distinct()
            val names = if (remove) current.names - place else current.names + listOfNotNull(target.name?.let { place to it })
            val updated = current.copy(items = items, names = names)
            io { phone.putList(updated) }
            phoneData = phoneData.copy(lists = phoneData.lists.filterNot { it.uri == list.uri } + updated)
            publish()
        } else {
            add(target, WriteOp.EditList(target.uri, list.uri, remove))
            send()
        }
    }

    /**
     * Creates a list named [name] holding the place, kept where [kept] says (the user's Lists choice
     * unless they picked another for this list).
     */
    fun createList(
        target: WriteTarget,
        name: String,
        kept: Kept,
    ) = enqueue {
        val id = "${now()}-${(phoneData.lists.size + queue.size)}"
        if (kept == Kept.Phone) {
            val place = normalize(target.uri)
            val list =
                OwnList(
                    uri = OwnList.PHONE_PREFIX + id,
                    kept = Kept.Phone,
                    name = name.trim(),
                    items = listOf(place),
                    names = listOfNotNull(target.name?.let { place to it }).toMap(),
                )
            io { phone.putList(list) }
            phoneData = phoneData.copy(lists = phoneData.lists + list)
            publish()
        } else {
            add(target, WriteOp.CreateList(target.uri, name.trim(), private = kept == Kept.Private, localId = id))
            send()
        }
    }

    /** Reads the user's lists from the homeserver again (the "Add to list" page asks when it opens). */
    fun refreshLists() = enqueue { loadLists() }

    /** Tries to send what waits now (the phone came online, the app came to the front). */
    fun flush() =
        enqueue {
            retries = 0
            retryJob?.cancel()
            retryJob = null
            send()
        }

    private fun write(
        target: WriteTarget,
        op: WriteOp,
    ) = enqueue {
        add(target, op)
        send()
    }

    /** Puts [op] in the outbox (and on screen). */
    private suspend fun add(
        target: WriteTarget,
        op: WriteOp,
    ) {
        if (signedIn == null) {
            tell("Sign in to change this.")
            return
        }
        val queued = io { store.enqueue(op) }
        queue = queue + queued
        val name = target.name
        if (name != null && labels[target.uri] != name) {
            io { store.putLabel(target.uri, name) }
            labels = labels + (target.uri to name)
        }
        publish()
    }

    // --- The account ------------------------------------------------------------------------

    private suspend fun accountChanged(state: AccountState) {
        val user = (state as? AccountState.SignedIn)?.takeUnless { it.signingOut }?.user?.publicKey
        if (user != viewer) {
            generation++
            sendJob?.cancel()
            sendJob = null
            retryJob?.cancel()
            retryJob = null
            listsJob?.cancel()
            listsJob = null
            confirmed = emptyList()
            listsLoaded = false
            listsLoading = false
            offline = false
            toldOffline = false
            viewer = user
            if (user != null) {
                queue = io { store.queue() }
                lists = io { store.lists() }
                labels = io { store.labels() }
                // Photos of writes that are no longer queued (sent, or dropped by a sign-out) are not needed.
                io { files.deleteAllBut(queue.mapNotNull { (it.op as? WriteOp.Photo)?.file }.toSet()) }
            } else {
                queue = emptyList()
                lists = emptyList()
                labels = emptyMap()
                // A sign-out deleted the outbox; its photos go too. (Loading at start is not signed out.)
                if (state is AccountState.SignedOut) io { files.deleteAllBut(emptySet()) }
            }
        }
        publish()
        if (canSend()) {
            send()
            if (!listsLoaded) loadLists()
        }
    }

    private fun canSend(): Boolean = signedIn?.connection == Connection.Online

    // --- Sending ----------------------------------------------------------------------------

    /** Sends the first write of the outbox, then the next; one at a time. */
    private fun send() {
        if (sendJob?.isActive == true || !canSend()) return
        val head = queue.firstOrNull() ?: return
        val made = generation
        sendJob =
            scope.launch {
                val result = attempt { execute(head.op) }
                enqueue { sent(head, result, made) }
            }
    }

    private suspend fun execute(op: WriteOp): Any? {
        val core = core ?: writes().also { core = it }
        return when (op) {
            is WriteOp.Tag -> {
                core.tag(op.place, op.label)
            }

            is WriteOp.Untag -> {
                core.untag(op.place, op.label)
            }

            is WriteOp.Post -> {
                core.post(op.place, op.text)
            }

            is WriteOp.Photo -> {
                val bytes = io { files.read(op.file) } ?: throw CoreFailure.Other("the photo is no longer on this phone")
                core.postPhoto(op.place, bytes, op.mimeType, op.alt, op.caption)
            }

            is WriteOp.Save -> {
                core.save(op.place)
            }

            is WriteOp.Unsave -> {
                core.unsave(op.place)
            }

            is WriteOp.CreateList -> {
                core.createList(op.name, listOf(op.place), op.private)
            }

            is WriteOp.EditList -> {
                core.editList(op.list, op.place, op.remove)
            }
        }
    }

    private suspend fun sent(
        head: QueuedOp,
        result: Result<Any?>,
        made: Int,
    ) {
        sendJob = null
        // Signed out or another account since: the outbox it belonged to is gone.
        if (made != generation || queue.firstOrNull()?.seq != head.seq) return
        val failure = result.exceptionOrNull()
        when {
            failure == null -> {
                io { store.remove(head.seq) }
                queue = queue.drop(1)
                confirm(head.op, result.getOrNull())
                offline = false
                toldOffline = false
                retries = 0
                publish()
                send()
            }

            failure is CoreFailure.Network -> {
                offline = true
                if (!toldOffline) tell(OFFLINE_MESSAGE)
                toldOffline = true
                publish()
                retryLater()
            }

            failure is CoreFailure.Auth -> {
                drop(head)
                tell(SESSION_ENDED_MESSAGE)
                refreshAccount()
            }

            else -> {
                drop(head)
                tell(failedText(head.op, (failure as? CoreFailure)?.reason ?: failure.message))
                send()
            }
        }
    }

    /** Takes a write the homeserver refused out of the outbox, and so off the screen. */
    private suspend fun drop(head: QueuedOp) {
        io { store.remove(head.seq) }
        queue = queue.drop(1)
        (head.op as? WriteOp.Photo)?.let { op -> io { files.delete(op.file) } }
        publish()
    }

    private suspend fun confirm(
        op: WriteOp,
        result: Any?,
    ) {
        val at = now()
        when (op) {
            is WriteOp.Tag -> {
                val tag = result as? CachedTag
                if (tag != null) updateAccount { cached -> cached.copy(tags = listOf(tag) + cached.tags.filterNot { it.path == tag.path }) }
            }

            is WriteOp.Untag -> {
                val place = normalize(op.place)
                updateAccount { cached ->
                    cached.copy(tags = cached.tags.filterNot { normalize(it.place) == place && it.label == op.label })
                }
            }

            is WriteOp.Save -> {
                val bookmark = result as? CachedBookmark
                if (bookmark != null) {
                    updateAccount { cached -> cached.copy(bookmarks = cached.bookmarks.filterNot { it.path == bookmark.path } + bookmark) }
                }
            }

            is WriteOp.Unsave -> {
                val place = normalize(op.place)
                updateAccount { cached -> cached.copy(bookmarks = cached.bookmarks.filterNot { normalize(it.place) == place }) }
            }

            is WriteOp.CreateList, is WriteOp.EditList -> {
                val list = result as? OwnList
                if (list != null) {
                    io { store.putList(list) }
                    lists = lists.filterNot { it.uri == list.uri } + list
                }
            }

            is WriteOp.Post, is WriteOp.Photo -> {}
        }
        confirmed = confirmed + Confirmed(op, at, uri = result as? String)
    }

    private fun retryLater() {
        if (retryJob?.isActive == true) return
        val wait = retryDelays[minOf(retries, retryDelays.lastIndex)]
        retries++
        retryJob =
            scope.launch {
                delay(wait)
                enqueue {
                    retryJob = null
                    send()
                }
            }
    }

    // --- Lists ------------------------------------------------------------------------------

    private fun loadLists() {
        if (listsJob?.isActive == true || !canSend()) return
        val made = generation
        listsLoading = true
        publish()
        listsJob =
            scope.launch {
                val result =
                    attempt {
                        val core = core ?: writes().also { core = it }
                        val public = core.lists(private = false, skip = io { store.otherPosts(false) })
                        val private = core.lists(private = true, skip = io { store.otherPosts(true) })
                        public to private
                    }
                enqueue { listsLoaded(result, made) }
            }
    }

    private suspend fun listsLoaded(
        result: Result<Pair<ListScan, ListScan>>,
        made: Int,
    ) {
        listsJob = null
        if (made != generation) return
        listsLoading = false
        result.onSuccess { (public, private) ->
            io {
                store.replaceLists(false, public.lists)
                store.replaceLists(true, private.lists)
                store.addOtherPosts(false, public.otherPostIds)
                store.addOtherPosts(true, private.otherPostIds)
            }
            lists = public.lists + private.lists
            listsLoaded = true
        }
        // A failed read keeps the lists as last read; the page says they may be out of date.
        publish()
    }

    // --- State ------------------------------------------------------------------------------

    /** [place] spelled as the core stores places, to compare it with the user's own data ([OwnData.forPlace]). */
    fun normalize(place: String): String = core?.normalize(place) ?: place

    private fun publish() {
        val cached = signedIn?.cached
        mutableOwn.value =
            ownData(
                viewer = viewer,
                cached = cached,
                lists = lists,
                phone = phoneData,
                queue = queue,
                confirmed = confirmed,
                labels = labels,
                normalize = ::normalize,
            ).copy(listsLoaded = listsLoaded, listsLoading = listsLoading, offline = offline && queue.isNotEmpty())
    }

    /** [block] with every failure as a [CoreFailure] (cancellation stays cancellation). */
    private suspend fun <T> attempt(block: suspend () -> T): Result<T> =
        try {
            Result.success(block())
        } catch (e: CancellationException) {
            throw e
        } catch (e: CoreFailure) {
            Result.failure(e)
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            Result.failure(CoreFailure.Other(e.message ?: e::class.simpleName ?: "unexpected error"))
        }
}

/** The line shown when the homeserver refused a write. */
internal fun failedText(
    op: WriteOp,
    reason: String?,
): String {
    val what =
        when (op) {
            is WriteOp.Tag -> "add the tag “${op.label}”"
            is WriteOp.Untag -> "remove the tag “${op.label}”"
            is WriteOp.Post -> "post your text"
            is WriteOp.Photo -> "post your photo"
            is WriteOp.Save -> "save the place"
            is WriteOp.Unsave -> "remove the saved place"
            is WriteOp.CreateList -> "create the list “${op.name}”"
            is WriteOp.EditList -> if (op.remove) "remove the place from the list" else "add the place to the list"
        }
    return "Couldn't $what" + (reason?.let { ": $it" } ?: ".")
}
