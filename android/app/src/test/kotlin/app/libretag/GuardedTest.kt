package app.libretag

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GuardedTest {
    @Test fun resultPassesThrough() = runBlocking {
        assertEquals(3, guarded({ throw AssertionError("no warning expected") }) { 3 })
    }

    @Test fun failureWarnsAndTheLoopGoesOn() = runBlocking {
        val warned = mutableListOf<String>()
        var runs = 0
        repeat(3) {
            val r = guarded({ warned += it.message ?: "" }) { runs++; if (runs < 3) error("relay gone") else "synced" }
            if (runs < 3) assertNull(r) else assertEquals("synced", r)
        }
        assertEquals(3, runs)
        assertEquals(listOf("relay gone", "relay gone"), warned)
    }

    @Test fun cancellationIsNotSwallowed() = runBlocking {
        var thrown = false
        try { guarded({}) { throw CancellationException("stop") } } catch (e: CancellationException) { thrown = true }
        assertTrue(thrown)
    }
}
