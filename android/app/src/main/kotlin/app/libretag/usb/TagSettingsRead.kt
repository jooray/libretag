package app.libretag.usb

import app.libretag.data.Tracker

/**
 * The tag's own settings, read from `status` (and `version`) over USB, are the app's truth for it:
 * the interval, report_every, check, parked quiet and the heartbeats. Fields an older firmware
 * doesn't print keep the app's value. Pure, so it is unit tested; nothing is written to the tag.
 */
object TagSettingsRead {
    /** [t] with what the tag printed; equal to [t] when nothing differs. */
    fun adopt(t: Tracker, s: TrackerStatus, version: String?): Tracker = t.copy(
        intervalMin = s.intervalMin?.takeIf { it > 0 } ?: t.intervalMin,
        reportEvery = s.reportEvery?.takeIf { it > 0 } ?: t.reportEvery,
        checkS = s.checkS ?: t.checkS,
        stillAfter = s.stillAfter ?: t.stillAfter,
        stillHbH = s.stillHbH?.takeIf { it > 0 } ?: t.stillHbH,
        zoneHbH = s.zoneHbH?.takeIf { it > 0 } ?: t.zoneHbH,
        ownerHbH = s.ownerHbH?.takeIf { it > 0 } ?: t.ownerHbH,
        firmware = version ?: t.firmware,
    )

    /** The settings differ (firmware alone doesn't count as "settings read"). */
    fun settingsDiffer(a: Tracker, b: Tracker): Boolean =
        a.intervalMin != b.intervalMin || a.reportEvery != b.reportEvery || a.checkS != b.checkS ||
            a.stillAfter != b.stillAfter || a.stillHbH != b.stillHbH || a.zoneHbH != b.zoneHbH || a.ownerHbH != b.ownerHbH
}
