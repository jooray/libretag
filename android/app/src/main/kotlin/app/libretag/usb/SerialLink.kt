package app.libretag.usb

import java.io.Closeable

/**
 * A byte link to the tag's console: the USB cable ([UsbSerial]) or, since firmware 0.8.0, the
 * Bluetooth console ([app.libretag.ble.BleSerial], PROTOCOL.md 6.2). [TrackerConsole] runs the same
 * commands over either.
 */
interface SerialLink : Closeable {
    /** Throws IOException when the link is gone. */
    fun write(bytes: ByteArray)

    /** Bytes that came within [timeoutMs]; empty on timeout. */
    fun read(timeoutMs: Int): ByteArray

    /** the tag may be in light sleep and lose the first line (the CP210x console of ESP32 boards) */
    val wakeBeforeWrite: Boolean

    /** the shortest quiet time that ends an answer (a Bluetooth answer pauses longer between frames) */
    val minQuietMs: Int

    /** the tag echoes what it receives (USB); the Bluetooth console does not */
    val echoes: Boolean get() = true
}
