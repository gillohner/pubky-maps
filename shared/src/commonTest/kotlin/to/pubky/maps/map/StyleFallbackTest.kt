package to.pubky.maps.map

import to.pubky.maps.config.MapStyleSource
import to.pubky.maps.config.ServerDefaults
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class StyleFallbackTest {
    private val first = MapStyleSource("First", "https://first.example/style.json", emptyList())
    private val second = MapStyleSource("Second", "https://second.example/style.json", emptyList())
    private val third = MapStyleSource("Third", "https://third.example/style.json", emptyList())

    @Test
    fun startsWithTheFirstStyle() {
        val fallback = StyleFallback(listOf(first, second))

        assertEquals(first, fallback.current)
        assertFalse(fallback.allFailed)
    }

    @Test
    fun aFailedStyleHandsOverToTheNextInOrder() {
        val fallback = StyleFallback(listOf(first, second, third))

        val afterFirst = fallback.failed(first.styleUrl)
        val afterSecond = afterFirst.failed(second.styleUrl)

        assertEquals(second, afterFirst.current)
        assertEquals(third, afterSecond.current)
        assertFalse(afterSecond.allFailed)
    }

    @Test
    fun whenEveryStyleFailedThereIsNoCurrentStyle() {
        val fallback = StyleFallback(listOf(first, second)).failed(first.styleUrl).failed(second.styleUrl)

        assertNull(fallback.current)
        assertTrue(fallback.allFailed)
    }

    @Test
    fun aLateFailureOfAnEarlierStyleIsIgnored() {
        val onSecond = StyleFallback(listOf(first, second, third)).failed(first.styleUrl)

        assertSame(onSecond, onSecond.failed(first.styleUrl))
        assertEquals(second, onSecond.current)
    }

    @Test
    fun aFailureOfAnUnknownStyleIsIgnored() {
        val fallback = StyleFallback(listOf(first))

        assertSame(fallback, fallback.failed("https://other.example/style.json"))
    }

    @Test
    fun aFailureAfterEveryStyleFailedChangesNothing() {
        val allFailed = StyleFallback(listOf(first)).failed(first.styleUrl)

        assertSame(allFailed, allFailed.failed(first.styleUrl))
    }

    @Test
    fun retryStartsAgainFromTheFirstStyle() {
        val retried = StyleFallback(listOf(first, second)).failed(first.styleUrl).failed(second.styleUrl).retry()

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
            val fallback = StyleFallback(ServerDefaults.mapStyles.forTheme(darkTheme))

            assertTrue(fallback.current!!.styleUrl.startsWith("https://tiles.openfreemap.org/"))
            val next = fallback.failed(fallback.current!!.styleUrl).current!!
            assertTrue(next.styleUrl.startsWith("https://tiles.versatiles.org/"))
        }
    }
}
