package app.libretag.ui

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import app.libretag.LibreTagApp
import app.libretag.usb.Via
import app.libretag.R
import app.libretag.usb.UsbSerial
import app.libretag.usb.UsbState
import app.libretag.usb.dfu.FirmwareUpdate
import kotlinx.coroutines.launch

/**
 * Finds the tag on the cable, asks for the USB permission, and connects the shared session.
 * Lives as long as the activity; plugging in or out while it is open is followed.
 */
class UsbHelper(private val a: AppCompatActivity, private val onChange: () -> Unit = {}) : DefaultLifecycleObserver {
    private val usb by lazy { a.getSystemService(UsbManager::class.java) }
    private val app get() = LibreTagApp.instance

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val device = intent.usbDevice() ?: return
            when (intent.action) {
                ACTION_PERMISSION -> if (FirmwareUpdate.active) onChange()
                    else if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) connect(device)
                    else { app.usb.needPermission(); onChange() }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> start(device)
                UsbManager.ACTION_USB_DEVICE_DETACHED -> { if (!FirmwareUpdate.active) app.usb.usbDetached(); onChange() }
            }
        }
    }

    init { a.lifecycle.addObserver(this) }

    override fun onCreate(owner: LifecycleOwner) {
        val f = IntentFilter().apply {
            addAction(ACTION_PERMISSION); addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED); addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        ContextCompat.registerReceiver(a, receiver, f, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onDestroy(owner: LifecycleOwner) { runCatching { a.unregisterReceiver(receiver) } }

    fun findTag(): UsbDevice? {
        // a tag in its bootloader belongs to the firmware update, never to the console
        val all = usb.deviceList.values.filterNot { FirmwareUpdate.isBootloader(it) }
        return all.firstOrNull { it.vendorId == UsbSerial.VID && it.productId == UsbSerial.PID_TRACKER }
            ?: all.firstOrNull { UsbSerial.isCdc(it) }
            // a Heltec ESP32 board (Wi-Fi tracker) behind its CP2102 bridge
            ?: all.firstOrNull { UsbSerial.isCp210x(it) }
    }

    /** Connect to what's on the cable now (or the device from an attach intent). */
    fun start(device: UsbDevice? = a.intent?.usbDevice() ?: findTag()) {
        // the update owns the cable while it runs
        if (FirmwareUpdate.active || (device != null && FirmwareUpdate.isBootloader(device))) { onChange(); return }
        val d = device ?: run { if (app.usb.state.value !is UsbState.Connected) app.usb.usbDetached(); onChange(); return }
        // the cable wins over a Bluetooth session (0.8.0)
        if (app.usb.via == Via.BLE) app.usb.closeBle()
        if (app.usb.state.value is UsbState.Connected && findTag() != null) { onChange(); return }
        if (usb.hasPermission(d)) { connect(d); return }
        val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
        usb.requestPermission(d, PendingIntent.getBroadcast(a, 0, Intent(ACTION_PERMISSION).setPackage(a.packageName), flags))
    }

    /** Drop the session and connect again (after a reset or a reboot of the tag). */
    fun reconnect() {
        app.usb.disconnected()
        start(findTag())
    }

    private fun connect(d: UsbDevice) {
        a.lifecycleScope.launch {
            onChange()
            app.usb.connect(usb, d)
            onChange()
            noteSettingsRead()
        }
    }

    /** "Settings read from the tag": once, after the tag's own settings replaced the app's. */
    fun noteSettingsRead() {
        if (app.usb.settingsRead.value == null) return
        app.usb.settingsReadShown()
        a.findViewById<android.view.View>(android.R.id.content)?.snack(a.getString(R.string.usb_settings_read))
    }

    companion object {
        const val ACTION_PERMISSION = "app.libretag.USB_PERMISSION"
    }
}

fun Intent.usbDevice(): UsbDevice? =
    if (Build.VERSION.SDK_INT >= 33) getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
    else @Suppress("DEPRECATION") getParcelableExtra(UsbManager.EXTRA_DEVICE)
