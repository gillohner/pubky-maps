package to.pubky.maps.links

import kotlinx.coroutines.CancellationException
import to.pubky.maps.place.PlaceRef
import to.pubky.maps.place.toPlaceRef

/** Map links other apps hand to Pubky Maps (Android `VIEW` and `SEND` intents). */
object IncomingLinks {
    const val ACTION_VIEW = "android.intent.action.VIEW"
    const val ACTION_SEND = "android.intent.action.SEND"

    /** The most shared text read; a map link with its name fits many times over. */
    const val MAX_TEXT_LENGTH = 16 * 1024

    /**
     * The text to read a map link from: a `VIEW` intent's [data] URI, or a `SEND` intent's [text] with
     * its [subject] as the first line (share sheets put the place's name there). Null for anything else.
     */
    fun textOf(
        action: String?,
        data: String?,
        text: String?,
        subject: String?,
    ): String? {
        val result =
            when (action) {
                ACTION_VIEW -> {
                    data?.trim()
                }

                ACTION_SEND -> {
                    val body = text?.trim().orEmpty()
                    val title = subject?.trim().orEmpty()
                    when {
                        body.isEmpty() -> null
                        title.isEmpty() || body.startsWith(title) -> body
                        else -> "$title\n$body"
                    }
                }

                else -> {
                    null
                }
            }
        return result?.takeIf { it.isNotEmpty() && it.length <= MAX_TEXT_LENGTH }
    }
}

/** What opening an incoming link came to. */
sealed interface LinkOpening {
    data class Opened(
        val place: PlaceRef,
        val provider: LinkProvider,
    ) : LinkOpening

    /** The text holds no map link Pubky Maps can read. */
    data object NotAMapLink : LinkOpening
}

/** Opens shared text as a place: through the core, with the user's Pinakes servers. */
suspend fun MapLinks.openAsPlace(
    text: String,
    pinakesServers: List<String>,
): LinkOpening =
    try {
        open(text, pinakesServers)?.let { LinkOpening.Opened(it.toPlaceRef(), it.provider) } ?: LinkOpening.NotAMapLink
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // The core fails only for text without a map link (or an unusable server list): either way
        // there is no place to show, and a shared link must never crash the app.
        LinkOpening.NotAMapLink
    }
