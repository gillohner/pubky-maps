package to.pubky.maps.place

import de.westnordost.osm_opening_hours.model.CalendarDate
import de.westnordost.osm_opening_hours.model.ClockTime
import de.westnordost.osm_opening_hours.model.DateRange
import de.westnordost.osm_opening_hours.model.DatesInMonth
import de.westnordost.osm_opening_hours.model.ExtendedClockTime
import de.westnordost.osm_opening_hours.model.ExtendedTime
import de.westnordost.osm_opening_hours.model.MonthDay
import de.westnordost.osm_opening_hours.model.MonthDayRange
import de.westnordost.osm_opening_hours.model.MonthRange
import de.westnordost.osm_opening_hours.model.MonthsOrDateSelector
import de.westnordost.osm_opening_hours.model.OpeningHours
import de.westnordost.osm_opening_hours.model.Range
import de.westnordost.osm_opening_hours.model.Rule
import de.westnordost.osm_opening_hours.model.RuleOperator
import de.westnordost.osm_opening_hours.model.RuleType
import de.westnordost.osm_opening_hours.model.SingleMonth
import de.westnordost.osm_opening_hours.model.TimeSpan
import de.westnordost.osm_opening_hours.model.TwentyFourSeven
import de.westnordost.osm_opening_hours.model.Weekday
import de.westnordost.osm_opening_hours.model.WeekdayRange
import de.westnordost.osm_opening_hours.model.WeekdaysSelector
import de.westnordost.osm_opening_hours.parser.toOpeningHoursOrNull
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.plus

/**
 * A place's OpenStreetMap `opening_hours`, parsed by osm-opening-hours and evaluated here for the
 * common forms: weekdays and weekday ranges, times (also past midnight), months and dates without a
 * year, `24/7`, `off`/`closed`, `,` and `;` rules and a `||` fallback. Public and school holidays
 * are not known to the app: a holiday-only rule (`PH off`) is taken not to apply today, and
 * [mentionsHolidays] says so. Anything else (sunrise, week numbers, Easter, the nth weekday of a
 * month, years, free text) makes the hours of a day unknown, and the page shows the text as written.
 */
