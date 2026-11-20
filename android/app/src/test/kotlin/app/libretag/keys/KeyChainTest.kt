package app.libretag.keys

import app.libretag.proto.Keys
import app.libretag.proto.LibreTag
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class KeyChainTest {
    private val k0 = ByteArray(32) { (it * 7 + 3).toByte() }
    private val keys = Keys(k0)

    /** a minimal report plaintext (type 0x21, battery 80 %, no fix) */
    private fun pt(): ByteArray = ByteArray(LibreTag.REPORT_PT_LEN).also { it[0] = 0x21; it[3] = 80 }

    @Test fun sharedChainMatchesOwnerFromItsEpoch() {
        val shared = SharedChain(3, keys.view(3), keys.cmd(3))
        for (e in 3L..6L) {
            assertArrayEquals(keys.view(e), shared.view(e))
            assertArrayEquals(keys.cmd(e), shared.cmd(e))
        }
        // the past before the shared epoch stays closed
        assertNull(shared.view(2))
        assertNull(shared.cmd(0))
    }

    @Test fun viewOnlyHasNoCommands() {
        val shared = SharedChain(1, keys.view(1), null)
        assertNull(shared.cmd(1))
        assertEquals(false, shared.canCommand)
    }

    @Test fun windowOpensReportsFromTheSharedEpochOnly() {
        val shared = SharedChain(2, keys.view(2), null)
        val w = TagWindow(shared, 2 * 64 - 1)
        val before = keys.sealReport(100, 0, pt())
        val after = keys.sealReport(130, 0, pt())
        assertNull(w.tryOpen(before))
        val r = w.tryOpen(after)
        assertNotNull(r)
        assertEquals(130L, r!!.first)
        assertEquals(80, r.third.batteryPct)
        // never reopened
        assertNull(w.tryOpen(after))
    }

    @Test fun ownerWindowAndHistoryCopy() {
        val w = TagWindow(OwnerChain(keys), 10)
        val p = keys.sealReport(12, 1, pt())
        assertEquals(12L, w.tryOpen(p)!!.first)
        // history records are sealed as copy j = 2 (and j = 3 for quiet fixes)
        val h = keys.sealReport(5, LibreTag.J_HIST, pt())
        assertNotNull(w.openHistory(5, h))
        val q = keys.sealReport(6, LibreTag.J_HIST_SILENT, pt())
        assertNotNull(w.openHistory(6, q))
        assertNull(w.openHistory(7, h))
    }

    @Test fun approxCopyKeepsTheReportOpenForAnExactOne() {
        val w = TagWindow(OwnerChain(keys), 10)
        val p = keys.sealReport(12, 0, pt())
        val older = keys.sealReport(8, 0, pt())
        // approximate copies (0.8.1) leave n open, a newer one as well as an older one
        assertEquals(12L, w.tryOpen(p, keep = true)!!.first)
        assertEquals(8L, w.tryOpen(older, keep = true)!!.first)
        // then the exact copies open, once
        assertEquals(12L, w.tryOpen(p)!!.first)
        assertEquals(8L, w.tryOpen(older)!!.first)
        assertNull(w.tryOpen(p))
        assertNull(w.tryOpen(older))
    }
}
