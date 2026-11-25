package to.pubky.maps.write

import kotlinx.coroutines.CompletableDeferred
import to.pubky.maps.cache.CachedBookmark
import to.pubky.maps.cache.CachedTag

/** A [PubkyWrites] that records what it was asked and answers as the test says. */
class FakePubkyWrites : PubkyWrites {
    /** Every call, as `name(arguments)`. */
    val calls = mutableListOf<String>()

    /** Failures for the next calls, in order; a null entry lets that call through. */
    val failures = ArrayDeque<Throwable?>()

    /** When set, every call waits for it (a slow homeserver). */
    var gate: CompletableDeferred<Unit>? = null

    val photos = mutableListOf<ByteArray>()
    var scans: Map<Boolean, ListScan> = mapOf(true to ListScan(emptyList(), emptyList()), false to ListScan(emptyList(), emptyList()))
    val skipped = mutableListOf<List<String>>()
    private var posts = 0

    private suspend fun call(name: String) {
        calls += name
        gate?.await()
        failures.removeFirstOrNull()?.let { throw it }
    }

    override suspend fun tag(
        place: String,
        label: String,
    ): CachedTag {
        call("tag($place, $label)")
        return CachedTag("/pub/social/v1/tags/${normalize(place).hashCode()}-$label.json", normalize(place), label, 1)
    }

    override suspend fun untag(
        place: String,
        label: String,
    ) = call("untag($place, $label)")

    override suspend fun post(
        place: String,
        text: String,
    ): String {
        call("post($place, $text)")
        return "pubky://me/pub/social/v1/posts/P${++posts}"
    }

    override suspend fun postPhoto(
        place: String,
        image: ByteArray,
        mimeType: String,
        alt: String?,
        caption: String?,
    ): String {
        call("photo($place, $mimeType, $alt, $caption)")
        photos += image
        return "pubky://me/pub/social/v1/posts/I${++posts}"
    }

    override suspend fun createList(
        name: String,
        places: List<String>,
        private: Boolean,
    ): OwnList {
        call("createList($name, $places, private=$private)")
        val root = if (private) "priv" else "pub"
        return OwnList("pubky://me/$root/social/v1/posts/L$name", if (private) Kept.Private else Kept.Public, name, places.map(::normalize))
    }

    override suspend fun editList(
        listUri: String,
        place: String,
        remove: Boolean,
    ): OwnList {
        call("editList($listUri, $place, remove=$remove)")
        val list = scans.values.flatMap { it.lists }.first { it.uri == listUri }
        val items = if (remove) list.items - normalize(place) else list.items + normalize(place)
        return list.copy(items = items)
    }

    override suspend fun lists(
        private: Boolean,
        skip: List<String>,
    ): ListScan {
        call("lists(private=$private)")
        skipped += skip
        return scans.getValue(private)
    }

    override suspend fun save(place: String): CachedBookmark {
        call("save($place)")
        return CachedBookmark("/priv/social/v1/bookmarks/${normalize(place).hashCode()}.json", normalize(place))
    }

    override suspend fun unsave(place: String) = call("unsave($place)")

    /** Like the core: osm.org links become www.openstreetmap.org links. */
    override fun normalize(place: String): String = place.replace("https://osm.org/", "https://www.openstreetmap.org/")
}

class MemoryWriteStore : WriteStore {
    val outbox = mutableListOf<QueuedOp>()
    private var seq = 0L
    var stored = listOf<OwnList>()
    val others = mutableMapOf<Boolean, MutableSet<String>>()
    val names = mutableMapOf<String, String>()

    override fun queue(): List<QueuedOp> = outbox.toList()

    override fun enqueue(op: WriteOp): QueuedOp = QueuedOp(++seq, op).also { outbox += it }

    override fun remove(seq: Long) {
        outbox.removeAll { it.seq == seq }
    }

    override fun lists(): List<OwnList> = stored

    override fun replaceLists(
        private: Boolean,
        lists: List<OwnList>,
    ) {
        stored = stored.filterNot { (it.kept == Kept.Private) == private } + lists
    }

    override fun putList(list: OwnList) {
        stored = stored.filterNot { it.uri == list.uri } + list
    }

    override fun otherPosts(private: Boolean): List<String> = others[private].orEmpty().toList()

    override fun addOtherPosts(
        private: Boolean,
        ids: List<String>,
    ) {
        others.getOrPut(private) { mutableSetOf() } += ids
    }

    override fun labels(): Map<String, String> = names.toMap()

    override fun putLabel(
        place: String,
        name: String,
    ) {
        names[place] = name
    }
}

class MemoryPhoneStore : PhoneStore {
    var data = PhoneData()

    override fun read(): PhoneData = data

    override fun save(saved: PhoneSaved) {
        data = data.copy(saved = data.saved.filterNot { it.place == saved.place } + saved)
    }

    override fun unsave(place: String) {
        data = data.copy(saved = data.saved.filterNot { it.place == place })
    }

    override fun putList(list: OwnList) {
        data = data.copy(lists = data.lists.filterNot { it.uri == list.uri } + list)
    }
}

class MemoryOutboxFiles : OutboxFiles {
    val files = mutableMapOf<String, ByteArray>()
    private var n = 0

    override fun write(bytes: ByteArray): String = "/outbox/${++n}.photo".also { files[it] = bytes }

    override fun read(path: String): ByteArray? = files[path]

    override fun delete(path: String) {
        files.remove(path)
    }

    override fun deleteAllBut(keep: Set<String>) {
        files.keys.retainAll(keep)
    }
}
