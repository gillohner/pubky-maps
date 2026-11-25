package to.pubky.maps.links

/** Map links other apps hand to Pubky Maps (Android `VIEW` and `SEND` intents). */
object IncomingLinks {
    const val ACTION_VIEW = "android.intent.action.VIEW"
    const val ACTION_SEND = "android.intent.action.SEND"

    /**
     * The most shared text read, in UTF-8 bytes: the core's limit (map-links `find_in_text`), so
     * whatever passes here the core reads too. A map link with its name fits many times over.
     */
    const val MAX_TEXT_BYTES = 16 * 1024

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
        return result?.takeIf { it.isNotEmpty() && it.encodeToByteArray().size <= MAX_TEXT_BYTES }
    }
}
