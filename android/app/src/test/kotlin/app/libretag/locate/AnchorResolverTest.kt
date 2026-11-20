package app.libretag.locate

import org.junit.Assert.assertEquals
import org.junit.Test

class AnchorResolverTest {
    private val ba = Repeater("a5e1000000000001", "XX-Alpha", 48.700, 21.227)
    private val nl = Repeater("a5e1000000000002", "NL-Synthetic", 52.13, 4.73, lastAdvert = 2_000)
    private val ba2 = Repeater("e8b9000000000001", "XX-Beta", 48.657, 21.239)
    private val de = Repeater("e8b9000000000002", "DE-Berlin", 52.52, 13.40)
    private val db = listOf(ba, nl, ba2, de)
    private fun cands(h: String) = db.filter { it.key.startsWith(h) }
    private fun heard(vararg h: String) = h.map { AnchorResolver.Heard(it, 5.0) }

    @Test fun priorPicksNearest() {
        val r = AnchorResolver.resolve(heard("a5e1"), ::cands, 48.700 to 21.250, 3600)
        assertEquals(listOf("XX-Alpha"), r.anchors.map { it.rep.name })
    }

    @Test fun priorRejectsUnreachable() {
        val r = AnchorResolver.resolve(heard("a5e1"), { listOf(nl) }, 48.700 to 21.250, 600)
        assertEquals(0, r.anchors.size); assertEquals(1, r.unresolved)
    }

    @Test fun noPriorClustersTogether() {
        val r = AnchorResolver.resolve(heard("a5e1", "e8b9"), ::cands, null, null)
        assertEquals(setOf("XX-Alpha", "XX-Beta"), r.anchors.map { it.rep.name }.toSet())
        assertEquals(0, r.ambiguous)
    }

    @Test fun noPriorSingleHashIsAmbiguous() {
        val r = AnchorResolver.resolve(heard("a5e1"), ::cands, null, null)
        assertEquals(1, r.ambiguous)
    }

    @Test fun noPriorUniqueHashIsFine() {
        val r = AnchorResolver.resolve(heard("e8b9"), { listOf(ba2) }, null, null)
        assertEquals(0, r.ambiguous); assertEquals(1, r.anchors.size)
    }
}
