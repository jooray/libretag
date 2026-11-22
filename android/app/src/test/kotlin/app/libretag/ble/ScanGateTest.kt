package app.libretag.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class ScanGateTest {
    private class FakeRadio(var ok: Boolean = true) : ScanGate.Radio {
        val starts = AtomicInteger()
        val stops = AtomicInteger()
        /** scans the stack holds now (a real stack fails a second start with ALREADY_STARTED) */
        val registered = AtomicInteger()
        override fun start(): Boolean { starts.incrementAndGet(); if (ok) registered.set(1); return ok }
        override fun stop() { stops.incrementAndGet(); registered.set(0) }
    }

    @Test fun countsUsers() {
        val r = FakeRadio()
        val g = ScanGate(r)
        g.acquire(); g.acquire()
        assertEquals(1, r.starts.get())
        g.release()
        assertTrue(g.running)
        g.release()
        assertFalse(g.running)
        assertEquals(0, r.registered.get())
        g.release() // never below zero
        assertEquals(0, g.users())
    }

    @Test fun alreadyStartedKeepsRunningAndStopStillStops() {
        val r = FakeRadio()
        val g = ScanGate(r)
        g.acquire()
        g.failed(ScanGate.ALREADY_STARTED)
        assertTrue(g.running)
        g.start() // no second registration
        assertEquals(1, r.starts.get())
        g.release()
        assertEquals(0, r.registered.get())
    }

    @Test fun otherFailureAllowsRestartAndStopAlwaysCallsStack() {
        val r = FakeRadio()
        val g = ScanGate(r)
        g.acquire()
        g.failed(2) // SCAN_FAILED_APPLICATION_REGISTRATION_FAILED
        assertFalse(g.running)
        g.stop() // not running, the stack is still told
        assertEquals(1, r.stops.get())
        g.start()
        assertEquals(2, r.starts.get())
        assertTrue(g.running)
    }

    @Test fun failedStartIsNotRunning() {
        val r = FakeRadio(ok = false)
        val g = ScanGate(r)
        g.acquire()
        assertFalse(g.running)
        r.ok = true
        g.start()
        assertTrue(g.running)
    }

    @Test fun balancedFromManyThreads() {
        val r = FakeRadio()
        val g = ScanGate(r)
        val go = CountDownLatch(1)
        val ts = (1..8).map { thread { go.await(); repeat(2000) { g.acquire(); g.release() } } }
        go.countDown()
        ts.forEach { it.join() }
        assertEquals(0, g.users())
        assertFalse(g.running)
        assertEquals(0, r.registered.get())
    }
}
