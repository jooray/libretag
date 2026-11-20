package app.libretag.ui

import android.os.Bundle
import android.view.WindowManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import app.libretag.BuildConfig
import app.libretag.usb.Via
import app.libretag.R
import app.libretag.usb.ConsoleParsers
import app.libretag.usb.UsbFind
import app.libretag.usb.UsbState
import app.libretag.usb.dfu.BundledFirmware
import app.libretag.usb.dfu.DfuFail
import app.libretag.usb.dfu.DfuStep
import app.libretag.usb.dfu.FirmwareUpdate
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Update firmware (Service, USB): the tag's version against the one bundled in the APK, and the
 * update over the cable ([FirmwareUpdate]). Also continues with a tag found in its bootloader.
 */
class FirmwareUpdateActivity : ScreenActivity() {
    private lateinit var usbh: UsbHelper

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setTitles(getString(R.string.fw_title), getString(R.string.fw_sub))
        FirmwareUpdate.reset()
        usbh = UsbHelper(this) { render() }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(FirmwareUpdate.state, app.usb.state) { a, _ -> a }.collect { render() }
            }
        }
        usbh.start()
    }

    private fun render() {
        content.removeAllViews()
        val step = FirmwareUpdate.state.value
        if (FirmwareUpdate.active) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        when (step) {
            DfuStep.Idle -> idle()
            is DfuStep.Done -> content.addView(card(getString(R.string.fw_done, step.version), R.drawable.ic_check) {
                step.paired?.let {
                    addView(text(R.style.TextAppearance_LibreTag_BodyMedium, getString(if (it) R.string.fw_done_paired else R.string.fw_done_unpaired),
                        if (it) R.color.ink2 else R.color.crit), lp().margins(t = dp(6)))
                }
                addView(button(getString(R.string.ok)) { FirmwareUpdate.reset(); finish() }, lp(WRAP, WRAP).margins(t = dp(12)))
            })
            is DfuStep.Failed -> failed(step)
            else -> running(step)
        }
    }

    private fun idle() {
        val bundled = BundledFirmware.version
        // a tag already in its bootloader: offer to go on with it
        FirmwareUpdate.bootloaderOnCable(this)?.let {
            content.addView(card(getString(R.string.fw_boot_found), R.drawable.ic_usb) {
                addView(text(R.style.TextAppearance_LibreTag_BodyMedium, getString(R.string.fw_boot_text), R.color.ink2), lp().margins(t = dp(6)))
                if (bundled != null) addView(button(getString(R.string.fw_continue)) { FirmwareUpdate.start(this@FirmwareUpdateActivity, fromBootloader = true) },
                    lp(WRAP, WRAP).margins(t = dp(12)))
            })
        }
        if (!BundledFirmware.available || bundled == null) {
            para(getString(R.string.fw_none_bundled))
            pad(hbox { addView(button(getString(R.string.svc_fw_update), Btn.TONAL, R.drawable.ic_open) { computerGuide() }) })
            return
        }
        val st = app.usb.state.value
        val onBle = app.usb.via == Via.BLE
        val conn = app.usb.connected?.takeIf { !onBle }
        if (conn == null) {
            content.addView(card(getString(R.string.fw_plug), R.drawable.ic_usb) {
                addView(text(R.style.TextAppearance_LibreTag_BodyMedium, when {
                    onBle -> getString(R.string.blecon_fw_cable)
                    else -> when (st) {
                    is UsbState.Connecting -> getString(R.string.wz_connecting)
                    is UsbState.NeedPermission -> getString(R.string.wz_permission)
                    is UsbState.Failed -> getString(R.string.wz_failed, st.message)
                    else -> getString(R.string.svc_plug_text)
                    }
                }, R.color.ink2), lp().margins(t = dp(6)))
            })
            return
        }
        if (conn.find != UsbFind.MINE && conn.find != UsbFind.NEW) { para(getString(R.string.fw_not_mine)); return }
        val v = conn.version
        pad(listItem(R.drawable.ic_chip, getString(R.string.fw_on_tag, v ?: getString(R.string.fw_unknown)), getString(R.string.fw_in_app, bundled)))
        when {
            v == bundled -> para(getString(R.string.fw_same))
            v != null && ConsoleParsers.isOlder(bundled, v) -> para(getString(R.string.fw_newer_on_tag))
        }
        para(getString(R.string.fw_explain))
        pad(hbox { addView(button(getString(R.string.fw_install, bundled)) { FirmwareUpdate.start(this@FirmwareUpdateActivity, fromBootloader = false) }) })
    }

    private fun running(step: DfuStep) {
        val text = when (step) {
            DfuStep.Rebooting -> getString(R.string.fw_step_rebooting)
            DfuStep.WaitingBootloader -> getString(R.string.fw_step_waiting)
            is DfuStep.Permission -> getString(if (step.bootloader) R.string.fw_step_perm_boot else R.string.fw_step_perm_tag)
            DfuStep.Erasing -> getString(R.string.fw_step_erasing)
            is DfuStep.Sending -> getString(R.string.fw_step_sending, pct(step))
            DfuStep.Finishing -> getString(R.string.fw_step_finishing)
            DfuStep.Restarting -> getString(R.string.fw_step_restarting)
            else -> getString(R.string.fw_step_reading)
        }
        content.addView(card(getString(R.string.fw_title), R.drawable.ic_chip) {
            addView(text(R.style.TextAppearance_LibreTag_BodyMedium, text), lp().margins(t = dp(6), b = dp(12)))
            addView(LinearProgressIndicator(this@FirmwareUpdateActivity).apply {
                if (step is DfuStep.Sending) { isIndeterminate = false; max = 100; progress = pct(step) } else isIndeterminate = true
            })
            addView(text(R.style.TextAppearance_LibreTag_Status, getString(R.string.fw_keep), R.color.ink2), lp().margins(t = dp(12)))
            if (step == DfuStep.WaitingBootloader) addView(button(getString(R.string.cancel), Btn.TEXT) { FirmwareUpdate.cancel() }, lp(WRAP, WRAP).margins(t = dp(8)))
        })
    }

    private fun pct(s: DfuStep.Sending) = if (s.total > 0) (100L * s.sent / s.total).toInt() else 0

    private fun failed(f: DfuStep.Failed) {
        val d = f.detail ?: "?"
        val msg = when (f.why) {
            DfuFail.NO_FIRMWARE -> getString(R.string.fw_fail_no_firmware, d)
            DfuFail.NOT_CONNECTED -> getString(R.string.fw_fail_not_connected)
            DfuFail.LOCKED -> getString(R.string.fw_fail_locked, d)
            DfuFail.NO_BOOTLOADER -> getString(R.string.fw_fail_no_bootloader)
            DfuFail.PERMISSION -> getString(R.string.fw_fail_permission)
            DfuFail.TRANSFER -> getString(R.string.fw_fail_transfer, d)
            DfuFail.NO_RETURN -> getString(R.string.fw_fail_no_return)
            DfuFail.WRONG_VERSION -> getString(R.string.fw_fail_wrong_version, d, BundledFirmware.version ?: "?")
        }
        content.addView(card(getString(R.string.fw_fail_title), R.drawable.ic_alert) {
            addView(text(R.style.TextAppearance_LibreTag_BodyMedium, msg, R.color.ink2), lp().margins(t = dp(6)))
            val boot = FirmwareUpdate.bootloaderOnCable(this@FirmwareUpdateActivity)
            addView(hbox {
                if (boot != null) addView(button(getString(R.string.fw_continue)) { FirmwareUpdate.start(this@FirmwareUpdateActivity, fromBootloader = true) })
                else addView(button(getString(R.string.fw_try_again)) { FirmwareUpdate.reset(); usbh.reconnect() })
            }, lp().margins(t = dp(12)))
        })
    }

    private fun computerGuide() {
        MaterialAlertDialogBuilder(this).setTitle(R.string.svc_fw_update).setMessage(getString(R.string.svc_fw_steps, BuildConfig.FIRMWARE_LATEST))
            .setPositiveButton(R.string.ok, null).show()
    }
}
