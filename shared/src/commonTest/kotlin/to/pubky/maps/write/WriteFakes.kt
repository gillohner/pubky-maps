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

    /** Objects built, as `kind(arguments)`: what the core's builders were asked for. */
    val builds = mutableListOf<String>()

    /** When set, reading the lists waits for it. */
    var listGate: CompletableDeferred<Unit>? = null

    /** Thrown when the private lists are read (a homeserver refusing /priv). */
    var privateListsFailure: Throwable? = null
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

    override fun buildPost(
        author: String,
        place: String,
        text: String,
    ): BuiltWrite {
        builds += "post($place, $text)"
        val id = "P${++posts}"
        return BuiltWrite(
            "/pub/social/v1/posts/$id/$id.json",
            "pubky://$author/pub/social/v1/posts/$id",
            """{"kind":"note","parent":"$place"}""",
        )
    }

    override fun buildPhoto(
        author: String,
        place: String,
        image: ByteArray,
        mimeType: String,
        alt: String?,
        caption: String?,
    ): BuiltPhoto {
        builds += "photo($place, $mimeType, $alt, $caption)"
        val id = "I${++posts}"
        return BuiltPhoto(
            filePath = "/pub/social/v1/files/F${image.size}.jpg",
            post =
                BuiltWrite(
                    "/pub/social/v1/posts/$id/$id.json",
                    "pubky://$author/pub/social/v1/posts/$id",
                    """{"kind":"image","alt":"$alt","caption":"$caption"}""",
                ),
        )
    }

    override fun buildList(
        author: String,
        name: String,
        places: List<String>,
        private: Boolean,
    ): BuiltWrite {
        if (name.length > 100) {
            throw to.pubky.maps.account.CoreFailure
                .Other("name: at most 100 characters")
        }
        builds += "list($name, $places, private=$private)"
        val root = if (private) "priv" else "pub"
        return BuiltWrite("/$root/social/v1/posts/L$name/L$name.json", "pubky://$author/$root/social/v1/posts/L$name", "{}")
    }

    override suspend fun put(
        path: String,
        json: String,
    ) = call("put($path)")

    override suspend fun putFile(
        path: String,
        bytes: ByteArray,
    ) {
        call("putFile($path)")
        photos += bytes
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
        listGate?.await()
        if (private) privateListsFailure?.let { throw it }
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
