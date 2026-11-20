package app.libretag.ui

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * When something happened, as a clock time a frightened owner can't misread: "14:32" today,
 * "yesterday 14:32", "Mon 16/11 14:32" further back (with the year when it isn't this year).
 * Pure JVM (no Context), so the day logic and the per-language date shapes are unit tested;
 * [Format.at] feeds it the phone's time format and the translated "yesterday".
 */
object TimeText {
    enum class Day { TODAY, YESTERDAY, OLDER }

    fun dayOf(ms: Long, now: Long, zone: ZoneId = ZoneId.systemDefault()): Day {
        val a = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()
        val b = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        return when {
            a == b || a.isAfter(b) -> Day.TODAY
            a == b.minusDays(1) -> Day.YESTERDAY
            else -> Day.OLDER
        }
    }

    /** How a list row says when the tag was heard: relative under an hour, the clock time after. */
    enum class RowHeard { AGO, AT_TODAY, AT_DAY }
    const val ROW_AGO_MS = 60 * 60_000L

    fun rowHeard(ms: Long, now: Long, zone: ZoneId = ZoneId.systemDefault()): RowHeard = when {
        now - ms < ROW_AGO_MS -> RowHeard.AGO
        dayOf(ms, now, zone) == Day.TODAY -> RowHeard.AT_TODAY
        else -> RowHeard.AT_DAY
    }

    /** Day name and date without the time: "Mon 16/11" (en), "po 16. 11." (sk), "Mo 16.11." (de). */
    fun date(ms: Long, now: Long, locale: Locale, zone: ZoneId = ZoneId.systemDefault()): String {
        val d = Instant.ofEpochMilli(ms).atZone(zone)
        val sameYear = d.year == Instant.ofEpochMilli(now).atZone(zone).year
        val pattern = when (locale.language) {
            "sk", "cs" -> if (sameYear) "EEE d. M." else "EEE d. M. yyyy"
            "de" -> if (sameYear) "EEE d.M." else "EEE d.M.yyyy"
            "nl" -> if (sameYear) "EEE d-M" else "EEE d-M-yyyy"
            "es" -> if (sameYear) "EEE d/M" else "EEE d/M/yyyy"
            else -> if (locale.country == "US") (if (sameYear) "EEE M/d" else "EEE M/d/yyyy")
                else (if (sameYear) "EEE d/M" else "EEE d/M/yyyy")
        }
        // CLDR short day names may end with a dot ("Mo.", "lun."): drop it, the date follows anyway
        val day = DateTimeFormatter.ofPattern("EEE", locale).format(d).trimEnd('.')
        return day + DateTimeFormatter.ofPattern(pattern.removePrefix("EEE"), locale).format(d)
    }

    /**
     * A past moment: [time] alone today, [yesterday] (a "%1$s"-style template with the time) for
     * yesterday, else the date and the time.
     */
    fun past(ms: Long, now: Long, time: String, yesterday: String, locale: Locale, zone: ZoneId = ZoneId.systemDefault()): String =
        when (dayOf(ms, now, zone)) {
            Day.TODAY -> time
            Day.YESTERDAY -> String.format(locale, yesterday, time)
            Day.OLDER -> date(ms, now, locale, zone) + " " + time
        }
}
