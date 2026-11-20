package app.libretag.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import app.libretag.DiagLog
import app.libretag.proto.BleConsole
import app.libretag.usb.SerialLink
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.security.SecureRandom
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * The tag's console over Bluetooth (PROTOCOL.md 6.2): a GATT client on the LibreTag console service.
 * [open] connects (LE, no auto connect), asks for MTU 247, enables notifications and runs the
 * handshake with the tag's usb_key; after that [write] seals console bytes into DATA frames (Write
 * With Response, one at a time) and [read] gives the opened bytes the tag notified. No Bluetooth
 * pairing or bond. Blocking: call from Dispatchers.IO. Once the link is gone [read] and [write]
 * throw IOException and [onLost] runs once.
 */
@SuppressLint("MissingPermission") // the caller holds BLUETOOTH_CONNECT (Android 12+)
class BleSerial private constructor(private val usbKey: ByteArray) : SerialLink {
    override val wakeBeforeWrite = false
    override val minQuietMs = 600
    override val echoes = false

    @Volatile private var gatt: BluetoothGatt? = null
    @Volatile private var closed = false
    @Volatile var mtu = 23
        private set
    /** the reason the tag gave when it ended the session itself (`BLECON closing <why>`), if it did */
    @Volatile var closingReason: String? = null
        private set
    @Volatile var onLost: (() -> Unit)? = null
    /** the app ended the session itself (no reconnect) */
    @Volatile var userClosed = false

    private var rx: BluetoothGattCharacteristic? = null
    private var session: BleConsole.Session? = null
    private val frames = LinkedBlockingQueue<ByteArray>()     // before the session: HELLO, READY
    private val bytes = LinkedBlockingQueue<ByteArray>()      // opened console bytes
    private val events = LinkedBlockingQueue<Pair<String, Int>>() // GATT op results, one op at a time
    private val writeLock = Any()
    private val tail = StringBuilder()                        // for `BLECON closing`

