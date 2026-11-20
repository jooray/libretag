package app.libretag.usb

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * What is on the USB cable, for the whole app while the process lives (docs/design/tag-diagnosis.md
 * 4.2). Classifies from the USB IDs without opening anything; [espDownload] is set by the session
 * when a CP2102 did not answer `status` and the ROM loader answered the `SYNC` probe.
 */
class CableWatch(private val ctx: Context) {
    data class State(val raw: CableClassify.Raw = CableClassify.Raw.NONE, val espDownload: Boolean = false, val since: Long = 0)

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) { refresh() }
    }

    fun start() {
        val f = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED); addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        ContextCompat.registerReceiver(ctx, receiver, f, ContextCompat.RECEIVER_NOT_EXPORTED)
        refresh()
    }

    /** Look at the device list again (an attach, a detach, or a screen that noticed a change). */
    fun refresh() {
        val usb = ctx.getSystemService(UsbManager::class.java) ?: return
        val devices = usb.deviceList.values.map {
            CableClassify.Device(it.vendorId, it.productId, runCatching { it.productName }.getOrNull(), UsbSerial.isCdc(it))
        }
        val raw = CableClassify.classifyAll(devices)
        val cur = _state.value
        if (raw == cur.raw) return
        _state.value = State(raw, espDownload = false, since = System.currentTimeMillis())
    }

    /** The session's `SYNC` probe answered (true) or the firmware answered `status` (false). */
    fun setEspDownload(on: Boolean) {
        val cur = _state.value
        if (cur.espDownload != on) _state.value = cur.copy(espDownload = on)
    }
}
