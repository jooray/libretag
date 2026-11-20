package app.libretag.proto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 0.7 additions (docs/design/checks-0.7.md): report flags, check schedule, commands at check k. */
class Checks07Test {
    private val keys = Keys(ByteArray(32) { it.toByte() })

    private fun pt(flags: Int, modeB: Int) = ByteArray(LibreTag.REPORT_PT_LEN).also {
        it[0] = LibreTag.TYPE_REPORT.toByte(); it[1] = flags.toByte(); it[2] = modeB.toByte(); it[3] = 80
    }

    @Test fun decodesChecksAndParked() {
        // QUIET + CHECKS, mode IDLE, parked bit, cmd_seq 5
        val r = Report.decode(pt(LibreTag.F_QUIET or LibreTag.F_CHECKS, (5 shl 4) or LibreTag.M_PARKED))!!
        assertTrue(r.checks)
        assertTrue(r.parked)
        assertEquals(Mode.IDLE, r.mode)
        assertEquals(5, r.cmdSeq)
        assertEquals("parked", r.quietReason)
        assertEquals("parked", ReportExtras.of(r).quietReason)
        assertTrue(ReportExtras.of(r).checks)
    }

    @Test fun parkedBitDoesNotChangeTheMode() {
        val r = Report.decode(pt(0, Mode.LOST.ordinal or LibreTag.M_PARKED))!!
        assertEquals(Mode.LOST, r.mode)
        assertNull(r.quietReason)
    }

    @Test fun reasonsKeep06Meaning() {
        assertEquals("zone", Report.decode(pt(LibreTag.F_QUIET, 0))!!.quietReason)
        assertEquals("owner", Report.decode(pt(LibreTag.F_QUIET or LibreTag.F_QUIET_OWNER, 0))!!.quietReason)
        // owner wins over the parked bit (the tracker never sets both)
        assertEquals("owner", Report.decode(pt(LibreTag.F_QUIET or LibreTag.F_QUIET_OWNER, LibreTag.M_PARKED))!!.quietReason)
        val r06 = Report.decode(pt(LibreTag.F_QUIET, Mode.TRACK.ordinal))!!
        assertFalse(r06.checks)
        assertFalse(r06.parked)
        assertEquals(Mode.TRACK, r06.mode)
    }

    @Test fun jitterIsWithinTenPercent() {
        val ce = keys.cmd(1)
        for (i in 1..200) {
            val j = LibreTag.checkJitter(ce, 70, i, 3600)
            assertTrue("jitter $j", j >= -360 && j < 360)
        }
        assertEquals(0, LibreTag.checkJitter(ce, 70, 1, 4))
    }

    @Test fun scheduleIsKeyedAndStopsBeforeTheHeartbeat() {
        val ce = keys.cmd(1)
        val a = LibreTag.checkOffsets(ce, 70, 60, 8 * 3600)
        assertEquals(a, LibreTag.checkOffsets(ce, 70, 60, 8 * 3600))
        assertNotEquals(a, LibreTag.checkOffsets(ce, 71, 60, 8 * 3600))
        assertTrue(a.all { it < 8 * 3600 })
        assertTrue(a.size in 7..8)
        // t_k = t_{k-1} + base + jit_k
        var prev = 0L
        a.forEachIndexed { i, t ->
            assertEquals(3600 + LibreTag.checkJitter(ce, 70, i + 1, 3600), t - prev)
            prev = t
        }
        assertEquals(LibreTag.checkOffsets(ce, 70, 60, 24 * 3600).take(a.size), a)
        assertTrue(LibreTag.checkOffsets(ce, 70, 15, 24 * 3600, max = 12).size == 12)
    }

    @Test fun commandAtCheckK() {
        val ce = keys.cmd(1)
        val cmd = Command.buzz(9, 10)
        // k = 0 is the 0.6 command
        assertArrayEquals(LibreTag.sealCommand(ce, 70, cmd), LibreTag.sealCommand(ce, 70, cmd, 0))
        val p1 = LibreTag.sealCommand(ce, 70, cmd, 1)
        val p3 = LibreTag.sealCommand(ce, 70, cmd, 3)
        assertEquals(LibreTag.CMD_P_LEN, p1.size)
        assertFalse(p1.copyOf(4).contentEquals(p3.copyOf(4)))
        assertArrayEquals(LibreTag.commandTag(ce, 70, 3), p3.copyOf(4))
        assertArrayEquals(cmd.encode(), LibreTag.openCommand(ce, 70, p3, 3)!!.encode())
        assertNull(LibreTag.openCommand(ce, 70, p3, 1))
        assertNull(LibreTag.openCommand(ce, 70, p3, 0))
    }

    @Test fun setIdleCarriesCheckSeconds() {
        val c = Command.setIdle(3, 120, 0, 20)
        val pt = c.encode()
        assertEquals(120, (pt[3].toInt() and 0xFF) or ((pt[4].toInt() and 0xFF) shl 8))
        assertEquals(20, pt[7].toInt())
        assertEquals(120, Command.idleIntervalMin(Command.decode(pt)!!))
        assertEquals(300, Command.modeIntervalS(Command.setMode(1, Mode.TRACK, 300, 60)))
        assertNull(Command.modeIntervalS(Command.buzz(1, 3)))
        assertEquals(0xFF, Command.setIdle(1, 0, 0, Command.CHECK_OFF).encode()[7].toInt() and 0xFF)
    }
}
