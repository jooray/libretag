package app.libretag.ble

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import app.libretag.DiagLog
import app.libretag.LibreTagApp
import app.libretag.proto.BleConsole

/**
 * "Notice my tags over Bluetooth" (docs/design/tag-diagnosis.md 4.1): one filtered scan with a
 * PendingIntent, in the controller, low power, for manufacturer data 0xFFFF starting 0x25 (a
 * console window) or 0x26 (a "here" advert, for later firmware). The phone does not wake for other
 * adverts. Runs while the owner has a tag with keys, Bluetooth is on and Nearby devices is
 * granted (Android 12+, neverForLocation); the switch in Settings turns it off. No location.
 */
@SuppressLint("MissingPermission") // behind BleConsoleScanner.permitted()
object BleSightScan {
    private const val TAG = "BleSight"
    private const val REQ = 4246
    const val HERE_TYPE = 0x26
    @Volatile private var running = false

    private fun intent(c: Context, flags: Int): PendingIntent? = PendingIntent.getBroadcast(c, REQ,
        Intent(c, BleSightReceiver::class.java), flags)

    private fun scanner(c: Context): BluetoothLeScanner? =
        c.getSystemService(BluetoothManager::class.java)?.adapter?.takeIf { it.isEnabled }?.bluetoothLeScanner

    /** Start or stop to match the setting, the permission and the tags. Safe to call often. */
    fun update(app: LibreTagApp) {
        val want = app.prefs.bleSightings && BleConsoleScanner.permitted(app) &&
            app.store.trackers.value.any { it.keyHex.isNotEmpty() }
        val sc = scanner(app)
        if (!want || sc == null) {
            if (running) stop(app)
            return
        }
        if (running) return
        val pi = intent(app, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE) ?: return
        val filters = listOf(BleConsole.ADV_TYPE, HERE_TYPE).map { type ->
            ScanFilter.Builder().setManufacturerData(BleConsole.COMPANY, byteArrayOf(type.toByte()), byteArrayOf(0xFF.toByte())).build()
        }
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES).build()
        val rc = runCatching { sc.startScan(filters, settings, pi) }.getOrElse { DiagLog.w(TAG, "start: ${it.message}"); -1 }
        running = rc == 0
        DiagLog.i(TAG, if (running) "background scan on" else "background scan not started ($rc)")
    }

    fun stop(c: Context) {
        running = false
        val pi = intent(c, PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_MUTABLE) ?: return
        runCatching { scanner(c)?.stopScan(pi) }
        DiagLog.i(TAG, "background scan off")
    }

    /** Android 12+ (the scan permission without location). */
    val supported: Boolean get() = Build.VERSION.SDK_INT >= 31
}

/** The background scan's matches: the same recognition as the foreground scan. */
class BleSightReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val err = intent.getIntExtra(BluetoothLeScanner.EXTRA_ERROR_CODE, 0)
        if (err != 0) { DiagLog.w("BleSight", "scan error $err"); return }
        val results: List<ScanResult> = if (Build.VERSION.SDK_INT >= 33)
            intent.getParcelableArrayListExtra(BluetoothLeScanner.EXTRA_LIST_SCAN_RESULT, ScanResult::class.java) ?: emptyList()
        else @Suppress("DEPRECATION") intent.getParcelableArrayListExtra(BluetoothLeScanner.EXTRA_LIST_SCAN_RESULT) ?: emptyList()
        val app = LibreTagApp.instance
        results.forEach { app.ble.onResult(it) }
    }
}
