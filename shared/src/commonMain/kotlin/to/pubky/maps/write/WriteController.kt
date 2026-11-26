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
import to.pubky.maps.links.GeoPoint
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
internal const val BUSY_MESSAGE = "Your homeserver is busy. Your changes will be sent a little later."

/** A line for the user, with one action ("Undo", "Try now") when there is one. */
class WriteMessage(
    val text: String,
    val actionLabel: String? = null,
    val action: (() -> Unit)? = null,
) {
    override fun toString(): String = text
}

/** [PhoneStore] flag: the user was told that tags are public. */
internal const val PUBLIC_TAGS_EXPLAINED = "public_tags_explained"

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

    private val mutableMessages = MutableSharedFlow<WriteMessage>(extraBufferCapacity = 8)

    /**
     * One line for the user per write that could not be made, when writes wait for the homeserver,
     * and after a tag or list change (with Undo).
     */
    val messages: SharedFlow<WriteMessage> = mutableMessages.asSharedFlow()

    // Not known until the phone store is read: asking once too often is the safe side.
    private val mutablePublicTagsExplained = MutableStateFlow(false)

    /** The user was told once on this phone that tags are public (before their first one-tap tag). */
    val publicTagsExplained: StateFlow<Boolean> = mutablePublicTagsExplained.asStateFlow()

    private val mutableSignInFirst = MutableStateFlow<SignInFirst?>(null)

    /** A write that waits for the user to sign in; the app shows the sign-in while it is set. */
    val signInFirst: StateFlow<SignInFirst?> = mutableSignInFirst.asStateFlow()

    private val mutableWaiting = MutableStateFlow<Boolean?>(null)

    /**
     * Whether the outbox holds writes still to send (for the signed-in user); null until the
     * controller read the account it starts with and its outbox, so a start never reads as "nothing
     * waits" before the outbox was read. Android sends what waits with the app closed while it is true.
     */
    val waiting: StateFlow<Boolean?> = mutableWaiting.asStateFlow()

    /** The account the controller acts on is known (not [AccountState.Loading]) and its outbox was read. */
    private var accountKnown = false

    private val actions = Channel<suspend () -> Unit>(Channel.UNLIMITED)

    @Volatile
    private var core: PubkyWrites? = null
    private var queue: List<QueuedOp> = emptyList()
    private var confirmed: List<Confirmed> = emptyList()
    private var lists: List<OwnList> = emptyList()
    private var phoneData = PhoneData()
    private var labels: Map<String, String> = emptyMap()
    private var points: Map<String, GeoPoint> = emptyMap()
    private var viewer: String? = null
    private var listsLoaded = false

    /** The lists were read once since the account came online (a failed read is retried only when asked). */
    private var listsTried = false

    /** Lists written while a scan runs, kept over its result. */
    private var listsSinceScan: Map<String, OwnList> = emptyMap()

    /** The outbox entry being sent, until its result is applied. */
    private var inFlight: Long? = null
    private var listsLoading = false
    private var offline = false

    /** The last failure was the homeserver throttling or failing (429, 5xx), not the connection. */
    private var busy = false
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
            mutablePublicTagsExplained.value = io { phone.flag(PUBLIC_TAGS_EXPLAINED) }
            publish()
        }
        // The core spells places for comparing them; it is built once, beside the queue.
        scope.launch {
            // A native library that does not load is an Error; the app goes on without writes.
            val built = attempt { coreWrites() }.getOrNull()
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
        mutableMessages.tryEmit(WriteMessage(message))
    }

    private fun tell(message: WriteMessage) {
        mutableMessages.tryEmit(message)
    }

    /** The user read that tags are public; the explainer does not come again on this phone. */
    fun markPublicTagsExplained() {
        mutablePublicTagsExplained.value = true
        enqueue { io { phone.setFlag(PUBLIC_TAGS_EXPLAINED) } }
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

    /** The waiting write, once: the app continues with it after the sign-in. */
    fun takeSignInFirst(): SignInFirst? = mutableSignInFirst.value.also { mutableSignInFirst.value = null }

    /**
     * The user left the sign-in themselves (Back or the bar's arrow) without signing in: the write that
     * waited for it is dropped. The page merely leaving the screen (the activity recreated while the
     * user approves in Pubky Ring) keeps it.
     */
    fun leftSignIn() {
        if (signedIn == null) mutableSignInFirst.value = null
    }

    /** Tags the place with [label] (a valid label, see [tagLabel]); says so with Undo. */
    fun tag(
        target: WriteTarget,
        label: String,
    ): Unit =
        enqueue {
            if (!add(target, WriteOp.Tag(target.uri, label))) return@enqueue
            send()
            tell(
                WriteMessage("Tagged “$label”. Tags are public.", "Undo") {
                    enqueue {
                        if (add(target, WriteOp.Untag(target.uri, label))) send()
                    }
                },
            )
        }

    /**
     * Removes the user's tag [label] from the place whose links are [links]: from every link they
     * tagged it on (the tag lives on the link the user had then), else from [target]'s.
     */
    fun untag(
        target: WriteTarget,
        links: Set<String>,
        label: String,
    ): Unit =
        enqueue {
            val tagged =
                own.value.tags
                    .filter { it.label == label && it.place in links }
                    .map { it.place }
            val places = tagged.ifEmpty { listOf(target.uri) }.distinct()
            val added = places.map { place -> add(target, WriteOp.Untag(place, label)) }
            if (added.none { it }) return@enqueue
            send()
            tell(
                WriteMessage("Removed your tag “$label”.", "Undo") {
                    enqueue {
                        places.forEach { place -> add(target, WriteOp.Tag(place, label)) }
                        send()
                    }
                },
            )
        }

    /** Posts [text] about the place (a review). */
    fun post(
        target: WriteTarget,
        text: String,
    ) = enqueue {
        val trimmed = text.trim()
        val built = build { core, author -> core.buildPost(author, target.uri, trimmed) } ?: return@enqueue
        add(target, WriteOp.Post(target.uri, trimmed, now(), built.path, built.uri, built.json))
        send()
    }

    /** Posts [photo] of the place with its [alt] text and [caption]. */
    fun photo(
        target: WriteTarget,
        photo: PreparedPhoto,
        alt: String?,
        caption: String?,
    ) = enqueue {
        val shownCaption = caption?.trim()?.ifEmpty { null }
        val built =
            build { core, author ->
                core.buildPhoto(author, target.uri, photo.bytes, photo.mimeType, alt?.trim()?.ifEmpty { null }, shownCaption)
            } ?: return@enqueue
        val file = io { files.write(photo.bytes) }
        add(
            target,
            WriteOp.Photo(
                place = target.uri,
                file = file,
                caption = shownCaption,
                at = now(),
                filePath = built.filePath,
                path = built.post.path,
                uri = built.post.uri,
                json = built.post.json,
            ),
        )
        send()
    }

    /** Saves the place where the user keeps saved places: on this phone, or privately on the homeserver. */
    fun save(target: WriteTarget) =
        enqueue {
            if (settings().storage(DataKind.SavedPlaces) == StorageChoice.PhoneOnly) {
                val saved = PhoneSaved(normalize(target.uri), target.name, now(), target.point)
                io { phone.save(saved) }
                phoneData = phoneData.copy(saved = listOf(saved) + phoneData.saved.filterNot { it.place == saved.place })
                publish()
            } else {
                add(target, WriteOp.Save(target.uri))
                send()
            }
        }

    /**
     * The page of the place whose links are [links] shows it as [target]: a place the user saved or
     * tagged that has no name or no point on this phone (saved on another device, or before the last
     * sign-out) keeps [target]'s, so the Saved and Account pages name it and the map shows its badge.
     * Points are kept for saved places only; a name or point already kept stays; other places keep
     * nothing.
     */
    fun remember(
        target: WriteTarget,
        links: Set<String>,
    ) = enqueue {
        val own = mutableOwn.value
        val mine = own.forPlace(links)
        val name = target.name
        val point = target.point
        var changed = false
        mine.saved.filter { it.kept == Kept.Phone }.forEach { entry ->
            val kept = phoneData.saved.firstOrNull { normalize(it.place) == entry.place } ?: return@forEach
            val filled = kept.copy(name = kept.name ?: name, point = kept.point ?: entry.point ?: point)
            if (filled != kept) {
                io { phone.save(filled) }
                phoneData = phoneData.copy(saved = phoneData.saved.map { if (it == kept) filled else it })
                changed = true
            }
        }
        // Not while signing out: the account's store is being cleared.
        if (viewer != null && signedIn?.user?.publicKey == viewer) {
            val homeSaved = mine.saved.filter { it.kept != Kept.Phone }
            val unnamed =
                homeSaved.filter { it.name == null }.map { it.place } +
                    mine.tags
                        .orEmpty()
                        .map { it.place }
                        .filter { it !in own.names }
            if (name != null) {
                unnamed.distinct().forEach { place ->
                    io { store.putLabel(place, name) }
                    labels = labels + (place to name)
                    changed = true
                }
            }
            if (point != null) {
                homeSaved.filter { it.point == null }.map { it.place }.distinct().forEach { place ->
                    io { store.putPoint(place, point) }
                    points = points + (place to point)
                    changed = true
                }
            }
        }
        if (changed) publish()
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
    ): Unit =
        enqueue {
            // The list as it is now: after "Removed from" a full list has room again for its Undo.
            val current = own.value.lists.firstOrNull { it.uri == list.uri } ?: list
            if (current.full) {
                tell("“${list.name}” already holds ${OwnList.MAX_LIST_ITEMS} places, the most a list can hold.")
                return@enqueue
            }
            if (!editList(target, list, remove = false)) return@enqueue
            tell(WriteMessage("Added to “${list.name}”.", "Undo") { removeFromList(target, setOf(normalize(target.uri)), list) })
        }

    /** Removes the place whose links are [links] from [list]. */
    fun removeFromList(
        target: WriteTarget,
        links: Set<String>,
        list: OwnList,
    ): Unit =
        enqueue {
            val stored = list.items.filter { normalize(it) in links }.ifEmpty { listOf(target.uri) }
            val changed = stored.map { editList(target.copy(uri = it), list, remove = true) }
            if (changed.none { it }) return@enqueue
            tell(WriteMessage("Removed from “${list.name}”.", "Undo") { addToList(target, list) })
        }

    /** False when the change could not be made (signed out, for a homeserver list). */
    private suspend fun editList(
        target: WriteTarget,
        list: OwnList,
        remove: Boolean,
    ): Boolean {
        if (list.kept == Kept.Phone) {
            val place = normalize(target.uri)
            val current = phoneData.lists.firstOrNull { it.uri == list.uri } ?: list
            val items = if (remove) current.items.filterNot { normalize(it) == place } else (current.items + place).distinct()
            val names = if (remove) current.names - place else current.names + listOfNotNull(target.name?.let { place to it })
            val updated = current.copy(items = items, names = names)
            io { phone.putList(updated) }
            phoneData = phoneData.copy(lists = phoneData.lists.filterNot { it.uri == list.uri } + updated)
            publish()
            return true
        }
        if (!add(target, WriteOp.EditList(target.uri, list.uri, remove))) return false
        send()
        return true
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
        if (kept == Kept.Phone) {
            val id = "${now()}-${phoneData.lists.size}"
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
            val private = kept == Kept.Private
            val built = build { core, author -> core.buildList(author, name.trim(), listOf(target.uri), private) } ?: return@enqueue
            add(target, WriteOp.CreateList(target.uri, name.trim(), private, built.path, built.uri, built.json))
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

    /**
     * The core's writes, built on first use on [io]: loading the native library and building the core
     * must never run on the main thread (the controller's scope is the main thread on Android).
     */
    private suspend fun coreWrites(): PubkyWrites = core ?: withContext(io) { writes() }.also { core = it }

    /**
     * Builds an object with the core's spec builders for the signed-in user, or says why it cannot be
     * (signed out, or the builder refused it: too long, not a place) and returns null.
     */
    private suspend fun <T> build(block: (PubkyWrites, String) -> T): T? {
        val author = signedIn?.user?.publicKey
        if (author == null) {
            tell("Sign in to change this.")
            return null
        }
        val result =
            attempt {
                val core = coreWrites()
                block(core, author)
            }
        result.exceptionOrNull()?.let { failure ->
            tell("Couldn't make that: ${(failure as? CoreFailure)?.reason ?: failure.message}")
        }
        return result.getOrNull()
    }

    /** Puts [op] in the outbox (and on screen); false (with a line saying so) when nobody is signed in. */
    private suspend fun add(
        target: WriteTarget,
        op: WriteOp,
    ): Boolean {
        if (signedIn == null) {
            tell("Sign in to change this.")
            return false
        }
        val queued = io { store.enqueue(op) }
        queue = queue + queued
        // By the core's spelling: the homeserver's copy comes back spelled so, not as the link was opened.
        val place = normalize(target.uri)
        val name = target.name
        if (name != null && labels[place] != name) {
            io { store.putLabel(place, name) }
            labels = labels + (place to name)
        }
        val point = target.point
        if (point != null && points[place] != point) {
            io { store.putPoint(place, point) }
            points = points + (place to point)
        }
        publish()
        return true
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
            inFlight = null
            listsLoaded = false
            listsTried = false
            listsSinceScan = emptyMap()
            listsLoading = false
            offline = false
            toldOffline = false
            viewer = user
            if (user != null) {
                queue = io { store.queue() }
                lists = io { store.lists() }
                labels = io { store.labels() }
                points = io { store.points() }
                // Photos of writes that are no longer queued (sent, or dropped by a sign-out) are not needed.
                io { files.deleteAllBut(queue.mapNotNull { (it.op as? WriteOp.Photo)?.file }.toSet()) }
            } else {
                queue = emptyList()
                lists = emptyList()
                labels = emptyMap()
                points = emptyMap()
            }
        }
        // Signed out, the account's cache and the outbox in it are gone (sign-out, or a start with no
        // stored session); the outbox's photos go too. Loading at start is not signed out.
        if (state is AccountState.SignedOut) io { files.deleteAllBut(emptySet()) }
        accountKnown = state != AccountState.Loading
        publish()
        if (canSend()) {
            send()
            if (!listsTried) loadLists()
        }
    }

    private fun canSend(): Boolean = signedIn?.connection == Connection.Online

    // --- Sending ----------------------------------------------------------------------------

    /** Sends the first write of the outbox, then the next; one at a time. */
    private fun send() {
        // A finished send hands its result to the queue, which may run other actions first: until
        // that result is applied the write is still in flight and must not be sent again.
        if (inFlight != null || !canSend()) return
        val head = queue.firstOrNull() ?: return
        val made = generation
        inFlight = head.seq
        sendJob =
            scope.launch {
                val result = attempt { execute(head.op) }
                enqueue { sent(head, result, made) }
            }
    }

    private suspend fun execute(op: WriteOp): Any? {
        val core = coreWrites()
        return when (op) {
            is WriteOp.Tag -> {
                core.tag(op.place, op.label)
            }

            is WriteOp.Untag -> {
                core.untag(op.place, op.label)
            }

            is WriteOp.Post -> {
                core.put(op.path, op.json)
                op.uri
            }

            // The file first, then the post that attaches it.
            is WriteOp.Photo -> {
                val bytes = io { files.read(op.file) } ?: throw CoreFailure.Other("the photo is no longer on this phone")
                core.putFile(op.filePath, bytes)
                core.put(op.path, op.json)
                op.uri
            }

            is WriteOp.Save -> {
                core.save(op.place)
            }

            is WriteOp.Unsave -> {
                core.unsave(op.place)
            }

            is WriteOp.CreateList -> {
                core.put(op.path, op.json)
                OwnList(op.uri, if (op.private) Kept.Private else Kept.Public, op.name, listOf(normalize(op.place)))
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
        // Signed out or another account since: the outbox it belonged to is gone (and a new send of
        // that account may be in flight).
        if (made != generation) return
        sendJob = null
        inFlight = null
        if (queue.firstOrNull()?.seq != head.seq) return
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
                busy = failure.busy
                // Said once per wait, with a way to try again now; a busy homeserver is not "offline".
                if (!toldOffline) tell(WriteMessage(if (failure.busy) BUSY_MESSAGE else OFFLINE_MESSAGE, "Try now") { flush() })
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
        (head.op as? WriteOp.Photo)?.let { op ->
            if (queue.none { (it.op as? WriteOp.Photo)?.file == op.file }) io { files.delete(op.file) }
        }
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
                    if (listsJob?.isActive == true) listsSinceScan = listsSinceScan + (list.uri to list)
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
        listsTried = true
        listsLoading = true
        listsSinceScan = emptyMap()
        publish()
        listsJob =
            scope.launch {
                val core = attempt { coreWrites() }.getOrNull()
                // Each root on its own: a homeserver refusing /priv (alpha) still lists the public ones.
                val public = core?.let { c -> attempt { c.lists(private = false, skip = io { store.otherPosts(false) }) } }
                val private = core?.let { c -> attempt { c.lists(private = true, skip = io { store.otherPosts(true) }) } }
                enqueue { listsLoaded(public, private, made) }
            }
    }

    private suspend fun listsLoaded(
        public: Result<ListScan>?,
        private: Result<ListScan>?,
        made: Int,
    ) {
        if (made != generation) return
        listsJob = null
        listsLoading = false
        val scans = listOf(false to public?.getOrNull(), true to private?.getOrNull())
        scans.forEach { (isPrivate, scan) ->
            if (scan == null) return@forEach
            // A list written while the scan ran is newer than what the scan read.
            val written = listsSinceScan.values.filter { (it.kept == Kept.Private) == isPrivate }
            // A list whose folder could not be read now stays as last known.
            val unread =
                lists.filter { known ->
                    (known.kept == Kept.Private) == isPrivate && listAddress(known.uri)?.second in scan.unreadPostIds
                }
            val found = scan.lists + unread.filterNot { kept -> scan.lists.any { it.uri == kept.uri } }
            val merged = found.filterNot { list -> written.any { it.uri == list.uri } } + written
            io {
                store.replaceLists(isPrivate, merged)
                store.addOtherPosts(isPrivate, scan.otherPostIds)
            }
            lists = lists.filterNot { (it.kept == Kept.Private) == isPrivate } + merged
        }
        // A root that could not be read, or posts that could not be, keep their lists as last read; the
        // page says they may be out of date.
        listsLoaded = scans.all { (_, scan) -> scan != null && scan.unreadPostIds.isEmpty() }
        publish()
    }

    // --- State ------------------------------------------------------------------------------

    /** [place] spelled as the core stores places, to compare it with the user's own data ([OwnData.forPlace]). */
    fun normalize(place: String): String = core?.normalize(place) ?: place

    private fun publish() {
        mutableWaiting.value = if (accountKnown) viewer != null && queue.isNotEmpty() else null
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
                points = points,
                normalize = ::normalize,
            ).copy(
                listsLoaded = listsLoaded,
                listsLoading = listsLoading,
                offline = offline && queue.isNotEmpty(),
                busy =
                    busy && offline && queue.isNotEmpty(),
            )
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
            // Also an Error: a Rust library that did not load (UnsatisfiedLinkError) must not crash the app.
            @Suppress("TooGenericExceptionCaught") e: Throwable,
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
