package app.libretag.usb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * A scripted link: each written line is answered from [answers], optionally echoed (USB) or not
 * (Bluetooth), cut into [chunk]-byte reads with [gapMs] between them.
 */
class FakeLink(
    private val answers: Map<String, String>,
    override val echoes: Boolean,
    private val chunk: Int = 7,
    private val gapMs: Long = 0,
    override val minQuietMs: Int = 0,
    private val failAfterWrites: Int = Int.MAX_VALUE,
) : SerialLink {
    override val wakeBeforeWrite = false
    private val pending = ArrayDeque<ByteArray>()
    private var writes = 0
    val written = mutableListOf<String>()

    override fun write(bytes: ByteArray) {
        if (++writes > failAfterWrites) throw IOException("link lost")
        val line = String(bytes).trimEnd('\r')
        written += line
        val out = (if (echoes) "$line\r\n" else "") + (answers[line] ?: "ERR unknown command, try help\r\n")
        out.toByteArray().toList().chunked(chunk).forEach { pending.addLast(it.toByteArray()) }
    }

    override fun read(timeoutMs: Int): ByteArray {
        val b = pending.removeFirstOrNull()
        if (b == null) { Thread.sleep(minOf(timeoutMs.toLong(), 5)); return ByteArray(0) }
        if (gapMs > 0) Thread.sleep(gapMs)
        return b
    }

    override fun close() {}
}

class TrackerConsoleTest {
    private val status = "LIBRETAG ID 0123456789abcdef\r\nlocked (unlocked for this connection)\r\nfirmware LibreTag 0.8.0, ZephCore da3513aa6d08\r\nbattery 88% (4012 mV)\r\n"
    private val owner = "owner npub1qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqq"

    @Test fun sameAnswerWithAndWithoutEcho() {
        for (echo in listOf(true, false)) for (chunk in listOf(1, 3, 64)) {
            val c = TrackerConsole(FakeLink(mapOf("status" to status), echoes = echo, chunk = chunk))
            val s = c.status()
            assertEquals("0123456789abcdef", s.idHex)
            assertEquals(88, s.battery)
            assertTrue(s.locked)
        }
    }

    @Test fun ownerAnswerIsKeptWithoutEcho() {
        // `owner <npub>` answers with the line it was sent: over USB the first copy is the echo,
        // over Bluetooth there is no echo and the one line is the answer
        val usb = TrackerConsole(FakeLink(mapOf(owner to "$owner\r\n"), echoes = true)).command(owner)
        val ble = TrackerConsole(FakeLink(mapOf(owner to "$owner\r\n"), echoes = false)).command(owner)
        assertEquals(listOf(owner), usb)
        assertEquals(listOf(owner), ble)
    }

    @Test fun quietFloorOfTheLink() {
        // a Bluetooth answer pauses between frames: with a 600 ms floor the whole answer arrives
        val link = FakeLink(mapOf("status" to status), echoes = false, chunk = 40, gapMs = 120, minQuietMs = 600)
        val lines = TrackerConsole(link).command("status", quietMs = 100)
        assertEquals(4, lines.size)
    }

    @Test fun collectUntilEnd() {
        val hist = "HIST 5 0 aabb\r\nHIST 6 0 ccdd\r\nEND 2\r\n"
        val link = FakeLink(mapOf("history 0" to hist), echoes = false, chunk = 5)
        val c = TrackerConsole(link)
        c.port().write("history 0\r".toByteArray())
        val lines = c.collect(3000, 50) { it.startsWith("END") }
        assertEquals(listOf("HIST 5 0 aabb", "HIST 6 0 ccdd", "END 2"), lines)
    }

    @Test(expected = IOException::class)
    fun linkThatGoesAwayThrows() {
        val c = TrackerConsole(FakeLink(mapOf("status" to status), echoes = false, failAfterWrites = 1))
        c.status()
        c.status()
    }
}
