package app.libretag.data

import app.libretag.keys.OwnerChain
import app.libretag.keys.TagWindow
import app.libretag.nostr.Announcement
import app.libretag.proto.Keys
import app.libretag.proto.LibreTag
import app.libretag.proto.QuietMesh
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The app's quiet mesh rules (SPEC.md 3.2, the contract's App section): ACK choice, hygiene, silence, notices. */
class QuietPlanTest {
    private val now = 1_800_000_000_000L
    private val min = 60_000L
    private val h = 3600_000L

    private fun pos(n: Long, agoMs: Long, j: Int = 9, via: String = "nostr", fix: Boolean = true, lat: Double = 48.70, lon: Double = 21.25,
                    mode: String = "IDLE", nextInS: Long? = 3 * 3600L, cmdAck: Boolean = false, cmdSeq: Int = 0, lowBatt: Boolean = false,
                    battery: Int? = 80, nodeDirect: Boolean = false, boot: Boolean = false) = Position(
        fixTime = 0, receivedAt = now - agoMs, hasFix = fix, lat = lat, lon = lon, altM = 0, battery = battery, mode = mode,
        hops = null, snrDb = 0.0, via = via, n = n, haccM = 6, nextInS = nextInS, j = j, cmdAck = cmdAck, cmdSeq = cmdSeq,
        lowBatt = lowBatt, nodeDirect = nodeDirect, boot = boot)

    private fun tag(vararg p: Position, ack: AckRec? = null) = Tracker(name = "Car", keyHex = "00", positions = p.toList(),
        lastN = p.maxOf { it.n }, intervalMin = 60, reportEvery = 3, lastAck = ack)

    // ---------------------------------------------------------------- ACK sender

    @Test fun ackChoice() {
        fun c(newest: Boolean = true, approx: Boolean = false, cmd: Boolean = true, j: Int = 9, node: Boolean = false, direct: Boolean = false,
              meta: Boolean = true, zero: Boolean = true, age: Long = 2_000, standing: Boolean = true) =
            QuietPlan.ackChoice(newest, approx, cmd, j, node, direct, meta, zero, age, standing)
        assertEquals(QuietPlan.AckWay.NOSTR, c().way)
        assertEquals(QuietMesh.Form.NEAR, c().form)
        assertEquals(QuietMesh.Form.BOTH, c(j = 0).form)
        assertEquals(QuietMesh.Form.NEAR, c(j = 0, standing = false).form)
        assertEquals(QuietMesh.Form.ROUTED, c(j = 11).form)
        // from the own node only for a copy it heard directly and only with rx_meta, near form, zero-hop
        assertEquals(QuietPlan.AckWay.NODE, c(node = true, direct = true).way)
        assertEquals(QuietMesh.Form.NEAR, c(node = true, direct = true, j = 0).form)
        assertEquals(QuietPlan.AckWay.SKIP, c(node = true, direct = true, meta = false).way)
        assertEquals(QuietPlan.AckWay.SKIP, c(node = true, direct = true, zero = false).way)
        assertEquals(QuietPlan.AckWay.SKIP, c(node = true, direct = false).way) // waits for a relay copy
        assertEquals(QuietPlan.AckWay.SKIP, c(node = true, direct = true, j = 11).way) // never a path from the node
        // late: timed; older, approximate, view-only: none
        assertEquals(QuietPlan.AckWay.TIMED, c(age = 40_000).way)
        assertEquals(QuietPlan.AckWay.SKIP, c(newest = false).way)
        assertEquals(QuietPlan.AckWay.SKIP, c(approx = true).way)
        assertEquals(QuietPlan.AckWay.SKIP, c(cmd = false).way)
    }

    @Test fun more() {
        assertTrue(QuietPlan.more(pending = true, screenOpen = false, notifiedAt = null, now = now))
        assertTrue(QuietPlan.more(false, true, null, now))
        assertTrue(QuietPlan.more(false, false, now - 20_000, now))
        assertFalse(QuietPlan.more(false, false, now - 31_000, now))
    }

