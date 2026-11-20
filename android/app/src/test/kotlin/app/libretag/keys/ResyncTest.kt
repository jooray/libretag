package app.libretag.keys

import app.libretag.proto.Keys
import app.libretag.proto.LibreTag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** Window resync (0.8.0): a tag that ran past the 2048-report window is found by searching forward in blocks. */
class ResyncTest {
    private val keys = Keys(ByteArray(32) { (it * 7 + 3).toByte() })
    private val chain = OwnerChain(keys)
    private fun tag(n: Long, j: Int) = LibreTag.bytesToHex(LibreTag.reportTag(keys.view(LibreTag.epoch(n)), n, j))

    @Test fun findsATagFarAhead() {
        val w = TagWindow(chain, 100)
        val target = 100L + 2048 + 9000
        val x = tag(target, 1)
        val hit = w.searchForward({ it == x })!!
        assertEquals(Triple(target, 1, x), hit)
    }

    @Test fun insideTheWindowIsNotSearched() {
        val w = TagWindow(chain, 100)
        val x = tag(500, 0)
        assertNull(w.searchForward({ it == x })) // tryOpen covers it; search starts past the window
    }

    @Test fun capIsRespected() {
        val w = TagWindow(chain, 0)
        val x = tag(TagWindow.RESYNC_CAP + 10, 0)
        assertNull(w.searchForward({ it == x }))
        assertNotNull(w.searchForward({ it == x }, cap = TagWindow.RESYNC_CAP + 100))
    }

    @Test fun neverHeardTagSearchesPastFirstSync() {
        val w = TagWindow(chain, -1)
        val x = tag(TagWindow.FIRST_SYNC + 77, 0)
        assertEquals(TagWindow.FIRST_SYNC + 77, w.searchForward({ it == x })!!.first)
    }

    @Test fun openAtMovesTheWindow() {
        val w = TagWindow(chain, 100)
        val n = 100L + 2048 + 5000
        val pt = ByteArray(33).also { it[0] = 0x21; it[3] = 55 }
        val p = keys.sealReport(n, 0, pt)
        val (fn, fj, _) = w.searchForward({ it == LibreTag.bytesToHex(p.copyOf(LibreTag.TAG_LEN)) })!!
        assertNotNull(w.openAt(p, fn, fj))
        assertEquals(n, w.lastN)
        // the next report after the jump opens normally
        val p2 = keys.sealReport(n + 1, 0, pt)
        assertEquals(n + 1, w.tryOpen(p2)!!.first)
    }
}
