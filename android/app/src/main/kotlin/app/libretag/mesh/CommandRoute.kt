package app.libretag.mesh

import app.libretag.CommandKind
import app.libretag.data.CheckPlan

/**
 * Which ways a queued command goes (PROTOCOL.md 3, 3.1, 4). Pure, so the rules are unit-tested.
 *
 * Every command but one keeps the old routes: the phone's Bluetooth advert when the tag takes it,
 * the owner's own node (MeshCore Open's Local API, a flood scoped to the region, or zero-hop) and
 * the Nostr mailbox, all at once, whichever reaches the tag first.
 *
 * Opening Bluetooth ([CommandKind.BLE_CONSOLE]) is for a tag next to the owner, so a local send must
 * not flood the mesh. In this order:
 *  1. [Via.BLE]: this phone's command advert, no mesh at all, when the tag takes adverts, the phone
 *     is near it (it is one of the tag's known devices, or the last report was quiet because of one)
 *     (every wake-up scans Bluetooth, a listen-only one too);
 *  2. [Via.ZERO_HOP]: the owner's node, zero-hop, when the Local API server can send one
 *     (`tx_path_data`; MeshCore Open cannot yet, meshcored can). Never a flood from our radio;
 *  3. [Via.NOSTR]: the signed mailbox, where bridges flood or zero-hop as usual.
 * A local way that reaches the tag at its next listening wake-up wins and nothing goes to Nostr.
 * Otherwise the mailbox carries it, and the local ways that are there run alongside (they cost the
 * mesh nothing). When a local way has not delivered it by the wake-up it was meant for (the phone
 * was not near after all), [mailboxFallback] hands it to the mailbox at the next report.
 */
object CommandRoute {
    /** What our own radio does with the command. */
    enum class Local { FLOOD, ZERO_HOP, NONE }

    /** The way the app expects the command to arrive by (what the screen says). */
    enum class Via { BLE, ZERO_HOP, NOSTR }

    data class Plan(
        /** this phone advertises it (when the tag can take it; [app.libretag.ble.BleBeaconPlan] decides the sealing) */
        val beacon: Boolean,
        val local: Local,
        /** Nostr mailbox entries are published */
        val mailbox: Boolean,
        val via: Via,
        /** when the tag should take it (ms), null when unknown */
        val at: Long?,
    )

    /** The command must never be flooded from our own radio. */
    fun quietLocally(kind: CommandKind?): Boolean = kind == CommandKind.BLE_CONSOLE

    /** What our radio may do with a command of [kind]: never a flood for a quiet one. */
    fun local(kind: CommandKind?, zeroHopCapable: Boolean): Local = when {
        !quietLocally(kind) -> Local.FLOOD
        zeroHopCapable -> Local.ZERO_HOP
        else -> Local.NONE
    }

    /**
     * [beaconOk]: the tag takes command adverts (it has a report counter) and this phone can advertise.
     * [zeroHopOk]: MeshCore Open (or another Local API server) is connected and sends zero-hop.
     * [nextWindowAt]: the tag's next listening wake-up (a check, or the next report).
     * [nextScanAt]: its next wake-up that also scans Bluetooth (see [nextScanAt]).
     * [near]: the phone is expected next to the tag ([isNear]); without it the advert still runs,
     * but the command does not count on it.
     */
    fun plan(kind: CommandKind?, beaconOk: Boolean, zeroHopOk: Boolean, nextWindowAt: Long?, nextScanAt: Long?,
             near: Boolean = true): Plan {
        if (!quietLocally(kind)) return Plan(beaconOk, Local.FLOOD, mailbox = true, via = Via.NOSTR, at = nextWindowAt)
        val local = local(kind, zeroHopOk)
        val bleNext = beaconOk && near && nextScanAt != null &&
            (nextWindowAt == null || nextScanAt <= nextWindowAt + SAME_WAKE_MS)
        return when {
            bleNext -> Plan(true, local, mailbox = false, via = Via.BLE, at = nextScanAt)
            zeroHopOk -> Plan(beaconOk, local, mailbox = false, via = Via.ZERO_HOP, at = nextWindowAt)
            else -> Plan(beaconOk, local, mailbox = true, via = Via.NOSTR, at = nextWindowAt)
        }
    }

    /**
     * The next wake-up that scans Bluetooth for the command advert: every wake-up does, a
     * listen-only one between reports too (a 1.5 s scan alongside the LoRa window), so the next
     * check of the plan, else the next report ([nextReportAt]).
     */
    fun nextScanAt(plan: CheckPlan?, nextReportAt: Long?, now: Long): Long? {
        plan?.offsetsS?.forEach { off ->
            val at = plan.t0 + off * 1000
            if (at > now) return at
        }
        return nextReportAt?.takeIf { it > now } ?: nextReportAt
    }

    /**
     * "Near" as the app can tell it (P10): this phone saw the tag over Bluetooth in the last 15
     * minutes, its last report was quiet because one of the owner's devices was there, or the last
     * report was ACKed through the own node (it heard the tag directly).
     */
    fun isNear(bleSightAt: Long?, lastQuietReason: String?, ackedByNode: Boolean, now: Long): Boolean =
        (bleSightAt != null && now - bleSightAt in 0..NEAR_SIGHT_MS) || lastQuietReason == "owner" || ackedByNode

    const val NEAR_SIGHT_MS = 15 * 60_000L

    /**
     * A command that went only by local ways ([Plan.mailbox] false) and is still not acknowledged
     * at a report received [reportAtMs], well after the wake-up it was expected at ([expectAt]):
     * the phone was not near or the zero-hop was not heard. The mailbox carries it from then on.
     */
    fun mailboxFallback(mailbox: Boolean, via: String, expectAt: Long, reportAtMs: Long): Boolean =
        !mailbox && (via == Via.BLE.name || via == Via.ZERO_HOP.name) && expectAt > 0 && reportAtMs > expectAt + SAME_WAKE_MS

    /** a check and a report this close are the same wake-up (clock guards, flood delay) */
    const val SAME_WAKE_MS = 60_000L
}
