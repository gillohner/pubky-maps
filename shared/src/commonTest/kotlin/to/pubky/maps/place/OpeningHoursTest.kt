package to.pubky.maps.place

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Opening hours as OpenStreetMap has them on the places of the development Pinakes. 2026-11-25 is a Wednesday. */
class OpeningHoursTest {
    private fun schedule(text: String) = assertNotNull(OpeningSchedule.parse(text), text)

    private fun at(
        date: String,
        hour: Int,
        minute: Int = 0,
    ) = LocalDateTime(LocalDate.parse(date), kotlinx.datetime.LocalTime(hour, minute))

    private fun span(
        from: String,
        to: String,
    ): MinuteSpan {
        fun minutes(t: String) = t.substringBefore(':').toInt() * 60 + t.substringAfter(':').toInt()
        return MinuteSpan(minutes(from), minutes(to))
    }

    @Test
    fun kunsthausIsOpenOnWednesdayAndClosedOnMonday() {
        // The Kunsthaus Zürich (relation 3768127).
        val hours = schedule("Tu-Su 10:00-18:00; Th 10:00-20:00; PH 10:00-18:00")
        assertEquals(OpeningStatus.Open(closesAt = at("2026-11-25", 18)), hours.status(at("2026-11-25", 12)))
        assertEquals(OpeningStatus.Closed(opensAt = at("2026-11-26", 10)), hours.status(at("2026-11-25", 19)))
        assertEquals(OpeningStatus.Closed(opensAt = at("2026-12-01", 10)), hours.status(at("2026-11-30", 9)))
        // Thursday's own rule replaces Tu-Su for that day.
        assertEquals(listOf(span("10:00", "20:00")), hours.openSpans(LocalDate.parse("2026-11-26")))
        assertEquals(emptyList(), hours.openSpans(LocalDate.parse("2026-11-30")))
        assertTrue(hours.mentionsHolidays)
    }

    @Test
    fun hoursPastMidnightRunIntoTheNextDay() {
        // Hafenbar zur Metzgerhalle, Lucerne.
        val hours = schedule("Mo-Th 17:00-00:30; Fr-Sa 17:00-02:00")
        assertEquals(listOf(span("17:00", "26:00")), hours.openSpans(LocalDate.parse("2026-11-27")))
        // Saturday 01:00 is still Friday night.
        assertEquals(OpeningStatus.Open(closesAt = at("2026-11-28", 2)), hours.status(at("2026-11-28", 1)))
        // Sunday 01:00 is Saturday night; then closed until Monday 17:00.
        assertEquals(OpeningStatus.Open(closesAt = at("2026-11-29", 2)), hours.status(at("2026-11-29", 1)))
        assertEquals(OpeningStatus.Closed(opensAt = at("2026-11-30", 17)), hours.status(at("2026-11-29", 3)))
    }

    @Test
    fun aLunchBreakIsTwoSpans() {
        // InoVino, San Francisco.
        val hours = schedule("Mo-Tu 15:00-21:00; Fr-Sa 11:00-14:00,15:00-21:30; Su,We,Th 11:00-14:00,15:00-21:00")
        assertEquals(listOf(span("11:00", "14:00"), span("15:00", "21:00")), hours.openSpans(LocalDate.parse("2026-11-25")))
        assertEquals(OpeningStatus.Closed(opensAt = at("2026-11-25", 15)), hours.status(at("2026-11-25", 14, 30)))
    }

    @Test
    fun twentyFourSevenNeverCloses() {
        // Brandenburger Tor.
        assertEquals(OpeningStatus.Open(closesAt = null), schedule("24/7").status(at("2026-11-25", 3)))
    }

    @Test
    fun aDayOffAndPublicHolidaysOff() {
        // Babu's Bakery, Zürich: "PH off" is taken not to apply (the app knows no holidays).
        val hours = schedule("Tu-Fr 07:30-17:00, Sa 08:30-18:00, Su 09:00-15:00; PH off")
        assertEquals(OpeningStatus.Open(closesAt = at("2026-11-25", 17)), hours.status(at("2026-11-25", 8)))
        assertEquals(OpeningStatus.Closed(opensAt = at("2026-12-01", 7, 30)), hours.status(at("2026-11-30", 10)))
        assertTrue(hours.mentionsHolidays)
    }

    @Test
    fun monthsAndDates() {
        // Chinagarten Zürich: longer in summer.
        val garden = schedule("11:00-17:00; May-Oct 11:00-19:00")
        assertEquals(listOf(span("11:00", "17:00")), garden.openSpans(LocalDate.parse("2026-11-25")))
        assertEquals(listOf(span("11:00", "19:00")), garden.openSpans(LocalDate.parse("2026-07-14")))
        // Verkehrshaus Luzern: two seasons by date.
        val museum = schedule("Mar 29-Oct 24 10:00-18:00; Oct 25-Mar 27 10:00-17:00")
        assertEquals(listOf(span("10:00", "17:00")), museum.openSpans(LocalDate.parse("2026-11-25")))
        assertEquals(listOf(span("10:00", "18:00")), museum.openSpans(LocalDate.parse("2026-07-14")))
        // Tour Eiffel: dates off.
        val tower = schedule("09:30-23:45; Jun 21-Sep 02: 09:00-00:45; Jul 14,Jul 15 off")
        assertEquals(emptyList(), tower.openSpans(LocalDate.parse("2026-07-14")))
        assertEquals(listOf(span("09:00", "24:45")), tower.openSpans(LocalDate.parse("2026-06-21")))
    }

    @Test
    fun whatThePageCannotWorkOutIsUnknown() {
        // A comment only (Clare Teagle Patterson, LMFT), sunrise, week numbers.
        for (text in listOf("\"By appointment\"", "sunrise-sunset", "week 01-10 Mo 10:00-12:00")) {
            val hours = OpeningSchedule.parse(text)
            if (hours != null) {
                assertEquals(OpeningStatus.Unknown, hours.status(at("2026-11-25", 12)), text)
                assertNull(hours.openSpans(LocalDate.parse("2026-11-25")), text)
            }
        }
        assertNull(OpeningSchedule.parse("call us"))
    }

    @Test
    fun openEveryDay() {
        val hours = schedule("Mo-Su 07:00-17:00")
        assertEquals(OpeningStatus.Closed(opensAt = at("2026-11-26", 7)), hours.status(at("2026-11-25", 17)))
        assertFalse(hours.mentionsHolidays)
    }
}
