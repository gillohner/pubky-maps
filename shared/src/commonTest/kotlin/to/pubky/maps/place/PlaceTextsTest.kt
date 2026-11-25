package to.pubky.maps.place

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlaceTextsTest {
    private val wednesdayNoon = LocalDateTime(LocalDate.parse("2026-11-25"), LocalTime(12, 0))

    @Test
    fun openNowLines() {
        val kunsthaus = OpeningSchedule.parse("Tu-Su 10:00-18:00; Th 10:00-20:00")!!
        assertEquals(OpeningLine(true, "Open · Closes 18:00"), openingLine(kunsthaus.status(wednesdayNoon), wednesdayNoon))
        val evening = LocalDateTime(LocalDate.parse("2026-11-25"), LocalTime(19, 0))
        assertEquals(OpeningLine(false, "Closed · Opens Thu 10:00"), openingLine(kunsthaus.status(evening), evening))
        assertEquals(OpeningLine(true, "Open 24 hours"), openingLine(OpeningSchedule.parse("24/7")!!.status(evening), evening))
        assertNull(openingLine(OpeningStatus.Unknown, evening))
    }

    @Test
    fun theWeekFromToday() {
        val rows = weekRows(OpeningSchedule.parse("Mo-Th 17:00-00:30; Fr-Sa 17:00-02:00")!!, LocalDate.parse("2026-11-25"))!!
        assertEquals("Wednesday", rows[0].day)
        assertEquals("17:00–00:30", rows[0].hours)
        assertEquals("17:00–02:00", rows[2].hours)
        assertEquals("Closed", rows[4].hours)
        assertEquals(listOf(true, false, false, false, false, false, false), rows.map { it.today })
        assertEquals("Open 24 hours", weekRows(OpeningSchedule.parse("24/7")!!, LocalDate.parse("2026-11-25"))!![0].hours)
    }

    @Test
    fun ages() {
        val now = 1_800_000_000_000L
        val date = LocalDate.parse("2026-11-12")
        assertEquals("just now", ageText(now, now - 20_000, date))
        assertEquals("5 min ago", ageText(now, now - 5 * 60_000, date))
        assertEquals("3 h ago", ageText(now, now - 3 * 3_600_000, date))
        assertEquals("yesterday", ageText(now, now - 30 * 3_600_000L, date))
        assertEquals("12 Nov 2026", ageText(now, now - 13 * 86_400_000L, date))
    }

    @Test
    fun alsoOnShowsOneWikipedia() {
        val links =
            listOf(
                AlsoOnLink("Official website", "http://www.kunsthaus.ch/", derived = true),
                AlsoOnLink("Wikipedia (de)", "https://de.wikipedia.org/wiki/Kunsthaus_Z%C3%BCrich", derived = true),
                AlsoOnLink("Wikipedia (en)", "https://en.wikipedia.org/wiki/Kunsthaus_Z%C3%BCrich", derived = true),
                AlsoOnLink("OpenStreetMap", "https://www.openstreetmap.org/relation/3768127", derived = false),
            )
        assertEquals(
            listOf("Official website", "Wikipedia (de)"),
            alsoOnShown(links, "https://www.openstreetmap.org/relation/3768127", "de").map { it.site },
        )
        assertEquals("Wikipedia (en)", alsoOnShown(links, "x", "fr").last().site)
    }

    @Test
    fun credits() {
        val credit =
            PlaceCredit("image", "Ilya Hirschberg (Kreecher)", "CC-BY-SA-4.0", null, "https://commons.wikimedia.org/x", "wikimedia_commons")
        assertEquals("Photo: Ilya Hirschberg (Kreecher) · CC BY-SA 4.0 · Wikimedia Commons", creditText("Photo", credit))
        assertEquals("Text: Wikidata", creditText("Text", credit.copy(author = null, license = null, provider = "wikidata")))
    }

    @Test
    fun theOfficialWebsiteIsLeftOutWhenTheDetailsHaveOne() {
        val links =
            listOf(
                AlsoOnLink("Official website", "http://www.kunsthaus.ch/", derived = true),
                AlsoOnLink("Wikidata", "https://www.wikidata.org/wiki/Q685038", derived = true),
            )
        assertEquals(listOf("Wikidata"), alsoOnShown(links, "x", "en", hasWebsite = true).map { it.site })
        assertEquals(listOf("Official website", "Wikidata"), alsoOnShown(links, "x", "en", hasWebsite = false).map { it.site })
    }

    @Test
    fun oneCountForTheTaggersAvatarsAndText() {
        val people =
            mapOf(
                "anna" to PlacePerson("anna", "Anna", null, followed = true),
                "ben" to PlacePerson("ben", "Ben", null, followed = false),
            )

        fun tag(
            label: String,
            vararg taggers: String,
            count: Int = taggers.size,
            mine: Boolean = false,
        ) = PlaceTag(label, count, taggers.toList(), mine)

        // One person: no others.
        assertEquals(TaggersLine(listOf("anna"), "Tagged by Anna"), taggersLine(listOf(tag("art", "anna")), people, null))
        // People who used several labels count once: 4 distinct people over 3 labels, 2 named.
        val several =
            taggersLine(
                listOf(tag("art", "ben", "anna", "cleo"), tag("museum", "anna", "dora"), tag("rainy", "ben", "cleo")),
                people,
                null,
            )!!
        assertEquals(listOf("anna", "ben"), several.shown)
        assertEquals("Tagged by Anna, Ben and 2 others", several.text)
        // Nexus lists at most 5 per label: people it counts but does not list are added, label by label.
        val unlisted = taggersLine(listOf(tag("art", "ben", "anna", "cleo", count = 12)), people, null)!!
        assertEquals("Tagged by Anna, Ben and 10 others", unlisted.text)
        // The user is said apart and counted nowhere, also with two labels of their own.
        val mine =
            taggersLine(
                listOf(
                    tag("rainy", "me", "anna", mine = true),
                    tag("art", "ben", "me", mine = true),
                    tag("old", "me", "cleo", mine = true),
                ),
                people,
                "me",
            )!!
        assertEquals("Tagged by Anna, Ben and 1 other", mine.text)
        // A label of the user's that Nexus counts but does not list them on: they are not among the unlisted.
        assertEquals("Tagged by Anna", taggersLine(listOf(tag("rainy", "anna", count = 2, mine = true)), people, "me")!!.text)
        // A key without a profile reads as a short key; nobody else than the user: no line.
        assertEquals("Tagged by pubkybbbb…bbbb", taggersLine(listOf(tag("art", "bbbbbbbbbbbbbbbb")), emptyMap(), null)!!.text)
        assertNull(taggersLine(listOf(tag("rainy", "me", mine = true)), people, "me"))
    }

    @Test
    fun theSchoberFixtureCountsPeopleNotTags() {
        // Nexus' view of the Conditorei Schober: coffee (3), cake, rtiafter, zurich; w36y… used coffee and zurich.
        val views = parseNexusResourceViews(PlaceFixtures.read("nexus-by-ids-zurich.json"))
        val social = mergeResourceViews(views.filter { it.uri == "https://www.openstreetmap.org/node/270803095" })
        val line = taggersLine(social.tags, emptyMap(), null)!!
        assertEquals(2, line.shown.size)
        assertTrue(line.text.endsWith(" and 3 others"), line.text)
    }

    @Test
    fun cardLinksOpenOnlyAsWebPages() {
        assertEquals("https://www.kunsthaus.ch/", httpLink(" https://www.kunsthaus.ch/ "))
        assertEquals("HTTP://example.org", httpLink("HTTP://example.org"))
        listOf(
            "intent://scan/#Intent;scheme=zxing;package=com.example;end",
            "javascript:alert(1)",
            "file:///data/data/to.pubky.maps/secrets",
            "pubkyauth:///?relay=https://evil.example&secret=x",
            "geo:47.3,8.5",
            "content://contacts/1",
            "https://",
            "https://exa mple.org",
            "",
            null,
        ).forEach { assertNull(httpLink(it), "$it must not open") }
    }

    @Test
    fun alsoOnLeavesOutLinksThatAreNotWebPages() {
        val links =
            listOf(
                AlsoOnLink("Google Maps", "intent://maps#Intent;package=com.evil;end", derived = false),
                AlsoOnLink("Official website", "javascript:alert(1)", derived = true),
                AlsoOnLink("Wikidata", "https://www.wikidata.org/wiki/Q685038", derived = true),
            )

        assertEquals(listOf("Wikidata"), alsoOnShown(links, "https://www.openstreetmap.org/node/1", "en").map { it.site })
    }

    @Test
    fun aCreditOpensItsLicenceElseItsSourceAndNeverAnotherScheme() {
        fun credit(
            license: String?,
            source: String,
        ) = PlaceCredit("image", "A", "CC-BY-4.0", license, source, "wikimedia_commons")

        assertEquals(
            "https://creativecommons.org/licenses/by/4.0/",
            credit("https://creativecommons.org/licenses/by/4.0/", "https://x.org").link,
        )
        assertEquals("https://commons.wikimedia.org/f", credit("javascript:alert(1)", "https://commons.wikimedia.org/f").link)
        assertNull(credit(null, "file:///etc/hosts").link)
    }
}
