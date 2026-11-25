package to.pubky.maps.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import to.pubky.maps.config.ServerUrl
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The network security config lets through exactly the plain http:// servers Settings accepts
 * (localhost and 127.0.0.1) and keeps HTTPS for everything else.
 */
class NetworkSecurityConfigTest {
    private fun parse(path: String): Element =
        DocumentBuilderFactory
            .newInstance()
            .newDocumentBuilder()
            .parse(File(path))
            .documentElement

    private fun Element.children(tag: String): List<Element> {
        val nodes = getElementsByTagName(tag)
        return (0 until nodes.length).map { nodes.item(it) as Element }
    }

    private val config = parse("src/main/res/xml/network_security_config.xml")

    @Test
    fun cleartextIsOffByDefault() {
        val base = config.children("base-config").single()
        assertEquals("false", base.getAttribute("cleartextTrafficPermitted"))
    }

    @Test
    fun cleartextIsAllowedOnlyForTheHostsSettingsAccepts() {
        val cleartext = config.children("domain-config").filter { it.getAttribute("cleartextTrafficPermitted") == "true" }
        val domains = cleartext.flatMap { it.children("domain") }

        assertEquals(ServerUrl.cleartextHosts, domains.map { it.textContent.trim() }.toSet())
        assertTrue(domains.all { it.getAttribute("includeSubdomains") == "false" })
        assertEquals(domains.size, ServerUrl.cleartextHosts.size)
    }

    @Test
    fun theManifestUsesTheConfig() {
        val application = parse("src/main/AndroidManifest.xml").children("application").single()
        assertEquals("@xml/network_security_config", application.getAttribute("android:networkSecurityConfig"))
    }
}
