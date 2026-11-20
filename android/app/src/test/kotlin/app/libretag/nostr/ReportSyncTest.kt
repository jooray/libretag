package app.libretag.nostr

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReportSyncTest {
    private val sk = ByteArray(32) { 2 }
    private fun ev(t: Long, i: Int) = NostrEvent.sign(sk, t, 4243, emptyList(), "c$i")

    /** a relay with these events, answering newest first up to limit, like strfry */
    private fun relay(all: List<NostrEvent>): suspend (Long, Long, Int) -> List<NostrEvent> = { s, u, l ->
        all.filter { it.createdAt in s..u }.sortedByDescending { it.createdAt }.take(l)
    }

    @Test fun pagesBackToSince() = runBlocking {
        val all = (0 until 1234).map { ev(1_000_000L + it, it) }
        var calls = 0
        val f = relay(all)
        val r = ReportSync.backlog(1_000_000L, 1_010_000L, { s, u, l -> calls++; f(s, u, l) }, page = 100)
        assertTrue(r.complete)
        assertEquals(1234, r.events.size)
        assertEquals(all.map { it.id }.toSet(), r.events.map { it.id }.toSet())
        assertTrue(r.events.zipWithNext().all { (a, b) -> a.createdAt <= b.createdAt })
        assertTrue(calls in 13..15)
    }

    @Test fun manyEventsInOneSecondDoNotLoop() = runBlocking {
        val all = (0 until 250).map { ev(1_000_500L, it) } + (0 until 10).map { ev(1_000_100L + it, 1000 + it) }
        val r = ReportSync.backlog(1_000_000L, 1_001_000L, relay(all), page = 100)
        assertTrue(r.complete)
        // a relay can't page inside one second: at least the page it gave, and everything older
        assertTrue(r.events.size >= 110)
    }

    @Test fun capFallsBackToWindowQuery() = runBlocking {
        val all = (0 until 5000).map { ev(1_000_000L + it, it) }
        val r = ReportSync.backlog(1_000_000L, 1_010_000L, relay(all), page = 500, cap = 1200)
        assertFalse(r.complete)
        assertTrue(r.events.size in 1200..1500)
        // the uncovered range is older than everything fetched
        assertTrue(r.uncoveredUntil <= r.events.minOf { it.createdAt })
        assertTrue(r.uncoveredUntil > 1_000_000L)
    }

    @Test fun emptyRelay() = runBlocking {
        val r = ReportSync.backlog(1, 100, relay(emptyList()))
        assertTrue(r.complete)
        assertEquals(0, r.events.size)
    }

    @Test fun liveRate() {
        val r = ReportSync.Rate(1000)
        repeat(5) { r.add(100L + it) }
        assertEquals(6, r.add(500))
        assertEquals(1, r.add(5000))
    }
}
