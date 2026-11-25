package to.pubky.maps.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ServerUrlTest {
    private fun valid(input: String): ServerUrl.Valid = assertIs<ServerUrl.Valid>(ServerUrl.parse(input), input)

    private fun invalid(input: String): String = assertIs<ServerUrl.Invalid>(ServerUrl.parse(input), input).reason

    @Test
    fun httpsAddressesAreAccepted() {
        assertEquals("https://photon.komoot.io", valid("https://photon.komoot.io").url)
        assertEquals("https://tiles.example/styles/a.json?key=1", valid("https://tiles.example/styles/a.json?key=1").url)
        assertEquals("https://relay.example:8443/inbox", valid("https://relay.example:8443/inbox").url)
    }

    @Test
    fun schemeAndHostAreLowerCasedAndTheTextTrimmed() {
        val parsed = valid("  HTTPS://Tiles.Example.ORG/Styles/Liberty  ")

        assertEquals("https://tiles.example.org/Styles/Liberty", parsed.url)
        assertEquals("tiles.example.org", parsed.host)
        assertEquals("https://tiles.example.org", parsed.origin)
    }

    @Test
    fun aLoneTrailingSlashIsDropped() {
        assertEquals("https://nexus.example", valid("https://nexus.example/").url)
        assertEquals("https://nexus.example/v1/", valid("https://nexus.example/v1/").url)
    }

    @Test
    fun plainHttpWorksOnlyForLocalhost() {
        assertEquals("http://localhost:8080", valid("http://localhost:8080").url)
        assertEquals("http://127.0.0.1:6287/style.json", valid("http://127.0.0.1:6287/style.json").url)
        assertTrue(invalid("http://tiles.example").startsWith("Use https://"))
        assertTrue(invalid("http://10.0.2.2:8080").startsWith("Use https://"))
        assertTrue(invalid("http://192.168.1.10").startsWith("Use https://"))
    }

    @Test
    fun otherSchemesAndMissingSchemesAreRefused() {
        assertEquals("Only https:// addresses work.", invalid("ftp://files.example"))
        assertEquals("Only https:// addresses work.", invalid("file:///sdcard/style.json"))
        assertEquals("Start the address with https://", invalid("tiles.example/style.json"))
        assertEquals("Start the address with https://", invalid("geo:47.37,8.54"))
    }

    @Test
    fun badAddressesSayWhy() {
        assertEquals("Enter the server's address.", invalid("   "))
        assertEquals("An address has no spaces.", invalid("https://tiles example"))
        assertEquals("Leave the user name and password out of the address.", invalid("https://user:secret@tiles.example"))
        assertEquals("Remove the part from # on.", invalid("https://tiles.example/#map"))
        assertTrue(invalid("https://tiles.example:0").startsWith("The port"))
        assertTrue(invalid("https://tiles.example:99999").startsWith("The port"))
        assertTrue(invalid("https://tiles.example:http").startsWith("The port"))
        assertTrue(invalid("https://").endsWith("is not a server name."))
        assertTrue(invalid("https://-bad-.example").endsWith("is not a server name."))
        assertTrue(invalid("https://tiles..example").endsWith("is not a server name."))
        assertTrue(invalid("https://tiles_example.org").endsWith("is not a server name."))
        assertTrue(invalid("https://kärten.example").endsWith("is not a server name."))
        assertEquals("The address is too long.", invalid("https://tiles.example/" + "a".repeat(ServerUrl.MAX_LENGTH)))
    }

    @Test
    fun theDefaultPortIsDroppedAndOnlyDigitsMakeAPort() {
        assertEquals("https://a.example/x", valid("https://a.example:443/x").url)
        assertEquals("http://localhost", valid("http://localhost:80").url)
        assertEquals("https://a.example:8443", valid("https://a.example:8443").url)
        assertEquals("https://a.example", valid("https://a.example:0443").url)
        assertTrue(invalid("https://a.example:+443").startsWith("The port"))
        assertTrue(invalid("https://a.example:").startsWith("The port"))
    }

    @Test
    fun controlCharactersAreRefused() {
        assertEquals("The address holds a character that cannot be typed.", invalid("https://a.example/\u0000x"))
        assertEquals("The address holds a character that cannot be typed.", invalid("https://a.example/\u007f"))
    }

    @Test
    fun ipAddressesAreHosts() {
        assertEquals("203.0.113.5", valid("https://203.0.113.5/style.json").host)
        assertEquals("[2001:db8::1]", valid("https://[2001:db8::1]:8443/style.json").host)
        assertEquals("[2001:db8::1]", valid("https://[2001:db8::1]").host)
    }

    @Test
    fun hostOfAnInvalidAddressIsNull() {
        assertEquals("pkarr.pubky.app", ServerUrl.hostOf("https://pkarr.pubky.app"))
        assertNull(ServerUrl.hostOf("not an address"))
    }
}
