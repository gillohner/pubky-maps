package to.pubky.maps.map

import to.pubky.maps.config.ServerDefaults
import to.pubky.maps.config.ServerEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class StyleFallbackTest {
    private val first = ServerEntry("First", "https://first.example/style.json", emptyList())
    private val second = ServerEntry("Second", "https://second.example/style.json", emptyList())
    private val third = ServerEntry("Third", "https://third.example/style.json", emptyList())

    @Test
    fun startsWithTheFirstStyle() {
        val fallback = StyleFallback(listOf(first, second))

        assertEquals(first, fallback.current)
        assertFalse(fallback.allFailed)
    }

    @Test
    fun aFailedStyleHandsOverToTheNextInOrder() {
        val fallback = StyleFallback(listOf(first, second, third))

        val afterFirst = fallback.failed(first.url)
        val afterSecond = afterFirst.failed(second.url)

        assertEquals(second, afterFirst.current)
        assertEquals(third, afterSecond.current)
        assertFalse(afterSecond.allFailed)
    }

    @Test
    fun theOtherThemeComesFromTheServerTheMapFellBackTo() {
        val light = ServerDefaults.lists.mapStyles(darkTheme = false)
        val dark = ServerDefaults.lists.mapStyles(darkTheme = true)
        val fellBack = StyleFallback(light).failed(light[0].url)

        assertEquals(dark[0], StyleFallback(light).otherTheme(dark))
        assertEquals(dark[1], fellBack.otherTheme(dark))
        assertEquals(dark[1], StyleFallback(light + first, index = 2).otherTheme(dark))
        assertNull(fellBack.failed(light[1].url).otherTheme(dark))
        assertNull(StyleFallback(light).otherTheme(emptyList()))
    }

    @Test
    fun whenEveryStyleFailedThereIsNoCurrentStyle() {
        val fallback = StyleFallback(listOf(first, second)).failed(first.url).failed(second.url)

        assertNull(fallback.current)
        assertTrue(fallback.allFailed)
    }

    @Test
    fun aLateFailureOfAnEarlierStyleIsIgnored() {
        val onSecond = StyleFallback(listOf(first, second, third)).failed(first.url)

        assertSame(onSecond, onSecond.failed(first.url))
        assertEquals(second, onSecond.current)
    }

    @Test
    fun aFailureOfAnUnknownStyleIsIgnored() {
        val fallback = StyleFallback(listOf(first))

        assertSame(fallback, fallback.failed("https://other.example/style.json"))
    }

    @Test
    fun aFailureAfterEveryStyleFailedChangesNothing() {
        val allFailed = StyleFallback(listOf(first)).failed(first.url)

        assertSame(allFailed, allFailed.failed(first.url))
    }

    @Test
    fun retryStartsAgainFromTheFirstStyle() {
        val retried = StyleFallback(listOf(first, second)).failed(first.url).failed(second.url).retry()

        assertEquals(first, retried.current)
        assertFalse(retried.allFailed)
    }

    @Test
    fun anEmptyListIsRejected() {
        assertFailsWith<IllegalArgumentException> { StyleFallback(emptyList()) }
    }

    @Test
    fun theDefaultsTryOpenFreeMapFirstThenVersaTiles() {
        for (darkTheme in listOf(false, true)) {
            val fallback = StyleFallback(ServerDefaults.lists.mapStyles(darkTheme))

            assertTrue(fallback.current!!.url.startsWith("https://tiles.openfreemap.org/"))
            val next = fallback.failed(fallback.current!!.url).current!!
            assertTrue(next.url.startsWith("https://tiles.versatiles.org/"))
        }
    }
}