    private val cb = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) events.add("conn" to status)
            else if (newState == BluetoothProfile.STATE_DISCONNECTED) lost("disconnected (status $status)")
        }
        override fun onMtuChanged(g: BluetoothGatt, m: Int, status: Int) { mtu = m; events.add("mtu" to status) }
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) { events.add("svc" to status) }
        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) { events.add("desc" to status) }
        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) { events.add("write" to status) }
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) { frame(value) }
        @Deprecated("Android 12 calls this one")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < 33) @Suppress("DEPRECATION") c.value?.let { frame(it.copyOf()) }
        }
    }

    private fun frame(f: ByteArray) {
        val s = session
        if (s == null) { frames.add(f); return }
        val pt = synchronized(s) { s.open(BleConsole.T2A, f) }
        if (pt == null) { DiagLog.w(TAG, "a frame failed its check"); close(); return }
        bytes.add(pt)
        synchronized(tail) {
            tail.append(String(pt, Charsets.UTF_8))
            CLOSING.find(tail)?.let { closingReason = it.groupValues[1] }
            if (tail.length > 200) tail.delete(0, tail.length - 64)
        }
    }

    private fun lost(why: String) {
        if (closed) return
        closed = true
        DiagLog.i(TAG, "link lost: $why")
        runCatching { gatt?.close() }
        bytes.add(ByteArray(0)) // wakes a reader
        events.add("lost" to -1)
        onLost?.invoke()
    }

    private fun await(what: String, ms: Long): Int {
        val end = System.currentTimeMillis() + ms
        while (true) {
            val left = end - System.currentTimeMillis()
            if (left <= 0) throw IOException("Bluetooth: no $what in time")
            val e = events.poll(left, TimeUnit.MILLISECONDS) ?: continue
            if (e.first == "lost") throw IOException("Bluetooth link lost")
            if (e.first == what) return e.second
        }
    }

    private fun writeRaw(c: BluetoothGattCharacteristic, v: ByteArray) {
        val g = gatt ?: throw IOException("Bluetooth link closed")
        events.clear()
        val ok = if (Build.VERSION.SDK_INT >= 33)
            g.writeCharacteristic(c, v, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == android.bluetooth.BluetoothStatusCodes.SUCCESS
        else @Suppress("DEPRECATION") run {
            c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            c.value = v
            g.writeCharacteristic(c)
        }
        if (!ok) throw IOException("Bluetooth write refused")
        if (await("write", 5_000) != BluetoothGatt.GATT_SUCCESS) throw IOException("Bluetooth write failed")
    }

    private fun handshake(ctx: Context, device: BluetoothDevice) {
        val g = device.connectGatt(ctx, false, cb, BluetoothDevice.TRANSPORT_LE) ?: throw IOException("Bluetooth: no GATT")
        gatt = g
        if (await("conn", 15_000) != BluetoothGatt.GATT_SUCCESS) throw IOException("Bluetooth: connect failed")
        g.requestMtu(BleConsole.MTU)
        runCatching { await("mtu", 5_000) }
        if (mtu < 64) throw IOException("Bluetooth: MTU $mtu too small")
        if (!g.discoverServices()) throw IOException("Bluetooth: discovery refused")
        await("svc", 10_000)
        val svc = g.getService(BleConsole.SERVICE) ?: throw IOException("Bluetooth: no console service")
        val tx = svc.getCharacteristic(BleConsole.TX) ?: throw IOException("Bluetooth: no TX")
        rx = svc.getCharacteristic(BleConsole.RX) ?: throw IOException("Bluetooth: no RX")
        g.setCharacteristicNotification(tx, true)
        val ccc = tx.getDescriptor(BleConsole.CCC) ?: throw IOException("Bluetooth: no CCC")
        events.clear()
        val ok = if (Build.VERSION.SDK_INT >= 33) g.writeDescriptor(ccc, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == android.bluetooth.BluetoothStatusCodes.SUCCESS
        else @Suppress("DEPRECATION") run { ccc.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE; g.writeDescriptor(ccc) }
        if (!ok || await("desc", 5_000) != BluetoothGatt.GATT_SUCCESS) throw IOException("Bluetooth: notifications refused")
        val hello = frames.poll(5, TimeUnit.SECONDS) ?: throw IOException("Bluetooth: no HELLO")
        val tn = BleConsole.helloNonce(hello) ?: throw IOException("Bluetooth: not a LibreTag console")
        val an = ByteArray(BleConsole.NONCE_LEN).also { SecureRandom().nextBytes(it) }
        writeRaw(rx!!, BleConsole.auth(usbKey, tn, an))
        val ready = frames.poll(5, TimeUnit.SECONDS) ?: throw IOException("Bluetooth: the tag refused the key")
        if (!BleConsole.readyOk(usbKey, ready, tn, an)) throw IOException("Bluetooth: couldn't verify the tag")
        session = BleConsole.Session(usbKey, tn, an)
        DiagLog.i(TAG, "session open, MTU $mtu")
    }

    override fun write(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        synchronized(writeLock) {
            if (closed) throw IOException("Bluetooth link lost")
            val s = session ?: throw IOException("Bluetooth: no session")
            val c = rx ?: throw IOException("Bluetooth: no RX")
            val n = BleConsole.chunk(mtu)
            var off = 0
            while (off < bytes.size) {
                val f = synchronized(s) { s.seal(BleConsole.A2T, bytes.copyOfRange(off, minOf(bytes.size, off + n))) }
                writeRaw(c, f)
                off += n
            }
        }
    }

    override fun read(timeoutMs: Int): ByteArray {
        if (closed && bytes.isEmpty()) throw IOException("Bluetooth link lost")
        val first = bytes.poll(timeoutMs.toLong(), TimeUnit.MILLISECONDS) ?: return ByteArray(0)
        val out = ByteArrayOutputStream().apply { write(first) }
        while (true) out.write(bytes.poll() ?: break)
        if (out.size() == 0 && closed) throw IOException("Bluetooth link lost")
        return out.toByteArray()
    }

    /** A fast link for a long listing (history), balanced again afterwards. */
    fun fast(on: Boolean) {
        runCatching { gatt?.requestConnectionPriority(if (on) BluetoothGatt.CONNECTION_PRIORITY_HIGH else BluetoothGatt.CONNECTION_PRIORITY_BALANCED) }
    }

    val isOpen: Boolean get() = !closed && session != null

    override fun close() {
        if (closed) return
        closed = true
        onLost = null
        runCatching { gatt?.disconnect() }
        runCatching { gatt?.close() }
        bytes.add(ByteArray(0))
    }

    companion object {
        const val TAG = "BleSerial"
        private val CLOSING = Regex("""BLECON closing (\w+)""")

        /** Connects and runs the handshake; throws IOException with a short reason. */
        fun open(ctx: Context, device: BluetoothDevice, usbKey: ByteArray): BleSerial {
            val s = BleSerial(usbKey)
            try {
                s.handshake(ctx.applicationContext, device)
            } catch (e: Exception) {
                s.close()
                throw if (e is IOException) e else IOException(e.message ?: e.javaClass.simpleName)
            }
            return s
        }
    }
}
