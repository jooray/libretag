package app.libretag.data

/**
 * Track: how often and how long (SET_MODE carries interval_s and duration_min, PROTOCOL.md 3).
 * The firmware raises an interval below 120 s to 120 s (2.5), takes any duration (0 = 120 min),
 * and stops flooding at 200 floods in a rolling 24 h: above that every interval falls back to
 * hourly. A choice that needs more than [SAFE_24H] reports in a day leaves no room for the rest
 * (the retransmission, BLE packets) and is warned about. Pure, unit tested.
 */
object TrackPlan {
    val INTERVALS_S = Status.TRACK_CHOICES_S
    val DURATIONS_MIN = listOf(30, 60, 120, 240, 480, 720, 1440)
    /** one tap: 1 h every 5 min */
    const val DEFAULT_MIN = 60
    const val MIN_INTERVAL_S = 120
    const val CAP_24H = 200
    const val SAFE_24H = 160

    fun effectiveIntervalS(intervalS: Int) = intervalS.coerceAtLeast(MIN_INTERVAL_S)

    /** reports the whole run sends */
    fun reports(intervalS: Int, durationMin: Int): Int = durationMin * 60 / effectiveIntervalS(intervalS)

    /** reports in the busiest 24 h of the run */
    fun per24h(intervalS: Int, durationMin: Int): Int = minOf(durationMin, 1440) * 60 / effectiveIntervalS(intervalS)

    /** the run would reach the tag's daily limit, after which it reports only hourly */
    fun hitsCap(intervalS: Int, durationMin: Int): Boolean = per24h(intervalS, durationMin) > SAFE_24H

    /** after how many minutes the limit is reached (the rest of the run is hourly), null when it is not */
    fun capAfterMin(intervalS: Int, durationMin: Int): Int? =
        if (!hitsCap(intervalS, durationMin)) null else SAFE_24H * effectiveIntervalS(intervalS) / 60
}
