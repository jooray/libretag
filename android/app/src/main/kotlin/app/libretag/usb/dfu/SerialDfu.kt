package app.libretag.usb.dfu

import java.io.IOException

/** Byte pipe to the bootloader (USB CDC on the phone, a fake in tests). */
interface DfuLink {
    /** Throws IOException when the bytes did not go out (the bootloader may be busy erasing). */
    fun write(bytes: ByteArray)
    /** What arrived within [timeoutMs]; empty on timeout. */
    fun read(timeoutMs: Int): ByteArray
}

interface DfuClock {
    fun now(): Long
    fun sleep(ms: Long)

    object System : DfuClock {
        override fun now() = java.lang.System.currentTimeMillis()
        override fun sleep(ms: Long) = Thread.sleep(ms)
    }
}

enum class DfuPhase { START, ERASING, INIT, DATA, FINISHING, DONE }

class DfuTransferException(val phase: DfuPhase, message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * Serial DFU sender (stop and wait, like adafruit-nrfutil, but it checks each ack and resends):
 * START (application size) → wait for the erase → INIT (.dat) → DATA in 512-byte pieces → STOP.
 * After STOP the bootloader checks the CRC16 from the init packet, writes its settings page and
 * resets into the new application. Blocking: run on Dispatchers.IO.
 */
class SerialDfu(
    private val link: DfuLink,
    private val clock: DfuClock = DfuClock.System,
    private val ackTimeoutMs: Long = 1000,
    private val tries: Int = 4,
) {
    private var seq = 0
    private val reader = SlipReader()
    private var phase = DfuPhase.START
    /** frames sent again because no matching ack came (for the log) */
    var resends = 0
        private set

    fun run(image: ByteArray, initPacket: ByteArray, onPhase: (DfuPhase) -> Unit = {}, onProgress: (sent: Int, total: Int) -> Unit = { _, _ -> }) {
        fun enter(p: DfuPhase) { phase = p; onPhase(p) }
        enter(DfuPhase.START)
        send(Hci.startPayload(image.size))
        // the bootloader erases the application pages now; its CPU stalls, it answers nothing
        enter(DfuPhase.ERASING)
        clock.sleep(eraseWaitMs(image.size))
        enter(DfuPhase.INIT)
        send(Hci.initPayload(initPacket))
        enter(DfuPhase.DATA)
        val chunks = Hci.dataPayloads(image)
        var sent = 0
        onProgress(0, image.size)
        chunks.forEachIndexed { i, p ->
            send(p)
            sent += p.size - 4
            // a flash page (4 KB) is written every 8 pieces
            if (i % 8 == 0) clock.sleep(PAGE_WRITE_MS)
            onProgress(sent, image.size)
        }
        clock.sleep(PAGE_WRITE_MS)
        enter(DfuPhase.FINISHING)
        send(Hci.stopPayload())
        clock.sleep(PAGE_ERASE_MS + PAGE_WRITE_MS)
        enter(DfuPhase.DONE)
    }

    private fun send(payload: ByteArray) {
        seq = (seq + 1) and 7
        val frame = Hci.frame(seq, payload)
        val want = (seq + 1) and 7
        var lastError: Throwable? = null
        repeat(tries) { attempt ->
            if (attempt > 0) resends++
            try {
                link.write(frame)
            } catch (e: IOException) {
                lastError = e
                clock.sleep(100)
                return@repeat
            }
            val end = clock.now() + ackTimeoutMs
            while (clock.now() < end) {
                val b = link.read(minOf(100L, end - clock.now()).toInt().coerceAtLeast(1))
                if (b.isEmpty()) continue
                for (body in reader.feed(b)) if (Hci.ackNumber(body) == want) return
            }
        }
        throw DfuTransferException(phase, "no acknowledgement for packet $seq after $tries tries", lastError)
    }

    companion object {
        const val PAGE = 4096
        /** nRF52840 page erase, worst case (adafruit-nrfutil FLASH_PAGE_ERASE_TIME) */
        const val PAGE_ERASE_MS = 90L
        /** 1024 words × 100 µs */
        const val PAGE_WRITE_MS = 103L

        fun eraseWaitMs(size: Int): Long = maxOf(500L, (size / PAGE + 1) * PAGE_ERASE_MS)
    }
}
