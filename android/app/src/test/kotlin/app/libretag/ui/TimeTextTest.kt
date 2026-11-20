package app.libretag.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale

class TimeTextTest {
    private val zone = ZoneId.of("Europe/Bratislava")
    private fun ms(y: Int, mo: Int, d: Int, h: Int, mi: Int) = LocalDateTime.of(y, mo, d, h, mi).atZone(zone).toInstant().toEpochMilli()
    private val now = ms(2026, 11, 16, 18, 0) // Monday
    private val today = ms(2026, 11, 16, 14, 32)
    private val yesterday = ms(2026, 11, 15, 23, 10)
    private val older = ms(2026, 11, 13, 9, 5) // Friday
    private val lastYear = ms(2025, 12, 30, 7, 0)

    private fun past(ms: Long, time: String, yest: String, l: Locale) = TimeText.past(ms, now, time, yest, l, zone)

    @Test
    fun days() {
        assertEquals(TimeText.Day.TODAY, TimeText.dayOf(today, now, zone))
        assertEquals(TimeText.Day.YESTERDAY, TimeText.dayOf(yesterday, now, zone))
        assertEquals(TimeText.Day.OLDER, TimeText.dayOf(older, now, zone))
        // just after midnight: five minutes ago is yesterday
        assertEquals(TimeText.Day.YESTERDAY, TimeText.dayOf(ms(2026, 11, 15, 23, 58), ms(2026, 11, 16, 0, 3), zone))
    }

    @Test
    fun rowSwitchesToClockAtSixtyMinutes() {
        val min = 60_000L
        assertEquals(TimeText.RowHeard.AGO, TimeText.rowHeard(now - 12 * min, now, zone))
        assertEquals(TimeText.RowHeard.AGO, TimeText.rowHeard(now - 59 * min - 59_999, now, zone))
        assertEquals(TimeText.RowHeard.AT_TODAY, TimeText.rowHeard(now - 60 * min, now, zone))
        assertEquals(TimeText.RowHeard.AT_TODAY, TimeText.rowHeard(today, now, zone))
        assertEquals(TimeText.RowHeard.AT_DAY, TimeText.rowHeard(yesterday, now, zone))
        assertEquals(TimeText.RowHeard.AT_DAY, TimeText.rowHeard(older, now, zone))
        // ten minutes ago across midnight stays relative
        assertEquals(TimeText.RowHeard.AGO, TimeText.rowHeard(ms(2026, 11, 15, 23, 55), ms(2026, 11, 16, 0, 5), zone))
        // 70 min ago across midnight: yesterday's clock time
        assertEquals(TimeText.RowHeard.AT_DAY, TimeText.rowHeard(ms(2026, 11, 15, 23, 0), ms(2026, 11, 16, 0, 10), zone))
    }

    @Test
    fun english() {
        val en = Locale.UK
        assertEquals("14:32", past(today, "14:32", "yesterday %1\$s", en))
        assertEquals("yesterday 23:10", past(yesterday, "23:10", "yesterday %1\$s", en))
        assertEquals("Fri 13/11 09:05", past(older, "09:05", "yesterday %1\$s", en))
        assertEquals("Tue 30/12/2025 07:00", past(lastYear, "07:00", "yesterday %1\$s", en))
        assertEquals("Fri 11/13 9:05 AM", past(older, "9:05 AM", "yesterday %1\$s", Locale.US))
    }

    @Test
    fun slovakAndCzech() {
        val sk = Locale("sk")
        assertEquals("14:32", past(today, "14:32", "včera %1\$s", sk))
        assertEquals("včera 23:10", past(yesterday, "23:10", "včera %1\$s", sk))
        assertEquals("pi 13. 11. 09:05", past(older, "09:05", "včera %1\$s", sk))
        assertEquals("ut 30. 12. 2025", TimeText.date(lastYear, now, sk, zone))
        assertEquals("pá 13. 11.", TimeText.date(older, now, Locale("cs"), zone))
    }

    @Test
    fun otherLanguages() {
        assertEquals("Fr 13.11.", TimeText.date(older, now, Locale.GERMAN, zone))
        assertEquals("vie 13/11", TimeText.date(older, now, Locale("es"), zone))
        assertEquals("vr 13-11", TimeText.date(older, now, Locale("nl"), zone))
    }
}
