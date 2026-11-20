package app.libretag.ui

import android.content.Context
import android.text.format.DateFormat
import app.libretag.LibreTagApp
import app.libretag.R
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Times, ages, distances and coordinates in the brief's vocabulary ("heard 12 min ago", "±1.2 km"). */
object Format {
    private const val MIN = 60_000L
    private const val HOUR = 3_600_000L
    private const val DAY = 86_400_000L

    /** Short age for the list's right column: "now", "12 min", "3 h", "2 d". */
    fun ageShort(ctx: Context, ms: Long, now: Long = System.currentTimeMillis()): String {
        val d = (now - ms).coerceAtLeast(0)
        return when {
            d < MIN -> ctx.getString(R.string.age_now)
            d < HOUR -> ctx.getString(R.string.age_min, (d / MIN).toInt())
            d < DAY -> ctx.getString(R.string.age_h, (d / HOUR).toInt())
            else -> ctx.getString(R.string.age_d, (d / DAY).toInt())
        }
    }

    /** "just now", "12 min ago", "1 h 20 min ago", "3 h ago", "2 days ago". */
    fun agoLong(ctx: Context, ms: Long, now: Long = System.currentTimeMillis()): String {
        val d = (now - ms).coerceAtLeast(0)
        return when {
            d < MIN -> ctx.getString(R.string.ago_just_now)
            d < HOUR -> ctx.getString(R.string.ago_min, (d / MIN).toInt())
            d < 3 * HOUR && (d % HOUR) / MIN >= 10 -> ctx.getString(R.string.ago_h_min, (d / HOUR).toInt(), ((d % HOUR) / MIN).toInt())
            d < DAY -> ctx.getString(R.string.ago_h, (d / HOUR).toInt())
            else -> ctx.resources.getQuantityString(R.plurals.ago_days, (d / DAY).toInt(), (d / DAY).toInt())
        }
    }

    /** Spoken form for TalkBack: "12 minutes", "3 hours". */
    fun ageWords(ctx: Context, ms: Long, now: Long = System.currentTimeMillis()): String {
        val d = (now - ms).coerceAtLeast(0)
        return when {
            d < HOUR -> ctx.resources.getQuantityString(R.plurals.minutes, (d / MIN).toInt(), (d / MIN).toInt())
            d < DAY -> ctx.resources.getQuantityString(R.plurals.hours, (d / HOUR).toInt(), (d / HOUR).toInt())
            else -> ctx.resources.getQuantityString(R.plurals.days, (d / DAY).toInt(), (d / DAY).toInt())
        }
    }

    /** A duration: "45 min", "3 h", "2 d". */
    fun duration(ctx: Context, ms: Long): String {
        val d = ms.coerceAtLeast(0)
        return when {
            d < HOUR -> ctx.getString(R.string.age_min, (d / MIN).toInt())
            d < 2 * DAY -> ctx.getString(R.string.age_h, (d / HOUR).toInt())
            else -> ctx.getString(R.string.age_d, (d / DAY).toInt())
        }
    }

    /** "hourly", "every 10 min", "every 2 h", "daily". */
    fun every(ctx: Context, intervalS: Int): String = when {
        intervalS == 3600 -> ctx.getString(R.string.every_hourly)
        intervalS == 86400 -> ctx.getString(R.string.every_daily)
        intervalS < 3600 -> ctx.getString(R.string.every_min, intervalS / 60)
        else -> ctx.getString(R.string.every_h, intervalS / 3600)
    }

