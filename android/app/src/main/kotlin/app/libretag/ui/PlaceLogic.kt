package app.libretag.ui

import app.libretag.data.Places
import app.libretag.data.Tracker
import app.libretag.data.Zone
import app.libretag.usb.SnapResult
import app.libretag.usb.WifiTestScan
import app.libretag.usb.WifiThresholds
import app.libretag.usb.WifiVerdict
import app.libretag.usb.WifiZoneParsers
import app.libretag.usb.WriteResult

/** The state chip of one place in the Places list (ui-review 2.4). */
enum class PlaceChip { ON_TAG, WAITS_CABLE, WRITING, NOT_WRITTEN, APP_ONLY }

/** The three steps of a Wi-Fi snap, in the order the sheet shows them. */
enum class SnapPhase { LISTEN, SAVE, CHECK }

/** What a snap ended with. */
enum class SnapOutcome {
    /** saved, and the check scan recognises the place */
    OK,
    /** saved, but the check scan scores it weak or outside */
    WEAK,
    /** saved, the check scan did not answer */
    UNCHECKED,
    /** no network heard often enough: nothing saved */
    NONE,
    /** the tag did not answer, or refused */
    NO_ANSWER,
}

/** Places: chips, edits and the snap flow, without Android (unit tested in PlaceLogicTest). */
object PlaceLogic {
    /**
     * GPS places go to the tag as one list, so they share one state. [writing]: a write runs now;
     * [last]: how the last write on this cable connection ended (null: none yet).
     */
    fun gpsChip(onTag: Boolean, onCable: Boolean, writing: Boolean, last: WriteResult?): PlaceChip = when {
        writing -> PlaceChip.WRITING
        onTag -> PlaceChip.ON_TAG
        last == WriteResult.UNSUPPORTED -> PlaceChip.APP_ONLY
        !onCable -> PlaceChip.WAITS_CABLE
        else -> PlaceChip.NOT_WRITTEN
    }

    /** A write should start by itself: on the cable, not on the tag, nothing running, nothing failed on this connection. */
    fun autoWrite(onTag: Boolean, onCable: Boolean, writing: Boolean, last: WriteResult?, hasPlaces: Boolean): Boolean =
        onCable && !onTag && !writing && last == null && hasPlaces

    /** A name a place can take: trimmed, not blank, not used by another place (case ignored). Null otherwise. */
    fun cleanName(wanted: String, taken: Collection<String>, self: String? = null): String? {
        val n = wanted.trim().replace(Regex("\\s+"), " ")
        if (n.isEmpty()) return null
        if (taken.any { it.equals(n, ignoreCase = true) && !it.equals(self, ignoreCase = false) }) return null
        return n
    }

    /** All place names of a tag, GPS and Wi-Fi. */
    fun names(t: Tracker): List<String> = t.zones.map { it.name } + t.wifiZones.map { it.name }

    /** The first place gets [home] as its name, later ones start empty. */
    fun suggestName(home: String, taken: Collection<String>): String =
        if (taken.none { it.equals(home, ignoreCase = true) }) home else ""

    /**
     * Renames a GPS place. Only the app knows the name (the tag keeps centre and radius), so the
     * tag's copy stays valid; a recent departure from it keeps pointing at the place.
     */
    fun rename(t: Tracker, old: String, new: String): Tracker = t.copy(
        zones = t.zones.map { if (it.name == old) it.copy(name = new) else it },
        leftPlace = if (t.leftPlace == old) new else t.leftPlace,
    )

    /** New size or position of a GPS place: the tag's copy is stale until written. */
    fun reshape(t: Tracker, name: String, lat: Double, lon: Double, radiusM: Int): Tracker = t.copy(
        zones = t.zones.map { if (it.name == name) it.copy(lat = lat, lon = lon, radiusM = radiusM) else it },
        zonesOnTag = false,
    )

    fun add(t: Tracker, z: Zone): Tracker = t.copy(zones = t.zones + z, zonesOnTag = false)

    fun delete(t: Tracker, name: String): Tracker = t.copy(zones = t.zones.filter { it.name != name }, zonesOnTag = false)

    /** The leave or arrive choice of a place (GPS or Wi-Fi), stored explicitly from now on. */
    fun setNotify(t: Tracker, name: String, leave: Boolean, on: Boolean): Tracker = t.copy(
        zones = t.zones.map { z -> if (z.name != name) z else if (leave) z.copy(notifyLeave = on) else z.copy(notifyArrive = on) },
        wifiZones = t.wifiZones.map { z -> if (z.name != name) z else if (leave) z.copy(notifyLeave = on) else z.copy(notifyArrive = on) },
    )

    /** The value a switch shows: the place's own choice, else the type default. */
    fun effective(choice: Boolean?, type: String): Boolean = choice ?: Places.defaultNotify(type)

    /** The step a snap is in: [scansDone] of [scans] heard; [saved]: the tag answered the snap; [checking]: the test scan runs. */
    fun snapPhase(scansDone: Int, scans: Int, checking: Boolean): SnapPhase = when {
        checking -> SnapPhase.CHECK
        scansDone >= scans -> SnapPhase.SAVE
        else -> SnapPhase.LISTEN
    }

    /** "2 of 5": the scan running now, never past the last. */
    fun scanShown(scansDone: Int, scans: Int): Int = (scansDone + 1).coerceIn(1, scans)

    /** Seconds a whole snap takes, for "About 30 s": about 5 s per scan plus the check scan. */
    fun snapSeconds(scans: Int): Int = (scans + 1) * 5

    fun snapOutcome(snap: SnapResult?, test: List<WifiTestScan>?, thr: WifiThresholds = WifiThresholds()): SnapOutcome {
        val z = snap?.zone
        if (z == null) return if (snap?.error?.contains("no access point") == true) SnapOutcome.NONE else SnapOutcome.NO_ANSWER
        val score = test?.lastOrNull()?.scores?.firstOrNull { it.name == z.name } ?: return SnapOutcome.UNCHECKED
        return if (WifiZoneParsers.verdict(score, thr) == WifiVerdict.INSIDE) SnapOutcome.OK else SnapOutcome.WEAK
    }

    /** "Check here" for one place: its verdict in the last test scan, null when the tag did not answer. */
    fun checkVerdict(name: String, test: List<WifiTestScan>?, thr: WifiThresholds = WifiThresholds()): WifiVerdict? {
        val scan = test?.lastOrNull() ?: return null
        return WifiZoneParsers.verdict(scan.scores.firstOrNull { it.name == name }, thr)
    }
}

/** The Add tag wizard: four steps (ui-review 2.7). */
object WizardSteps {
    const val PLUG = 0
    const val NAME = 1
    const val REST = 2
    const val DONE = 3
    const val COUNT = 4

    /** Back and the "stop pairing?" question while the user is inside the flow. */
    fun inFlow(step: Int) = step == NAME || step == REST

    /** The footer button of the place step: Skip until a place exists, then Pair. */
    fun pairsNow(step: Int, places: Int) = step == REST && places > 0
}