    @Test fun timedAcksTwoNearOneRouted() {
        val g = CheckPlan(100, now - 30 * min, 60, listOf(1200L, 4800L, 8400L))
        val g2 = CheckPlan(101, now - 10 * min, 60, listOf(3600L, 7200L))
        val near = QuietPlan.timedAckSlots(listOf(g, g2), QuietMesh.Form.NEAR, now)
        assertEquals(listOf(100L to 2, 100L to 3, 101L to 1, 101L to 2), near.map { it.first.n to it.second })
        assertEquals(listOf(100L to 2, 101L to 1), QuietPlan.timedAckSlots(listOf(g, g2), QuietMesh.Form.ROUTED, now).map { it.first.n to it.second })
    }

    // ---------------------------------------------------------------- hygiene (P12)

    /** R5: an old report with a matching number does not confirm. */
    @Test fun oldReportDoesNotConfirm() {
        val p = PendingCommand(0x35, "beep", "", kind = "BEEP", queuedAtN = 40)
        assertFalse(QuietPlan.confirms(p, 40, newest = true, cmdAck = true, cmdSeq = 5, mode = "IDLE"))   // queued after it
        assertFalse(QuietPlan.confirms(p, 39, newest = false, cmdAck = true, cmdSeq = 5, mode = "IDLE"))  // late copy
        assertTrue(QuietPlan.confirms(p, 41, newest = true, cmdAck = true, cmdSeq = 5, mode = "IDLE"))
        assertFalse(QuietPlan.confirms(p, 41, newest = true, cmdAck = true, cmdSeq = 6, mode = "IDLE"))
        // Lost, Track and Normal also need the mode asked for
        val lost = p.copy(kind = "LOST")
        assertFalse(QuietPlan.confirms(lost, 41, true, true, 5, "IDLE"))
        assertTrue(QuietPlan.confirms(lost, 41, true, true, 5, "LOST"))
        assertFalse(QuietPlan.confirms(p.copy(kind = "NORMAL"), 41, true, true, 5, "LOST"))
    }

    /** R6: a restored device does not reuse the tag's last cmd_seq (nor its own previous one). */
    @Test fun restoredDeviceDoesNotReuseTheTagsSeq() {
        val r = java.util.Random(7)
        repeat(500) {
            // a restored phone counts from 0; the tag's newest report says it last ran seq ...0x1
            val s = QuietPlan.pickSeq(tagLow = 1, prevSeq = 0) { r.nextInt(it) }
            assertTrue(s in 1..255)
            assertNotEquals(1, s and 0x0F)
            val s2 = QuietPlan.pickSeq(tagLow = 1, prevSeq = s) { r.nextInt(it) }
            assertNotEquals(s and 0x0F, s2 and 0x0F)
        }
        // a degenerate random source still ends with a valid choice
        assertNotEquals(1, QuietPlan.pickSeq(1, 0) { 0 } and 0x0F)
    }

    /** R7: an expired Beep posts one notice: expiry is a lifetime check, the notice latch is the command's seq. */
    @Test fun expiredBeepEndsOnce() {
        val p = PendingCommand(9, "beep", "", kind = "BEEP", queuedAt = now - 2 * h, expiresAt = now - h)
        assertTrue(QuietPlan.expired(p, now))
        assertFalse(QuietPlan.expired(p.copy(expiresAt = now + 1), now))
        assertFalse(QuietPlan.expired(p.copy(expiresAt = 0), now))
        assertEquals(3600L, app.libretag.CommandKind.BEEP.mailboxTtlS)
        assertEquals(4 * 3600L, app.libretag.CommandKind.BLE_CONSOLE.mailboxTtlS)
        assertEquals(48 * 3600L, app.libretag.CommandKind.LOST.mailboxTtlS)
        assertEquals(12 * 3600L, app.libretag.CommandKind.BLE_SCAN.mailboxTtlS)
    }

    /** P8: a relay copy then a node copy: the second is a second sighting, offered once; the first never opens again. */
    @Test fun secondSightingOnce() {
        val keys = Keys(ByteArray(32) { it.toByte() })
        val pt = ByteArray(33).also { it[0] = 0x21 }
        val n = 300L
        val relayCopy = keys.sealReport(n, 0, pt)
        val nodeCopy = keys.sealReport(n, QuietMesh.J_NEAR, pt)
        var clock = now
        val w = TagWindow(OwnerChain(keys), n - 1).also { it.now = { clock } }
        assertEquals(n to 0, w.tryOpen(relayCopy)?.let { it.first to it.second })
        assertNull(w.tryOpen(nodeCopy))          // not a new report
        assertNull(w.tryOpen(relayCopy))
        assertEquals(n to QuietMesh.J_NEAR, w.trySecond(nodeCopy)?.let { it.first to it.second })
        clock += TagWindow.SECOND_MS + 1
        assertNull(w.trySecond(nodeCopy))        // only within 120 s
    }

