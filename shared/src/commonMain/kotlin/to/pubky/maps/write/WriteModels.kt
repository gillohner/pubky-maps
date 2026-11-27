package to.pubky.maps.write

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import to.pubky.maps.config.StorageChoice
import to.pubky.maps.links.GeoPoint

/** Where one of the user's own things is kept (the "What is stored online" choices). */
@Serializable
enum class Kept {
    /** On this phone only. */
    Phone,

    /** On the homeserver under `/priv`: not public, readable by the homeserver operator. */
    Private,

    /** On the homeserver under `/pub`: anyone can read it. */
    Public,
    ;

    companion object {
        fun of(choice: StorageChoice): Kept =
            when (choice) {
                StorageChoice.PhoneOnly -> Phone
                StorageChoice.HomeserverPrivate -> Private
                StorageChoice.Public -> Public
            }
    }
}

/** One of the user's lists. [uri] is the list's post (`pubky://<me>/{pub|priv}/social/v1/posts/<id>`), or `phone:<id>` on this phone. */
@Serializable
data class OwnList(
    val uri: String,
    val kept: Kept,
    val name: String,
    /** The places in it, as stored. */
    val items: List<String>,
    /** A change to it waits to be sent (or the list itself does). */
    val pending: Boolean = false,
    /** Names of its places, for lists on this phone (homeserver lists name them from the account's labels). */
    val names: Map<String, String> = emptyMap(),
) {
    /** The spec's cap: a list holds at most this many places. */
    val full: Boolean get() = items.size >= MAX_LIST_ITEMS

    companion object {
        const val MAX_LIST_ITEMS = 100
        const val PHONE_PREFIX = "phone:"
    }
}

/**
 * A write about a place, kept in the outbox until the homeserver took it. Posts, photos and lists
 * are built (their ids minted) when the user makes them and tag and bookmark paths follow from the
 * place, so sending a write again (its answer was lost) writes the same path and changes nothing.
 */
@Serializable
sealed interface WriteOp {
    /** The place the write is about, as the user had it. */
    val place: String

    @Serializable
    @SerialName("tag")
    data class Tag(
        override val place: String,
        val label: String,
    ) : WriteOp

    @Serializable
    @SerialName("untag")
    data class Untag(
        override val place: String,
        val label: String,
    ) : WriteOp

    /** A post built when the user wrote it ([path], [uri], [json] from the core's builder). */
    @Serializable
    @SerialName("post")
    data class Post(
        override val place: String,
        val text: String,
        /** When the user wrote it, epoch ms. */
        val at: Long,
        val path: String,
        val uri: String,
        val json: String,
    ) : WriteOp

    /** A photo post; [file] is the prepared photo (metadata stripped) on this phone until it is sent to [filePath]. */
    @Serializable
    @SerialName("photo")
    data class Photo(
        override val place: String,
        val file: String,
        val caption: String?,
        val at: Long,
        val filePath: String,
        val path: String,
        val uri: String,
        val json: String,
    ) : WriteOp

    @Serializable
    @SerialName("save")
    data class Save(
        override val place: String,
    ) : WriteOp

    @Serializable
    @SerialName("unsave")
    data class Unsave(
        override val place: String,
    ) : WriteOp

    /** A new list holding [place], built when the user made it. */
    @Serializable
    @SerialName("list_create")
    data class CreateList(
        override val place: String,
        val name: String,
        val private: Boolean,
        val path: String,
        val uri: String,
        val json: String,
    ) : WriteOp

    @Serializable
    @SerialName("list_edit")
    data class EditList(
        override val place: String,
        val list: String,
        val remove: Boolean,
    ) : WriteOp
}

/** A write in the outbox, in the order it is sent. */
data class QueuedOp(
    val seq: Long,
    val op: WriteOp,
)

/**
 * What a write is about: the place's URI as the user had it, its name for lists of the user's own
 * places, and where it is, for the pin of a saved place on the map (null when the page does not know).
 * [pointPage]: the page is of a point of its own (a dropped pin, typed coordinates), so a save or list
 * keeps it as one ([OwnData.pointPlaces]).
 */
data class WriteTarget(
    val uri: String,
    val name: String?,
    val point: GeoPoint? = null,
    val pointPage: Boolean = false,
    /** What and where the place is ("Café · Napfgasse 4, 8001 Zürich"), kept for the line under its name on the Saved page. */
    val summary: String? = null,
    /** [summary] was taken while Pinakes could not be reached and no card was kept ([KeptSummary.partial]). */
    val summaryPartial: Boolean = false,
)

/** What the user wanted to do when the app asked them to sign in first. */
enum class WriteIntent { Save, Tag, Post, Photo, AddToList }

/** A write that waits for the user to sign in. */
data class SignInFirst(
    val target: WriteTarget,
    val intent: WriteIntent,
)

/** A photo ready to post: re-encoded on the phone, so it carries no metadata except, when [keptLocation], its GPS spot. */
class PreparedPhoto(
    val bytes: ByteArray,
    val mimeType: String,
    val width: Int,
    val height: Int,
    /** The photo had a GPS spot and it was kept (the user's Photo locations choice is Public). */
    val keptLocation: Boolean,
    /** The photo had a GPS spot and it was removed. */
    val removedLocation: Boolean,
)
