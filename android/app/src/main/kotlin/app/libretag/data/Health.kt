package app.libretag.data

import kotlinx.serialization.Serializable

/**
 * The tag's health from `status` over USB or the Bluetooth console (PROTOCOL.md 6, the `txcap`
 * line): counter, resets in a row, the last reset cause, the first-report delay, whether it may
 * transmit, the quiet reason and the schedule it runs.
 */
@Serializable
data class TagHealth(
    val at: Long,
    val n: Long? = null,
    val resets: Int? = null,
    /** pin, power, soft, debug, brownout, watchdog, crash, none */
    val lastReset: String? = null,
    val bootDelayS: Int? = null,
    /** tx=ok (true) or tx=low_voltage (false) */
    val txOk: Boolean? = null,
    /** "none", "zone", "owner", "parked" from the `quiet` command */
    val quiet: String? = null,
    val intervalMin: Int? = null,
    val reportEvery: Int? = null,
) {
    fun fresh(now: Long) = now - at in 0..FRESH_MS
    /** the last reset was a fault, not the owner */
    val faultReset: Boolean get() = lastReset in FAULTS

    companion object {
        const val FRESH_MS = 15 * 60_000L
        val FAULTS = setOf("brownout", "watchdog", "crash", "soft")
    }
}

/** An advert of this tag the phone heard: "console" (its console window) or "here". */
@Serializable
data class BleSightRec(val at: Long, val kind: String, val rssi: Int, val info: Int = 0, val salt: String = "")

/** A boot of the tag: from a BOOT report (cause and resets when it had no fix) or inferred from a counter gap. */
@Serializable
data class BootRec(val at: Long, val n: Long, val cause: String? = null, val resets: Int? = null, val inferred: Boolean = false)