    // ---------------------------------------------------------------- anchor in the app

    @Test fun anchorKeepsGridsAndVias() {
        var built = 0
        val grid = { n: Long, t0: Long -> built++; CheckPlan(n, t0, 60, listOf(3600L)) }
        var a = QuietPlan.anchorAfter(AnchorState(), 100, 0, 0, sync = false, liveAck = false, still = true, dueInGap = false, x = "aa", grid = grid)
        assertEquals(100L, a.a?.n); assertEquals(listOf("aa"), a.a?.vias)
        a = QuietPlan.anchorAfter(a, 101, 9, 3 * h, false, liveAck = true, still = true, dueInGap = false, x = "bb", grid = grid)
        assertEquals(listOf(101L), a.cands.map { it.n })
        a = QuietPlan.anchorAfter(a, 102, 9, 6 * h, sync = true, liveAck = true, still = true, dueInGap = false, x = "cc", grid = grid)
        assertEquals(101L, a.a?.n); assertEquals(listOf("bb"), a.a?.vias)
        assertEquals(3, built) // the existing grid of 101 was kept, not built again
        assertEquals(101L, QuietPlan.confirmed(a, 101).a?.n)
    }

    @Test fun liveAckOnTheSecondSightingAddsTheCandidateAtTheFirstCopy() {
        val grid = { n: Long, t0: Long -> CheckPlan(n, t0, 60, listOf(3600L)) }
        var a = QuietPlan.anchorAfter(AnchorState(), 100, 0, 0, sync = false, liveAck = false, still = true, dueInGap = false, x = "aa", grid = grid)
        // hello 101 from the node without rx_meta: no ACK, no candidate
        a = QuietPlan.anchorAfter(a, 101, 9, 3 * h, false, liveAck = false, still = true, dueInGap = false, x = "bb", grid = grid)
        assertTrue(a.cands.isEmpty())
        // its relay copy 2 s later gets the live ACK entry: the tag anchors on the hello's time
        a = QuietPlan.liveAckOnSecond(a, 101, 9, 3 * h + 2_000, sync = false, grid = grid)
        assertEquals(listOf(101L to 3 * h), a.cands.map { it.n to it.t0 })
        assertEquals(100L, a.a?.n)
        // an older report changes nothing
        assertEquals(a, QuietPlan.liveAckOnSecond(a, 99, 9, 4 * h, sync = false, grid = grid))
    }

    @Test fun stillNeedsTwoFixes() {
        val a = pos(1, h, lat = 48.7000)
        assertTrue(QuietPlan.still(a, pos(2, 0, lat = 48.7010)))           // 111 m
        assertFalse(QuietPlan.still(a, pos(2, 0, lat = 48.7100)))          // 1.1 km
        assertFalse(QuietPlan.still(a, pos(2, 0, fix = false)))
        assertFalse(QuietPlan.still(a, pos(2, 0, boot = true)))
        assertFalse(QuietPlan.still(null, pos(2, 0)))
    }

    // ---------------------------------------------------------------- silence by link (4.4, 9.3)

    @Test fun linkFromTheLastCopy() {
        assertEquals(QuietPlan.Link.NEAR_BRIDGE, QuietPlan.link(tag(pos(5, 0, j = 9))))
        assertEquals(QuietPlan.Link.NEAR_NODE, QuietPlan.link(tag(pos(5, 0, j = 9, via = "mesh", nodeDirect = true))))
        assertEquals(QuietPlan.Link.ROUTED, QuietPlan.link(tag(pos(5, 0, j = 11))))
        assertEquals(QuietPlan.Link.FAR, QuietPlan.link(tag(pos(5, 0, j = 0))))
        assertEquals(QuietPlan.Link.NEAR_BRIDGE, QuietPlan.link(tag(pos(5, 0, j = 0), ack = AckRec(5, now, 4, "both", "nostr"))))
        assertEquals(QuietPlan.Link.UNKNOWN, QuietPlan.link(tag(pos(5, 0, j = -1))))
    }

