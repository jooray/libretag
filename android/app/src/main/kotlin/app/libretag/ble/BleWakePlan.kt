package app.libretag.ble

import app.libretag.data.Tracker
import app.libretag.proto.BleConsole

/**
 * The phone waits for a tag to open its console over Bluetooth: it advertises the wake advert
 * ([BleConsole.wakePayload]) from [startedAt] until the tag's console advert is
 * seen ([answeredAt]), the owner cancels, or [untilMs].
 */
data class WakeRequest(val tagId: String, val startedAt: Long, val untilMs: Long, val answeredAt: Long? = null) {
    val waiting: Boolean get() = answeredAt == null
}

/** The rules of the wake advert, pure for the tests. */
object BleWakePlan {
    const val MIN_WAIT_MS = 2 * 3600_000L

    /** After the console advert was seen, the app connects by itself within the tag's window. */
    const val ANSWER_KEEP_MS = BleConsole.WINDOW_S * 1000L

    /** The tag scans at every wake-up and wake-ups can be a report period apart: wait for two of them, 2 h at least. */
    fun waitMs(t: Tracker): Long = maxOf(MIN_WAIT_MS, 2L * t.reportPeriodMin.coerceAtLeast(1) * 60_000L)

    fun request(t: Tracker, now: Long): WakeRequest = WakeRequest(t.id, now, now + waitMs(t))

    enum class Step { KEEP, ANSWERED, TIMED_OUT, DONE }

    /**
     * What happens to [r] now. [seenAt]: when the tag's console advert was last seen (a fresh
     * window), null when never.
     */
    fun step(r: WakeRequest, seenAt: Long?, now: Long): Step = when {
        r.answeredAt != null -> if (now - r.answeredAt > ANSWER_KEEP_MS) Step.DONE else Step.KEEP
        seenAt != null && seenAt >= r.startedAt -> Step.ANSWERED
        now >= r.untilMs -> Step.TIMED_OUT
        else -> Step.KEEP
    }

    /**
     * When the tag should next wake up and scan, for the screen: the earliest future time of
     * [candidates] (the next check or report the app expects, the next projected report wake-up).
     */
    fun nextWakeAt(now: Long, vararg candidates: Long?): Long? = candidates.filterNotNull().filter { it > now }.minOrNull()
}
