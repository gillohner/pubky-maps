package to.pubky.maps.place

import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.plus
import to.pubky.maps.links.LinkProvider
import to.pubky.maps.search.categoryOf
import to.pubky.maps.search.schemaTypeLabel

// What the place page says, as plain functions of its state, so the wording is tested without
// Compose. Times are in the phone's time zone and the 24-hour clock, as OpenStreetMap writes hours.

/** What the place is, in words: OSM's category, else the card's schema.org type, else what the opener knew. */
fun PlacePageState.categoryText(): String? {
    val osm =
        card?.category?.let { category ->
            val key = category.substringBefore('/', "")
            val value = category.substringAfter('/', "")
            categoryOf(key.ifEmpty { null }, value.ifEmpty { null }, photonType = null)
        }
    return osm ?: card?.type?.let(::schemaTypeLabel) ?: place.category
}

/** The page's title: the card's name, else what the place was opened as. */
val PlacePageState.title: String get() = card?.name ?: place.title

/** The address line: the opener's (Photon's is the most complete), else the card's. */
val PlacePageState.address: String? get() = place.address ?: card?.address

/**
 * The line under what the place is: its address, else its coordinates (unless the title is them),
 * and the app a link came from ("From a Google Maps link"), as on the place card.
 */
fun PlacePageState.detailText(provider: LinkProvider?): String? =
    listOfNotNull(place.copy(address = address).detailLine(), provider?.let { "From ${it.withArticle} link" })
        .joinToString(" · ")
        .ifEmpty { null }

/** "Open · Closes 18:00", "Closed · Opens Thu 10:00", or null when the hours are not known. */
fun openingLine(
    status: OpeningStatus,
    now: LocalDateTime,
): OpeningLine? =
    when (status) {
        is OpeningStatus.Open -> {
            OpeningLine(open = true, text = status.closesAt?.let { "Open · Closes ${whenText(it, now)}" } ?: "Open 24 hours")
        }

        is OpeningStatus.Closed -> {
            OpeningLine(open = false, text = status.opensAt?.let { "Closed · Opens ${whenText(it, now)}" } ?: "Closed")
        }

        OpeningStatus.Unknown -> {
            null
        }
    }

data class OpeningLine(
    val open: Boolean,
    val text: String,
)

/** "18:00" today, "Thu 10:00" on another day. */
private fun whenText(
    at: LocalDateTime,
    now: LocalDateTime,
): String {
    val time = clock(at.hour * 60 + at.minute)
    return when (at.date) {
        now.date -> time
        else -> "${weekdayShort(at.date)} $time"
    }
}

/** One row of the week's hours: "Wednesday", "10:00–18:00" or "Closed". */
data class HoursRow(
    val day: String,
    val hours: String,
    val today: Boolean,
)

/** The next seven days' hours from [today], or null when they cannot be worked out. */
fun weekRows(
    schedule: OpeningSchedule,
    today: LocalDate,
): List<HoursRow>? =
    (0 until 7).map { offset ->
        val date = today.plus(DatePeriod(days = offset))
        val spans = schedule.openSpans(date) ?: return null
        HoursRow(
            day = weekdayLong(date),
            hours =
                when {
                    spans.isEmpty() -> "Closed"
                    spans.singleOrNull()?.let { it.start == 0 && it.end >= DAY } == true -> "Open 24 hours"
                    else -> spans.joinToString(", ") { "${clock(it.start)}–${clock(it.end)}" }
                },
            today = offset == 0,
        )
    }

/** "09:30", "02:00" for 26:00 (past midnight). */
internal fun clock(minutes: Int): String {
    val m = minutes % DAY
    return "${(m / 60).toString().padStart(2, '0')}:${(m % 60).toString().padStart(2, '0')}"
}

private const val DAY = 24 * 60

private val WEEKDAYS = listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")

private fun weekdayLong(date: LocalDate) = WEEKDAYS[date.dayOfWeek.ordinal]

private fun weekdayShort(date: LocalDate) = weekdayLong(date).take(3)

private val MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

/** "just now", "5 min ago", "3 h ago", "2 days ago", then the date ("12 Nov 2026"). */
fun ageText(
    nowMillis: Long,
    thenMillis: Long,
    thenDate: LocalDate,
): String {
    val minutes = (nowMillis - thenMillis) / 60_000
    return when {
        minutes < 1 -> "just now"
        minutes < 60 -> "$minutes min ago"
        minutes < 24 * 60 -> "${minutes / 60} h ago"
        minutes < 7 * 24 * 60 -> (minutes / (24 * 60)).let { if (it == 1L) "yesterday" else "$it days ago" }
        else -> dateText(thenDate)
    }
}

/** "25 Nov 2026". */
fun dateText(date: LocalDate): String = "${date.day} ${MONTHS[date.month.ordinal]} ${date.year}"

/** "25 Nov, 09:12". */
fun savedText(at: LocalDateTime): String = "${at.date.day} ${MONTHS[at.date.month.ordinal]}, ${clock(at.hour * 60 + at.minute)}"

/**
 * The "Also on" links worth a row: one Wikipedia (the phone's language, else English), every other
 * service once, not the link the page is about, and not Wikidata's "Official website" when the
 * details already have a website row ([hasWebsite]).
 */
fun alsoOnShown(
    links: List<AlsoOnLink>,
    pageUri: String,
    language: String,
    hasWebsite: Boolean = false,
): List<AlsoOnLink> {
    val (wikipedias, others) =
        links
            .filter { it.uri != pageUri && !(hasWebsite && it.site == "Official website") }
            .partition { it.site.startsWith("Wikipedia (") }
    val wikipedia =
        wikipedias.firstOrNull { it.site == "Wikipedia ($language)" }
            ?: wikipedias.firstOrNull { it.site == "Wikipedia (en)" }
    return (others + listOfNotNull(wikipedia)).distinctBy { it.site }
}

/** "CC-BY-SA-4.0" as "CC BY-SA 4.0"; other licence ids as they are. */
fun licenceText(license: String): String =
    if (license.startsWith("CC-")) {
        "CC " + license.removePrefix("CC-").replace(Regex("-(\\d)"), " $1")
    } else {
        license.replace('-', ' ')
    }

/** "Photo: Ilya Hirschberg · CC BY-SA 4.0 · Wikimedia Commons". */
fun creditText(
    what: String,
    credit: PlaceCredit,
): String =
    listOfNotNull(
        "$what: " + (credit.author ?: providerName(credit.provider)),
        credit.license?.let(::licenceText),
        providerName(credit.provider).takeIf { credit.author != null },
    ).joinToString(" · ")

private fun providerName(provider: String): String =
    when (provider) {
        "wikimedia_commons" -> "Wikimedia Commons"
        "wikipedia" -> "Wikipedia"
        "wikidata" -> "Wikidata"
        else -> provider.replace('_', ' ').replaceFirstChar { it.uppercaseChar() }
    }