    @Test fun nearTagWaitsForHold() {
        // a hello 4 h ago, ACKed: hold 12 h, period 3 h -> silent only after t_ack + 12 h + 3 h + 30 min
        val p = pos(5, 4 * h, j = 9)
        val t = tag(p, ack = AckRec(5, now - 4 * h, 4, "near", "nostr"))
        val base = now - h // the plain schedule would say silent an hour ago
        assertEquals(now - 4 * h + 15 * h + 30 * min, QuietPlan.silentDeadline(t, base, now))
        assertEquals(StatusKind.OK, Status.of(t.copy(alerts = t.alerts), now).kind.let { if (it == StatusKind.LOW_BATTERY) StatusKind.OK else it })
        // hold 0 (guarded): the plain deadline
        assertEquals(base, QuietPlan.silentDeadline(t.copy(lastAck = AckRec(5, now - 4 * h, 0, "near", "nostr")), base, now))
    }

    @Test fun farAndRoutedAndViewOnly() {
        val far = tag(pos(5, h, j = 0))
        assertEquals(1000L, QuietPlan.silentDeadline(far, 1000L, now))
        val routed = tag(pos(5, h, j = 11))
        assertEquals(1000L + 60 * min, QuietPlan.silentDeadline(routed, 1000L, now))
        val view = tag(pos(5, h, j = 9, nextInS = 3600)).copy(sharedBy = "x", sharedControl = false)
        assertEquals(now - h + 3600_000L + 24 * h, QuietPlan.silentDeadline(view, 1000L, now))
    }

    /** R8: an out-of-order report does not clear the silent latch (handleReport keeps it unless the report is new). */
    @Test fun outOfOrderReportKeepsTheLatch() {
        val t = tag(pos(10, 5 * h, j = 0, nextInS = 3600)).copy(silentAlerted = true)
        assertEquals(StatusKind.SILENT, Status.of(t, now).kind)
        // an older report merged into the history changes neither the newest report nor the status
        val old = pos(8, 10 * h, j = 0)
        val merged = t.copy(positions = ReportMerge.merge(t.positions, old, 100))
        assertEquals(10L, merged.last?.n)
        assertEquals(StatusKind.SILENT, Status.of(merged, now).kind)
    }

    // ---------------------------------------------------------------- notices (A3)

    @Test fun reportsLimited() {
        val floods = (1..8).map { pos(it.toLong(), (9 - it) * h, j = 0, nextInS = 6 * 3600L) }
        val t = tag(*floods.toTypedArray())
        val until = QuietPlan.limitedUntil(t, now)
        assertEquals(floods.last().receivedAt + 6 * h, until)
        // seven floods, or command answers among them: not limited
        assertNull(QuietPlan.limitedUntil(tag(*floods.drop(1).toTypedArray()), now))
        assertNull(QuietPlan.limitedUntil(tag(*(floods.dropLast(1) + pos(8, h, j = 0, cmdAck = true, nextInS = 6 * 3600L)).toTypedArray()), now))
    }

    @Test fun modeEndedOnce() {
        val p = pos(5, 2 * h, mode = "LOST", nextInS = 600)
        val t = tag(p).copy(modeUntil = now - min)
        assertEquals(now - min, QuietPlan.modeEnded(t, now))
        assertNull(QuietPlan.modeEnded(t.copy(modeEndNotified = now - min), now))
        assertNull(QuietPlan.modeEnded(t.copy(modeUntil = now + min), now))
        // after the end the next report is due on the IDLE schedule, not 10 min after the last Lost report
        assertEquals(now - min + 3 * h, Status.nextAt(t, now))
    }

    @Test fun noAnswers() {
        assertTrue(QuietPlan.noAnswers(tag(pos(5, 0)).copy(acksNoNote = 2)))
        assertFalse(QuietPlan.noAnswers(tag(pos(5, 0)).copy(acksNoNote = 1)))
        // floods from the same place after an ACK: the downlink is broken
        val r = (6..8).map { pos(it.toLong(), (9 - it) * h, j = 0) }
        assertTrue(QuietPlan.noAnswers(tag(*r.toTypedArray(), ack = AckRec(8, now - h, 4, "both", "nostr"))))
        assertFalse(QuietPlan.noAnswers(tag(*r.toTypedArray(), ack = AckRec(5, now - 5 * h, 4, "near", "nostr"))))
        assertFalse(QuietPlan.noAnswers(tag(*r.toTypedArray())))
        assertFalse(QuietPlan.noAnswers(tag(pos(5, 0)).copy(acksNoNote = 3, sharedBy = "x", sharedControl = false)))
    }

