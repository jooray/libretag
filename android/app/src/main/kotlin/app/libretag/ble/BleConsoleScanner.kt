package app.libretag.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import app.libretag.DiagLog
import app.libretag.LibreTagApp
import app.libretag.data.Tracker
import app.libretag.proto.BleConsole
import app.libretag.proto.LibreTag
import app.libretag.usb.ConsoleParsers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** A tag's console advert: the tag listens on Bluetooth for about [leftS] seconds more. */
data class BleSeen(val tagId: String, val device: BluetoothDevice, val leftS: Int, val rssi: Int, val at: Long) {
    /** seconds left now */
    fun leftNow(now: Long = System.currentTimeMillis()): Int = (leftS - ((now - at) / 1000).toInt()).coerceAtLeast(0)
}

/**
 * Scans for console adverts (PROTOCOL.md 6.2: manufacturer data 0xFFFF, first byte 0x25) while a
 * screen that can use the console is in front, and recognises the owner's tags by their usb_key
 * (a salted 6 B tag, new every window). Never in the background. Android 12+ with the Nearby
 * devices permission (BLUETOOTH_SCAN with neverForLocation).
 */
@SuppressLint("MissingPermission") // every call is behind permitted()
class BleConsoleScanner(private val app: LibreTagApp) {
    private val _seen = MutableStateFlow<Map<String, BleSeen>>(emptyMap())
    val seen: StateFlow<Map<String, BleSeen>> = _seen
    private var users = 0
    private var running = false
    /** usb_key per tag id, computed once per scan start */
    @Volatile private var keys: List<Pair<String, ByteArray>> = emptyList()
    @Volatile private var keysAt = 0

    private fun refreshKeys() {
        val list = app.store.trackers.value
        keys = list.mapNotNull { t -> app.keysFor(t)?.let { t.id to it.usbKey } }
        keysAt = list.size
    }

    private val cb = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, r: ScanResult) = onResult(r)
        override fun onBatchScanResults(results: MutableList<ScanResult>) = results.forEach { onResult(it) }
        override fun onScanFailed(errorCode: Int) { DiagLog.w(TAG, "scan failed $errorCode"); running = false }
    }

    /** A result from the foreground scan or the background filtered scan ([BleSightReceiver]). */
    fun onResult(r: ScanResult) {
        val p = r.scanRecord?.getManufacturerSpecificData(BleConsole.COMPANY) ?: return
        if (!BleConsole.isConsoleAdvert(p)) return
        if (keys.isEmpty() || keysAt < app.store.trackers.value.size) refreshKeys()
        val id = keys.firstOrNull { BleConsole.advMatch(it.second, p) }?.first ?: return
        val now = System.currentTimeMillis()
        // 0.12: the diagnosis keeps the sighting; an unasked new window is a restart nearby
        val salt = LibreTag.bytesToHex(p.copyOfRange(2, 8))
        app.store.get(id)?.let { cur ->
            val next = BleBoots.onAdvert(cur, "console", salt, r.rssi, now)
            if (next !== cur) { app.store.updateTracker(id) { BleBoots.onAdvert(it, "console", salt, r.rssi, now) }; app.diagContextVersion.value++ }
        }
        val prev = _seen.value[id]
        _seen.value = _seen.value + (id to BleSeen(id, r.device, BleConsole.leftSeconds(p), r.rssi, now))
        if (prev == null || prev.device.address != r.device.address) {
            DiagLog.i(TAG, "tag $id listening, ${BleConsole.leftSeconds(p)} s left")
            // only firmware with the console advertises it
            app.noteFirmwareAtLeast(id, BleConsole.FIRMWARE)
        }
    }

    /** A screen wants the scan (counted; [release] for each). */
    fun acquire(ctx: Context) {
        users++
        start(ctx)
    }

    fun release() {
        users = (users - 1).coerceAtLeast(0)
        if (users == 0) stop()
    }

    /** (Re)start when someone wants it, the permission is there and the app has tags with keys. */
    fun start(ctx: Context) {
        if (users == 0 || running || !permitted(ctx)) return
        refreshKeys()
        if (keys.isEmpty()) return
        val scanner = ctx.getSystemService(BluetoothManager::class.java)?.adapter?.takeIf { it.isEnabled }?.bluetoothLeScanner ?: return
        val filter = ScanFilter.Builder()
            .setManufacturerData(BleConsole.COMPANY, byteArrayOf(BleConsole.ADV_TYPE.toByte()), byteArrayOf(0xFF.toByte()))
            .build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        running = runCatching { scanner.startScan(listOf(filter), settings, cb) }.onFailure { DiagLog.w(TAG, "start: ${it.message}") }.isSuccess
    }

    fun stop() {
        if (!running) return
        running = false
        runCatching { app.getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeScanner?.stopScan(cb) }
    }

    /** the tag's advert when it was seen in the last 20 s and its window has time left */
    fun current(tagId: String): BleSeen? = _seen.value[tagId]?.takeIf {
        System.currentTimeMillis() - it.at < 20_000 && it.leftNow() > 0
    }

    fun forget(tagId: String) { _seen.value = _seen.value - tagId }

    companion object {
        const val TAG = "BleConsole"

        /** Android 12+ only: older versions scan only with the location permission (PRODUCT.md). */
        val supported: Boolean get() = Build.VERSION.SDK_INT >= 31

        fun permitted(ctx: Context): Boolean = supported &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

        /** The tag's firmware has the console (0.8.0), as far as the app knows. */
        fun firmwareHas(t: Tracker): Boolean = t.firmware?.let { !ConsoleParsers.isOlder(it, BleConsole.FIRMWARE) } ?: false
    }
}