    /** "14:31" (or "2:31 PM"), with the day when it is not today: "Tue 08:00". */
    fun clock(ctx: Context, ms: Long, now: Long = System.currentTimeMillis()): String {
        val t = DateFormat.getTimeFormat(ctx).format(Date(ms))
        val a = Calendar.getInstance().apply { timeInMillis = ms }
        val b = Calendar.getInstance().apply { timeInMillis = now }
        if (a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)) return t
        val tomorrow = (b.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, 1) }
        if (a.get(Calendar.YEAR) == tomorrow.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == tomorrow.get(Calendar.DAY_OF_YEAR))
            return ctx.getString(R.string.clock_tomorrow, t)
        val day = java.text.SimpleDateFormat("EEE", Locale.getDefault()).format(Date(ms))
        return "$day $t"
    }

    private fun locale(ctx: Context): Locale =
        ctx.resources.configuration.locales.takeIf { !it.isEmpty }?.get(0) ?: Locale.getDefault()

    /**
     * When it happened (a fix, a report): "14:32" today, "yesterday 14:32", "Mon 16/11 14:32"
     * further back. For past moments; [clock] is for times ahead ("14:31 tomorrow").
     */
    fun at(ctx: Context, ms: Long, now: Long = System.currentTimeMillis()): String =
        TimeText.past(ms, now, time(ctx, ms), ctx.getString(R.string.clock_yesterday), locale(ctx))

    /** Relative age plus the exact time: "5 h ago (14:32)", "2 days ago (Sat 14/11 09:10)". */
    fun agoAt(ctx: Context, ms: Long, now: Long = System.currentTimeMillis()): String =
        ctx.getString(R.string.ago_at, agoLong(ctx, ms, now), at(ctx, ms, now))

    /** [agoAt] with "~" before the time when it is only approximate (0.8.1, a report a bridge drained late). */
    fun agoAt(ctx: Context, ms: Long, approx: Boolean, now: Long = System.currentTimeMillis()): String =
        if (!approx) agoAt(ctx, ms, now) else ctx.getString(R.string.ago_at, agoLong(ctx, ms, now), "~" + at(ctx, ms, now))

    /**
     * List rows: "Heard 12 min ago" under an hour, then the clock time only ("Heard at 14:19",
     * "Heard yesterday 14:19", "Heard Fri 13/11 09:05"): for older reports the time is what matters.
     */
    fun heardRow(ctx: Context, ms: Long, now: Long = System.currentTimeMillis()): String = when (TimeText.rowHeard(ms, now)) {
        TimeText.RowHeard.AGO -> ctx.getString(R.string.st_heard, agoLong(ctx, ms, now))
        TimeText.RowHeard.AT_TODAY -> ctx.getString(R.string.row_heard_at, time(ctx, ms))
        TimeText.RowHeard.AT_DAY -> ctx.getString(R.string.st_heard, at(ctx, ms, now))
    }

    /** Time of day only: "14:31". */
    fun time(ctx: Context, ms: Long): String = DateFormat.getTimeFormat(ctx).format(Date(ms))

    /** "Mon 16 Nov" */
    fun day(ms: Long): String = java.text.SimpleDateFormat(
        DateFormat.getBestDateTimePattern(Locale.getDefault(), "EEEdMMM"), Locale.getDefault()).format(Date(ms))

    /** "16 Nov" */
    fun date(ms: Long): String = java.text.SimpleDateFormat(
        DateFormat.getBestDateTimePattern(Locale.getDefault(), "dMMM"), Locale.getDefault()).format(Date(ms))

    private fun imperial(ctx: Context) = runCatching { LibreTagApp.instance.prefs.units == "imperial" }.getOrDefault(false)

    /** "8 m", "350 m", "1.2 km" (or feet/miles). */
    fun distance(ctx: Context, m: Double): String {
        if (imperial(ctx)) {
            val ft = m * 3.28084
            return if (ft < 1000) ctx.getString(R.string.dist_ft, ((ft / 10).toInt() * 10).coerceAtLeast(1))
            else ctx.getString(R.string.dist_mi, String.format(Locale.getDefault(), "%.1f", m / 1609.344))
        }
        return when {
            m < 100 -> ctx.getString(R.string.dist_m, m.toInt().coerceAtLeast(1))
            m < 1000 -> ctx.getString(R.string.dist_m, (m / 10).toInt() * 10)
            else -> ctx.getString(R.string.dist_km, String.format(Locale.getDefault(), "%.1f", m / 1000))
        }
    }

    /** Coordinates as copied to the clipboard and sent to map apps: "48.70120, 21.25410". */
    fun coord(lat: Double, lon: Double) = String.format(Locale.ROOT, "%.5f, %.5f", lat, lon)

    fun pct(v: Int) = String.format(Locale.getDefault(), "%d %%", v)
}
