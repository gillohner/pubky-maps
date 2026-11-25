package to.pubky.maps.config

/**
 * A server address as typed by the user, checked. Only `https://` addresses are accepted, except
 * `http://localhost` and `http://127.0.0.1`, which reach a server on the phone itself (or, through
 * `adb reverse`, on a developer's computer) and never leave the device.
 */
sealed interface ServerUrl {
    /**
     * A usable address: [url] with the scheme and host in lower case, [host] without the port, and
     * [origin] the scheme, host and port.
     */
    data class Valid(
        val url: String,
        val host: String,
        val origin: String,
    ) : ServerUrl

    /** Not usable; [reason] says why in one line for the user. */
    data class Invalid(
        val reason: String,
    ) : ServerUrl

    companion object {
        const val MAX_LENGTH = 2000
        private const val MAX_HOST_LENGTH = 253
        private const val MAX_PORT = 65535

        private val loopbackHosts = setOf("localhost", "127.0.0.1")
        private val schemeAndRest = Regex("^([A-Za-z][A-Za-z0-9+.-]*)://(.*)$")
        private val hostLabel = Regex("^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$")
        private val ipv6 = Regex("^\\[[0-9a-f:.]+]$")

        fun parse(input: String): ServerUrl {
            val text = input.trim()
            val match = schemeAndRest.matchEntire(text)
            val scheme = match?.groupValues?.get(1)?.lowercase()
            val rest = match?.groupValues?.get(2).orEmpty()
            val authority = rest.substringBefore('/').substringBefore('?')
            val hostAndPort = splitPort(authority.lowercase())
            val host = hostAndPort?.first.orEmpty()
            return when {
                text.isEmpty() -> {
                    Invalid("Enter the server's address.")
                }

                text.length > MAX_LENGTH -> {
                    Invalid("The address is too long.")
                }

                text.any { it.isWhitespace() } -> {
                    Invalid("An address has no spaces.")
                }

                match == null -> {
                    Invalid("Start the address with https://")
                }

                scheme != "https" && scheme != "http" -> {
                    Invalid("Only https:// addresses work.")
                }

                '#' in rest -> {
                    Invalid("Remove the part from # on.")
                }

                '@' in authority -> {
                    Invalid("Leave the user name and password out of the address.")
                }

                hostAndPort == null -> {
                    Invalid("The port after : must be a number from 1 to $MAX_PORT.")
                }

                !isValidHost(host) -> {
                    Invalid("\"$authority\" is not a server name.")
                }

                scheme == "http" && host !in loopbackHosts -> {
                    Invalid("Use https://. Plain http:// works only for localhost and 127.0.0.1.")
                }

                else -> {
                    val origin = "$scheme://${authority.lowercase()}"
                    val pathAndQuery = rest.removePrefix(authority)
                    // A path of just "/" is dropped: https://a.example/ and https://a.example are one server.
                    Valid(url = if (pathAndQuery == "/") origin else origin + pathAndQuery, host = host, origin = origin)
                }
            }
        }

        /** The host of [url] in lower case, or null when [url] is not a valid server address. */
        fun hostOf(url: String): String? = (parse(url) as? Valid)?.host

        /** Host and port of an authority; null when a port is given but is not 1..65535. */
        private fun splitPort(authority: String): Pair<String, Int?>? {
            val portSeparator = authority.lastIndexOf(':')
            if (portSeparator < 0 || authority.endsWith("]")) return authority to null
            val port = authority.substring(portSeparator + 1).toIntOrNull()
            if (port == null || port !in 1..MAX_PORT) return null
            return authority.substring(0, portSeparator) to port
        }

        private fun isValidHost(host: String): Boolean {
            if (host.isEmpty() || host.length > MAX_HOST_LENGTH) return false
            if (host.startsWith("[")) return ipv6.matches(host)
            return host.split('.').all { hostLabel.matches(it) }
        }
    }
}