class OpeningSchedule private constructor(
    /** The text as OpenStreetMap has it. */
    val text: String,
    private val hours: OpeningHours,
) {
    /** True when a rule names public or school holidays, which the app cannot tell. */
    val mentionsHolidays: Boolean =
        hours.rules.any { rule -> (rule.selector as? Range)?.holidays?.isNotEmpty() == true }

    /**
     * The spans of [date]'s own rules, earliest first; a span past midnight ends after 24:00
     * (`22:00-02:00` ends at 26:00). Only open spans are listed. Null when the hours of the day cannot be
     * worked out (see the class).
     */
    fun openSpans(date: LocalDate): List<MinuteSpan>? {
        val spans = daySpans(date) ?: return null
        if (spans.any { it.state == SpanState.Unknown }) return null
        return timeline(spans, 0, LATEST_END).filter { it.state == SpanState.Open }.map { MinuteSpan(it.start, it.end) }
    }

    /** Open, closed or unknown at [at], and when that changes within the next week. */
    fun status(at: LocalDateTime): OpeningStatus {
        val now = at.hour * 60 + at.minute
        val today = at.date
        val current = stateAt(today, now) ?: return OpeningStatus.Unknown
        if (current == SpanState.Unknown) return OpeningStatus.Unknown
        for (offset in 0..DAYS_AHEAD) {
            val date = today.plus(DatePeriod(days = offset))
            val boundaries = boundaries(date) ?: return statusWithoutChange(current)
            for (minute in boundaries) {
                if (offset == 0 && minute <= now) continue
                val state = stateAt(date, minute) ?: return statusWithoutChange(current)
                if (state != current) {
                    val change = LocalDateTime(date, LocalTime(minute / 60, minute % 60))
                    return if (current == SpanState.Open) OpeningStatus.Open(closesAt = change) else OpeningStatus.Closed(opensAt = change)
                }
            }
        }
        return statusWithoutChange(current)
    }

    private fun statusWithoutChange(state: SpanState): OpeningStatus =
        when (state) {
            SpanState.Open -> OpeningStatus.Open(closesAt = null)
            SpanState.Closed -> OpeningStatus.Closed(opensAt = null)
            SpanState.Unknown -> OpeningStatus.Unknown
        }

    /** The state at [minute] of [date]: the day's own spans, over what yesterday's spans run into it. */
    private fun stateAt(
        date: LocalDate,
        minute: Int,
    ): SpanState? {
        val today = daySpans(date) ?: return null
        val yesterday = daySpans(date.plus(DatePeriod(days = -1))) ?: return null
        val spill = yesterday.filter { it.end > DAY }.map { it.copy(start = (it.start - DAY).coerceAtLeast(0), end = it.end - DAY) }
        val covering = (spill + today).lastOrNull { minute >= it.start && minute < it.end }
        return covering?.state ?: SpanState.Closed
    }

    /** The minutes of [date] at which its state can change. */
    private fun boundaries(date: LocalDate): List<Int>? {
        val today = daySpans(date) ?: return null
        val yesterday = daySpans(date.plus(DatePeriod(days = -1))) ?: return null
        val minutes = mutableSetOf(0)
        today.forEach {
            minutes += it.start.coerceAtMost(DAY - 1)
            if (it.end < DAY) minutes += it.end
        }
        yesterday.filter { it.end > DAY }.forEach { minutes += (it.end - DAY).coerceAtMost(DAY - 1) }
        return minutes.sorted()
    }

    /**
     * The spans [date]'s rules give it, in rule order (a later span wins where they overlap). A
     * normal rule (`;`) that applies replaces what earlier rules said about the day; an additional
     * rule (`,`) adds to it; a fallback rule (`||`) applies only when no rule did. Null when a rule
     * that could apply cannot be evaluated.
     */
    private fun daySpans(date: LocalDate): List<StateSpan>? {
        val spans = mutableListOf<StateSpan>()
        var matched = false
        for (rule in hours.rules) {
            if (rule.ruleOperator == RuleOperator.Fallback && matched) continue
            when (rule.applies(date)) {
                Match.No -> {
                    continue
                }

                Match.Unknown -> {
                    return null
                }

                Match.Yes -> {}
            }
            if (rule.ruleOperator == RuleOperator.Normal) spans.clear()
            spans += rule.spans() ?: return null
            matched = true
        }
        return spans
    }

    companion object {
        /** Parses [text] leniently (as StreetComplete does); null when it is not opening hours. */
        fun parse(text: String): OpeningSchedule? =
            text.trim().toOpeningHoursOrNull(lenient = true)?.let { OpeningSchedule(text.trim(), it) }

        private const val DAY = 24 * 60

        /** A span ends at the latest 48:00, the end of the next day. */
        private const val LATEST_END = 2 * DAY

        private const val DAYS_AHEAD = 7
    }
}

/** Minutes after midnight, [end] exclusive and possibly past 24:00. */
data class MinuteSpan(
    val start: Int,
    val end: Int,
)

/** Whether a place is open now. */
sealed interface OpeningStatus {
    /** Open; [closesAt] is null when it stays open all of the next week. */
    data class Open(
        val closesAt: LocalDateTime?,
    ) : OpeningStatus

    /** Closed; [opensAt] is null when it does not open within the next week. */
    data class Closed(
        val opensAt: LocalDateTime?,
    ) : OpeningStatus

    /** The hours cannot be worked out for now. */
    data object Unknown : OpeningStatus
}

private enum class SpanState { Open, Closed, Unknown }

private data class StateSpan(
    val start: Int,
    val end: Int,
    val state: SpanState,
)

private enum class Match { Yes, No, Unknown }

/** Splits overlapping [spans] (later ones win) into consecutive pieces within [from]..[to]. */
private fun timeline(
    spans: List<StateSpan>,
    from: Int,
    to: Int,
): List<StateSpan> {
    val cuts = (spans.flatMap { listOf(it.start, it.end) } + from + to).filter { it in from..to }.distinct().sorted()
    val pieces = mutableListOf<StateSpan>()
    for (i in 0 until cuts.size - 1) {
        val start = cuts[i]
        val end = cuts[i + 1]
        val state = spans.lastOrNull { start >= it.start && start < it.end }?.state ?: continue
        val last = pieces.lastOrNull()
        if (last != null && last.end == start && last.state == state) {
            pieces[pieces.size - 1] = last.copy(end = end)
        } else {
            pieces += StateSpan(start, end, state)
        }
    }
    return pieces
}

private fun Rule.state(): SpanState =
    when (ruleType) {
        null, RuleType.Open -> SpanState.Open
        RuleType.Closed, RuleType.Off -> SpanState.Closed
        RuleType.Unknown -> SpanState.Unknown
    }

