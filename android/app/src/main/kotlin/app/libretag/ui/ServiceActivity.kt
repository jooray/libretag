package app.libretag.ui

import app.libretag.usb.WriteResult
import app.libretag.DiagLog
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import app.libretag.BuildConfig
import app.libretag.R
import app.libretag.data.Tracker
import app.libretag.usb.ConsoleParsers
import app.libretag.usb.TagOps
import app.libretag.usb.UsbFind
import app.libretag.usb.UsbState
import app.libretag.usb.Via
import app.libretag.usb.dfu.BundledFirmware
import app.libretag.usb.dfu.FirmwareUpdate
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import kotlinx.coroutines.launch

/**
 * Service: a known tag on the cable or over Bluetooth. Charging, history sync (reports the mesh didn't
 * deliver, read from the tag's memory), the firmware check, and the settings that need the cable.
 */
class ServiceActivity : ScreenActivity() {
    private lateinit var usbh: UsbHelper
    private lateinit var bleh: BleConsoleHelper
    private var tagId: String? = null
    private var syncing = false
    private var synced: Int? = null
    private var syncedFor: String? = null
    private var busyText: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tagId = intent.getStringExtra(MainActivity.EXTRA_TAG)
        usbh = UsbHelper(this) { render() }
        bleh = BleConsoleHelper(this, { tagId ?: app.usb.connected?.tagId }) { render() }
        lifecycleScope.launch { repeatOnLifecycle(Lifecycle.State.STARTED) { app.usb.state.collect { render(); maybeSync() } } }
        usbh.start()
    }

    private fun tag(): Tracker? = (tagId ?: app.usb.connected?.tagId)?.let { app.store.get(it) }

    private fun maybeSync() {
        val t = tag() ?: return
        if (!app.usb.tagFor(t) || syncing || syncedFor == t.id) return
        syncing = true; syncedFor = t.id
        lifecycleScope.launch {
            render()
            synced = TagOps.syncHistory(t) ?: -1
            syncing = false
            // settings changed in Reporting while the tag was away
            app.store.get(t.id)?.takeIf { it.cableDirty }?.let { cur ->
                if (TagOps.writeSettings(cur) == WriteResult.OK) content.snack(getString(R.string.set_written))
            }
            render()
        }
    }

    private fun render() {
        content.removeAllViews()
        val t = tag()
        val st = app.usb.state.value
        val conn = app.usb.connected
        val ble = conn?.via == Via.BLE
        setTitles(if (t != null && conn != null && app.usb.tagFor(t)) getString(R.string.svc_connected, t.name) else getString(R.string.menu_service),
            getString(when {
                t == null || conn == null || !app.usb.tagFor(t) -> R.string.svc_not_connected
                ble -> R.string.blecon_connected
                else -> R.string.svc_via_cable
            }))
        if (t == null) { para(getString(R.string.svc_no_tag)); return }
        if (conn == null || !app.usb.tagFor(t)) {
            content.addView(card(getString(R.string.svc_plug, t.name), R.drawable.ic_usb) {
                addView(text(R.style.TextAppearance_LibreTag_BodyMedium, when {
                    st is UsbState.Connecting -> getString(R.string.wz_connecting)
                    st is UsbState.NeedPermission -> getString(R.string.wz_permission)
                    st is UsbState.Failed -> getString(R.string.wz_failed, st.message)
                    conn != null && conn.find == UsbFind.UNLOCK_FAILED && conn.tagId == t.id -> getString(R.string.wz_unlock_failed)
                    conn != null -> getString(R.string.svc_other_tag)
                    else -> getString(R.string.svc_plug_text)
                }, R.color.ink2), lp().margins(t = dp(6), b = dp(12)))
                addView(LinearProgressIndicator(this@ServiceActivity).apply { isIndeterminate = true })
                // 0.8.0: or over Bluetooth after a restart of the tag
                bleh.view(t)?.let { addView(it) }
                if (conn != null && conn.find == UsbFind.UNLOCK_FAILED) addView(button(getString(R.string.try_again)) {
                    lifecycleScope.launch { app.usb.retryUnlock(); render(); usbh.noteSettingsRead() }
                }, lp(WRAP, WRAP).margins(t = dp(12)))
            })
            // a tag in its bootloader (an update that stopped, or a double press of reset)
            if (FirmwareUpdate.active || FirmwareUpdate.bootloaderOnCable(this) != null) content.addView(card(getString(R.string.fw_boot_found), R.drawable.ic_chip) {
                addView(button(getString(R.string.fw_continue)) { openUpdate() }, lp(WRAP, WRAP).margins(t = dp(12)))
            })
            return
        }
        val s = conn.status
        // charging (on the cable)
        if (!ble) pad(listItem(R.drawable.ic_bolt, getString(R.string.svc_charging), s.battery?.let { getString(R.string.svc_battery, it) }))
        // history
        val hist = when {
            syncing -> getString(R.string.svc_syncing)
            synced == null -> getString(R.string.svc_sync_wait)
            synced!! < 0 -> getString(R.string.svc_sync_failed)
            else -> resources.getQuantityString(R.plurals.svc_synced, synced!!, synced!!)
        }
        pad(listItem(R.drawable.ic_sync, hist, getString(R.string.svc_sync_sub), if (syncing) null else button(getString(R.string.svc_sync_again), Btn.TEXT) {
            syncedFor = null; maybeSync()
        }))
        if (syncing) pad(LinearProgressIndicator(this).apply { isIndeterminate = true })
        // firmware
        val v = conn.version
        val latest = BuildConfig.FIRMWARE_LATEST
        val older = v != null && ConsoleParsers.isOlder(v, latest)
        pad(listItem(R.drawable.ic_chip, when {
            v == null -> getString(R.string.svc_fw_unknown)
            older -> getString(R.string.svc_fw_available, v, latest)
            else -> getString(R.string.svc_fw_ok, v)
        }, when {
            ble && older -> getString(R.string.blecon_fw_cable)
            BundledFirmware.available && older -> getString(R.string.fw_service_sub, BundledFirmware.version)
            older -> getString(R.string.svc_fw_later)
            else -> conn.board ?: conn.productName
        }).apply { setOnLongClickListener { consoleDialog(); true } })
        if (ble) { /* firmware updates need the cable */ }
        else if (BundledFirmware.available) pad(hbox {
            addView(button(getString(R.string.fw_title), if (older) Btn.FILLED else Btn.OUTLINED, R.drawable.ic_chip) { openUpdate() })
        })
        else if (older) pad(hbox { addView(button(getString(R.string.svc_fw_update), Btn.TONAL, R.drawable.ic_open) { updateGuide() }) })
        hr()
        h3(getString(R.string.svc_cable_settings))
        // places, reports and the rest live in Tag settings; known devices keep their own screen
        pad(listItem(R.drawable.ic_prefs, getString(R.string.menu_tag_settings), s.intervalMin?.let { Format.every(this, it * 60) } ?: Format.every(this, t.reportPeriodMin * 60)) {
            startActivity(Intent(this, TagSettingsActivity::class.java).putExtra(MainActivity.EXTRA_TAG, t.id))
        })
        pad(listItem(R.drawable.ic_bt, getString(R.string.menu_known), null) {
            startActivity(Intent(this, KnownDevicesActivity::class.java).putExtra(MainActivity.EXTRA_TAG, t.id))
        })
        pad(listItem(R.drawable.ic_send, getString(R.string.svc_test), getString(R.string.svc_test_sub)) {
            lifecycleScope.launch { content.snack(getString(if (TagOps.testReports()) R.string.svc_test_ok else R.string.svc_test_no)) }
        })
        busyText?.let { pad(text(R.style.TextAppearance_LibreTag_Status, it, R.color.ink2)) }
        hr()
        h3(getString(R.string.svc_danger))
        // re-key and reset need the tag in hand and the cable (PROTOCOL.md 6.2)
        if (ble) {
            pad(listItem(R.drawable.ic_key, getString(R.string.svc_rekey), getString(R.string.blecon_needs_cable)).apply { alpha = 0.6f })
            pad(listItem(R.drawable.ic_trash, getString(R.string.svc_reset), getString(R.string.blecon_needs_cable)).apply { alpha = 0.6f })
            hr()
            pad(hbox { addView(button(getString(R.string.blecon_disconnect), Btn.TEXT, R.drawable.ic_bt) { app.usb.closeBle(); render() }) })
        } else {
            pad(listItem(R.drawable.ic_key, getString(R.string.svc_rekey), getString(R.string.svc_rekey_sub)) { rekeyDialog(t) })
            pad(listItem(R.drawable.ic_trash, getString(R.string.svc_reset), getString(R.string.svc_reset_sub)) { resetDialog(t) })
        }
    }

    /** The tag's console as text (a long press on the firmware row): for checks on the bench. */
    private fun consoleDialog() {
        val out = text(R.style.TextAppearance_LibreTag_BodySmall, "", R.color.ink2).apply {
            typeface = android.graphics.Typeface.MONOSPACE
            setTextIsSelectable(true)
        }
        val field = OutlinedField(this).apply { hint = getString(R.string.blecon_console_hint) }
        val input = com.google.android.material.textfield.TextInputEditText(field.context).apply {
            isSingleLine = true
            tag = "console_input"
            // commands, not prose: no autocorrect ("status" became "statusom"); Enter sends
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
                android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEND
        }
        field.addView(input)
        val box = vbox(dp(16)) {
            addView(field, lp())
            addView(android.widget.ScrollView(this@ServiceActivity).apply { addView(out) }, lp(MATCH, dp(320)).margins(t = dp(8)))
        }
        val d = MaterialAlertDialogBuilder(this).setTitle(R.string.blecon_console).setView(box)
            .setNegativeButton(R.string.close, null)
            .setPositiveButton(R.string.blecon_console_send, null).show()
        input.setOnEditorActionListener { _, id, ev ->
            val enter = ev?.keyCode == android.view.KeyEvent.KEYCODE_ENTER && ev.action == android.view.KeyEvent.ACTION_DOWN
            if (id == android.view.inputmethod.EditorInfo.IME_ACTION_SEND || enter) {
                d.getButton(android.content.DialogInterface.BUTTON_POSITIVE).performClick(); true
            } else false
        }
        d.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener {
            val cmd = input.text?.toString()?.trim().orEmpty()
            if (cmd.isEmpty()) return@setOnClickListener
            lifecycleScope.launch {
                val lines = app.usb.command(cmd, quietMs = 800, maxMs = 15_000) ?: listOf("(not connected)")
                out.text = "> $cmd\n" + lines.joinToString("\n")
                DiagLog.i("Console", "> $cmd: ${lines.firstOrNull().orEmpty()}")
            }
        }
    }

    /** New K0 on the tag: everyone it was shared with is cut off from now on. */
    private fun rekeyDialog(t: Tracker) {
        MaterialAlertDialogBuilder(this).setTitle(R.string.svc_rekey).setMessage(R.string.svc_rekey_text)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.svc_rekey_ok) { _, _ ->
                lifecycleScope.launch {
                    val key = app.usb.session { c ->
                        c.command("newkey")
                        val s = c.status()
                        val k = s.keyHex
                        if (k != null) c.command("lock")
                        k
                    }
                    if (key == null) { content.snack(getString(R.string.svc_write_failed)); return@launch }
                    // on a full-access share this phone becomes the owner: the new K0 replaces the shared keys
                    app.store.updateTracker(t.id) { it.copy(keyHex = key, lastN = -1, shares = emptyList(), pending = null,
                        sharedBy = null, sharedControl = false, sharedEpoch = 0, sharedViewHex = "", sharedCmdHex = "",
                        sharedUsbHex = "", sharedIdHex = "") }
                    app.forgetWindow(t.id)
                    usbh.reconnect()
                    content.snack(getString(R.string.svc_rekey_done))
                }
            }.show().getButton(android.content.DialogInterface.BUTTON_POSITIVE)?.setTextColor(col(R.color.crit))
    }

    private fun resetDialog(t: Tracker) {
        MaterialAlertDialogBuilder(this).setTitle(R.string.wz_reset_title).setMessage(R.string.svc_reset_text)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.wz_reset) { _, _ ->
                lifecycleScope.launch {
                    app.usb.command("unpair")
                    app.removeTag(t.id)
                    usbh.reconnect()
                    finish()
                }
            }.show().getButton(android.content.DialogInterface.BUTTON_POSITIVE)?.setTextColor(col(R.color.crit))
    }

    private fun openUpdate() {
        startActivity(Intent(this, FirmwareUpdateActivity::class.java).putExtra(MainActivity.EXTRA_TAG, tagId))
    }

    private fun updateGuide() {
        MaterialAlertDialogBuilder(this).setTitle(R.string.svc_fw_update).setMessage(getString(R.string.svc_fw_steps, BuildConfig.FIRMWARE_LATEST))
            .setPositiveButton(R.string.ok, null).show()
    }
}
