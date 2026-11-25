package to.pubky.maps.config

/**
 * Hosts run by Synonym (the company behind Pubky) or by the Pubky Maps team. Settings mark servers on
 * them, so users see which parts of the app depend on us; the app must keep working with all of them
 * blocked.
 */
object SynonymHosts {
    private val domains = listOf("pubky.app", "pubky.org", "synonym.to", "coder.riginode.xyz")

    fun isSynonymHost(host: String): Boolean {
        val name = host.lowercase().removeSuffix(".")
        return domains.any { name == it || name.endsWith(".$it") }
    }

    fun isSynonymServer(url: String): Boolean = ServerUrl.hostOf(url)?.let(::isSynonymHost) ?: false
}