/** The spans this rule gives a day it applies to; null when its times cannot be evaluated. */
private fun Rule.spans(): List<StateSpan>? {
    val state = state()
    val times =
        when (val selector = selector) {
            TwentyFourSeven -> null
            is Range -> selector.times
        }
    if (times.isNullOrEmpty()) return listOf(StateSpan(0, 24 * 60, state))
    return times.map { time ->
        val span = time as? TimeSpan ?: return null
        if (span.openEnd) return null
        val start = (span.start as? ClockTime)?.let { it.hour * 60 + it.minutes } ?: return null
        var end = span.end.minutes() ?: return null
        if (end <= start) end += 24 * 60
        StateSpan(start, end, state)
    }
}

private fun ExtendedTime.minutes(): Int? =
    when (this) {
        is ClockTime -> hour * 60 + minutes
        is ExtendedClockTime -> hour * 60 + minutes
        else -> null
    }

/** Whether this rule's selector applies to [date]. */
private fun Rule.applies(date: LocalDate): Match {
    val range =
        when (val selector = selector) {
            TwentyFourSeven -> return Match.Yes
            is Range -> selector
        }
    // "off" alone closes every day; a comment alone says nothing the page can evaluate.
    if (range.isEmpty()) return if (ruleType != null && ruleType != RuleType.Unknown) Match.Yes else Match.Unknown
    if (range.years != null || range.weeks != null || range.text != null) return Match.Unknown
    range.months?.let { months ->
        val matches = months.map { it.matches(date) }
        if (Match.Unknown in matches) return Match.Unknown
        if (Match.Yes !in matches) return Match.No
    }
    val holidays = range.holidays.orEmpty()
    // The app does not know holidays: a rule only for holidays is taken not to apply.
    if (holidays.isNotEmpty() && (range.isRestrictedByHolidays || range.weekdays == null)) return Match.No
    val weekdays = range.weekdays ?: return Match.Yes
    val matches = weekdays.map { it.matches(date) }
    return when {
        Match.Unknown in matches -> Match.Unknown
        Match.Yes in matches -> Match.Yes
        else -> Match.No
    }
}

private fun WeekdaysSelector.matches(date: LocalDate): Match {
    val day = date.dayOfWeek.ordinal // kotlinx-datetime: 0 is Monday, as in osm-opening-hours.
    return when (this) {
        is Weekday -> if (ordinal == day) Match.Yes else Match.No
        is WeekdayRange -> if (inCircularRange(day, start.ordinal, end.ordinal)) Match.Yes else Match.No
        else -> Match.Unknown
    }
}

private fun MonthsOrDateSelector.matches(date: LocalDate): Match {
    val monthIndex = date.month.ordinal
    return when (this) {
        is SingleMonth -> {
            if (year != null) {
                Match.Unknown
            } else if (month.ordinal == monthIndex) {
                Match.Yes
            } else {
                Match.No
            }
        }

        is MonthRange -> {
            if (year != null) {
                Match.Unknown
            } else if (inCircularRange(monthIndex, start.ordinal, end.ordinal)) {
                Match.Yes
            } else {
                Match.No
            }
        }

        is DatesInMonth -> {
            when {
                year != null -> {
                    Match.Unknown
                }

                month.ordinal != monthIndex -> {
                    Match.No
                }

                days.any { (it is MonthDay && it.day == date.day) || (it is MonthDayRange && date.day in it.start..it.end) } -> {
                    Match.Yes
                }

                else -> {
                    Match.No
                }
            }
        }

        is CalendarDate -> {
            val key = dayKey() ?: return Match.Unknown
            if (key == monthIndex * 32 + date.day) Match.Yes else Match.No
        }

        is DateRange -> {
            val from = (start as? CalendarDate)?.dayKey() ?: return Match.Unknown
            val to = (end as? CalendarDate)?.dayKey() ?: return Match.Unknown
            if (inCircularRange(monthIndex * 32 + date.day, from, to)) Match.Yes else Match.No
        }

        else -> {
            Match.Unknown
        }
    }
}

/** A date without a year as one comparable number; null for dates the page does not evaluate. */
private fun CalendarDate.dayKey(): Int? = if (year == null && weekdayOffset == null && dayOffset == 0) month.ordinal * 32 + day else null

/** [value] in [start]..[end], where the range may wrap around (`Fr-Mo`, `Nov-Feb`). */
private fun inCircularRange(
    value: Int,
    start: Int,
    end: Int,
): Boolean = if (start <= end) value in start..end else value >= start || value <= end
