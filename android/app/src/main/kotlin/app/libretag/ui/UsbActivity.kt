package app.libretag.ui

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import app.libretag.LibreTagApp
import app.libretag.usb.UsbFind
import app.libretag.usb.UsbState
import app.libretag.usb.dfu.FirmwareUpdate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Opens when a tag is plugged in (USB_DEVICE_ATTACHED): a tag of ours goes to its Service screen
 * ("Car is connected"), anything else to Add tag.
 */
class UsbActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = LibreTagApp.instance
        // the tag coming back after a firmware update: the update screen takes it from here
        if (FirmwareUpdate.active) { finish(); return }
        val h = UsbHelper(this)
        val dev = intent.usbDevice() ?: h.findTag()
        lifecycleScope.launch {
            h.start(dev)
            val st = app.usb.state.first { it is UsbState.Connected || it is UsbState.Failed || it is UsbState.NeedPermission }
            val tag = (st as? UsbState.Connected)?.tag
            val i = if (tag != null && (tag.find == UsbFind.MINE || tag.find == UsbFind.UNLOCK_FAILED) && tag.tagId != null)
                Intent(this@UsbActivity, ServiceActivity::class.java).putExtra(MainActivity.EXTRA_TAG, tag.tagId)
            else Intent(this@UsbActivity, AddTagActivity::class.java)
            startActivity(i)
            finish()
        }
    }
}