    @Test fun batteryProbablyEmpty() {
        val t = tag(pos(5, 30 * h, lowBatt = true, battery = 9, nextInS = 3600))
        assertEquals(now - 30 * h, QuietPlan.batteryEmptySince(t))
        val d = Diagnosis.of(t, Status.of(t, now), DiagContext(), now)
        assertEquals(Diagnosis.Cause.BATTERY_EMPTY, d?.cause)
        assertNull(QuietPlan.batteryEmptySince(tag(pos(5, 30 * h, battery = 60))))
    }

    @Test fun nodeOff() {
        val p = pos(5, 4 * h, via = "mesh", nodeDirect = true, nextInS = 3 * 3600L)
        val t = tag(p, ack = AckRec(5, now - 4 * h, 4, "near", "node"))
        assertEquals(now - 4 * h + 12 * h, QuietPlan.nodeOffBy(t, nodeDown = true, now = now))
        assertNull(QuietPlan.nodeOffBy(t, nodeDown = false, now = now))
        assertNull(QuietPlan.nodeOffBy(tag(p.copy(via = "nostr")), nodeDown = true, now = now))
        val d = Diagnosis.of(t, Status.of(t, now), DiagContext(mesh = MeshLink.DOWN, meshUpAt = now - 5 * h), now)
        assertEquals(Diagnosis.Cause.NODE_DOWN, d?.cause)
        assertEquals(now - 4 * h + 12 * h, d?.facts?.deadline)
    }

    @Test fun bridgeOffline() {
        val nowS = now / 1000
        val fresh = Announcement("ab".repeat(32), "sk", true, true, nowS + 86400, createdAt = nowS - 600, radioUp = true, radioSince = nowS - 9000)
        assertNull(fresh.offlineSince(nowS))
        assertEquals(nowS - 9000, fresh.copy(radioUp = false).offlineSince(nowS))
        assertEquals(nowS - 3 * 3600, fresh.copy(createdAt = nowS - 3 * 3600).offlineSince(nowS))
        // the diagnosis names the bridge before it raises "silent"
        val t = tag(pos(5, 8 * h, j = 0, nextInS = 3600))
        val c = DiagContext(bridgeDown = BridgeDownSeen(now - 7 * h, "Bridge A", radioDown = true))
        val d = Diagnosis.of(t, Status.of(t, now), c, now)
        assertEquals(Diagnosis.Cause.BRIDGE_OFFLINE, d?.cause)
        assertEquals(Diagnosis.Level.NOTE, d?.level)
        assertEquals(SilentNotify.Out.NONE, SilentNotify.decide(false, true, Status.of(t, now), d))
    }

    /** W: next_in says the period only for a hello or the first flood after a move; backoff floods are expected. */
    @Test fun scheduleMismatchOnlyForHellos() {
        val flood = tag(pos(4, 30 * h, j = 0, nextInS = 6 * 3600L), pos(5, 26 * h, j = 0, nextInS = 12 * 3600L))
        val d = Diagnosis.of(flood, Status.of(flood, now), DiagContext(), now)
        assertNotEquals(Diagnosis.Cause.SCHEDULE_MISMATCH, d?.cause)
        val hello = tag(pos(5, 30 * h, j = 9, nextInS = 6 * 3600L))
        assertEquals(Diagnosis.Cause.SCHEDULE_MISMATCH, Diagnosis.of(hello, Status.of(hello, now), DiagContext(), now)?.cause)
    }

    @Test fun dueInGap() {
        assertTrue(QuietPlan.dueInGap(25 * h, 4, 3 * 3600))
        assertTrue(QuietPlan.dueInGap(13 * h, 4, 3 * 3600))
        assertFalse(QuietPlan.dueInGap(6 * h, 4, 3 * 3600))
        assertNotNull(QuietPlan.slotCount(60).takeIf { it >= 50 })
        assertEquals(LibreTag.TAG_LEN, 4)
    }
}
