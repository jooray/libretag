package app.libretag.ble

import app.libretag.data.BleSightRec
import app.libretag.data.Tracker

/**
 * A console advert of one of our tags, heard by this phone (docs/design/tag-diagnosis.md 4.1):
 * kept as [Tracker.bleSight]. A new salt is a new console window; a window the app did not ask
 * for (no BLE_CONSOLE pending or confirmed in the last 15 min) means the tag restarted within the
 * last 3 minutes nearby, and its time goes to [Tracker.bleBoots]. Pure, unit tested.
 */
object BleBoots {
    const val OWN_WINDOW_MS = 15 * 60_000L
    const val KEEP = 6
    /** a sighting is rewritten at most this often while the same window lasts */
    const val REFRESH_MS = 60_000L

    /** The app asked for this window: a BLE_CONSOLE command pending, or confirmed in the last 15 min. */
    fun ownWindow(t: Tracker, now: Long): Boolean {
        val p = t.pending
        if (p != null && p.kind == "BLE_CONSOLE" && (p.queuedAt == 0L || now - p.queuedAt < 24 * 3600_000L)) return true
        val l = t.lastCommand ?: return false
        return l.kind == "BLE_CONSOLE" && now - l.confirmedAt in 0..OWN_WINDOW_MS
    }

    /** [kind] "console" or "here"; [saltHex] the advert's salt; [asked]: the phone's wake advert asked for this window. The same tracker when nothing changes. */
    fun onAdvert(t: Tracker, kind: String, saltHex: String, rssi: Int, now: Long, asked: Boolean = false): Tracker {
        val prev = t.bleSight
        val newWindow = prev == null || prev.salt != saltHex || prev.kind != kind
        if (!newWindow && now - prev!!.at < REFRESH_MS) return t
        val sight = BleSightRec(now, kind, rssi, salt = saltHex)
        val boots = if (newWindow && kind == "console" && !asked && !ownWindow(t, now)) (t.bleBoots + now).takeLast(KEEP) else t.bleBoots
        return t.copy(bleSight = sight, bleBoots = boots,
            // the tag answered "Check if it's here"
            hereAskAt = if (newWindow) null else t.hereAskAt, notHereAt = if (newWindow) null else t.notHereAt)
    }

    /** "Check if it's here" ran out: not seen by the expected wake-up + 2 min. */
    fun notHere(t: Tracker, now: Long): Boolean {
        val ask = t.hereAskAt ?: return false
        if (now < ask + NOT_HERE_GRACE_MS) return false
        return t.bleSight?.let { it.at >= ask - 5 * 60_000L } != true
    }

    const val NOT_HERE_GRACE_MS = 2 * 60_000L
}
