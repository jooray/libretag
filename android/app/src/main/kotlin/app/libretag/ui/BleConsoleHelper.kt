package app.libretag.ui

import android.Manifest
import android.bluetooth.BluetoothManager
import android.view.View
import android.widget.LinearLayout
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import app.libretag.CommandKind
import app.libretag.LibreTagApp
import app.libretag.R
import app.libretag.ble.BleCommandBeacon
import app.libretag.ble.BleConsoleScanner
import app.libretag.data.Tracker
import app.libretag.mesh.CommandRoute
import app.libretag.proto.BleConsole
import app.libretag.usb.ConsoleParsers
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import app.libretag.usb.UsbState
import app.libretag.usb.UsbTagSession
import app.libretag.usb.Via
import com.google.android.material.progressindicator.LinearProgressIndicator
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * "Connect over Bluetooth" (firmware 0.8.0, PROTOCOL.md 6.2) next to UsbHelper on the screens that
 * use the tag's console. While such a screen is in front and the Nearby devices permission is there,
 * the app scans for the tag's console advert; seeing it shows a strip with the countdown and one
 * tap connects. Otherwise the button opens a sheet that tells the owner to restart the tag. The
 * cable wins: with a tag on the cable nothing here shows. Android 12 and newer only.
 */
class BleConsoleHelper(private val a: AppCompatActivity, private val tagId: () -> String?, private val onChange: () -> Unit) :
    DefaultLifecycleObserver {
    private val app get() = LibreTagApp.instance
    private var acquired = false
    private var jobs = mutableListOf<Job>()
    private var pending: Tracker? = null
    private var lastSeen = false

    private var pendingRemote: Tracker? = null

    private val perms = a.registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        if (r.values.all { it }) {
            scan()
            pending?.let { connect(it) }
            pendingRemote?.let { openRemote(it) }
        } else a.findViewById<View>(android.R.id.content)?.snack(a.getString(R.string.blecon_permission))
        pending = null
        pendingRemote = null
    }

    init { a.lifecycle.addObserver(this) }

    override fun onStart(owner: LifecycleOwner) {
        scan()
        jobs += a.lifecycleScope.launch {
            // the strip appears and counts down; a lost link says so
            while (isActive) {
                val id = tagId()
                val seen = id != null && app.ble.current(id) != null
                if (seen || seen != lastSeen) onChange()
                lastSeen = seen
                delay(if (seen) 5_000 else 1_000)
            }
        }
        jobs += a.lifecycleScope.launch {
            app.usb.bleNote.collect { n ->
                when (n) {
                    UsbTagSession.BleNote.RECONNECTED -> { snack(R.string.blecon_reconnected); app.usb.bleNoteShown() }
                    else -> {}
                }
                onChange()
            }
        }
        jobs += a.lifecycleScope.launch {
            // the keepalive: the tag ends a silent session after 2 minutes
            while (isActive) {
                delay(45_000)
                val id = tagId()
                if (app.usb.via == Via.BLE && id != null && app.usb.connected?.tagId == id) app.usb.keepAlive()
            }
        }
    }

    override fun onStop(owner: LifecycleOwner) {
        jobs.forEach { it.cancel() }
        jobs.clear()
        if (acquired) { app.ble.release(); acquired = false }
    }

    private fun scan() {
        if (!BleConsoleScanner.permitted(a)) return
        if (!acquired) { app.ble.acquire(a); acquired = true } else app.ble.start(a)
    }

    private fun snack(id: Int) { a.findViewById<View>(android.R.id.content)?.snack(a.getString(id)) }

    /** Offered for this tag at all: Android 12+, our own tag with its key, not on the cable. */
    fun offered(t: Tracker): Boolean = BleConsoleScanner.supported && !t.isShared && app.keysFor(t) != null &&
        app.usb.via != Via.USB && !(app.usb.via == Via.BLE && app.usb.tagFor(t))

    /**
     * The block for a screen that waits for a connection: the strip when the tag is listening, else
     * "Connect over Bluetooth" (or why not). Null when nothing is offered.
     */
    fun view(t: Tracker): View? {
        if (!offered(t)) return null
        val ctx = a
        val st = app.usb.state.value
        return ctx.vbox {
            when (app.usb.bleNote.value) {
                UsbTagSession.BleNote.RECONNECTING -> addNote(ctx.getString(R.string.blecon_reconnecting))
                UsbTagSession.BleNote.LOST -> addNote(ctx.getString(R.string.blecon_lost))
                UsbTagSession.BleNote.TAG_CLOSED -> addNote(ctx.getString(R.string.blecon_tag_closed))
                else -> {}
            }
            val seen = app.ble.current(t.id)
            when {
                st is UsbState.Connecting -> {}
                seen != null -> addView(ctx.hbox {
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    background = ctx.rounded(ctx.col(R.color.surf3), 12f, ctx.col(R.color.line))
                    setPadding(dp(16), dp(10), dp(8), dp(10))
                    addView(ctx.icon(R.drawable.ic_bt, R.color.ink2), LinearLayout.LayoutParams(dp(22), dp(22)).margins(r = dp(12)))
                    addView(ctx.text(R.style.TextAppearance_LibreTag_BodyMedium, ctx.getString(R.string.blecon_listening, t.name, seen.leftNow())),
                        LinearLayout.LayoutParams(0, WRAP, 1f))
                    addView(ctx.button(ctx.getString(R.string.blecon_connect_short), Btn.TONAL) { connect(t) }, lp(WRAP, WRAP).margins(l = dp(8)))
                }, lp().margins(t = dp(12)))
                else -> {
                    // always offered: the button asks for the Nearby devices permission, and the app's
                    // record of the firmware may be old (a tag flashed elsewhere); the advert or a
                    // session corrects it
                    addView(ctx.button(ctx.getString(R.string.blecon_connect), Btn.OUTLINED, R.drawable.ic_bt) { connect(t) },
                        lp(WRAP, WRAP).margins(t = dp(12)))
                    if (!BleConsoleScanner.firmwareHas(t) && t.firmware != null)
                        addNote(ctx.getString(R.string.blecon_needs_fw, BleConsole.FIRMWARE, t.firmware))
                    remoteBlock(t)
                }
            }
        }.takeIf { it.childCount > 0 }
    }

    /** 0.9.2: open the tag's Bluetooth without a restart, by the BLE_CONSOLE command (CommandRoute). */
    fun remoteOffered(t: Tracker): Boolean = t.canCommand && t.lastN >= 0 &&
        t.firmware?.let { !ConsoleParsers.isOlder(it, REMOTE_FW) } ?: true

    private fun LinearLayout.remoteBlock(t: Tracker) {
        if (!remoteOffered(t)) return
        val ctx = a
        val p = t.pending?.takeIf { it.kind == CommandKind.BLE_CONSOLE.name }
        if (p == null) {
            addView(ctx.button(ctx.getString(R.string.blecon_remote), Btn.TEXT, R.drawable.ic_send) { openRemote(t) },
                lp(WRAP, WRAP).margins(t = dp(4)))
            return
        }
        val via = runCatching { CommandRoute.Via.valueOf(p.via) }.getOrNull()
        val at = app.expectedAt(t, via)
        addNote(if (at != null) ctx.getString(R.string.blecon_remote_at, t.name, Format.clock(ctx, at))
            else ctx.getString(R.string.blecon_remote_next, t.name))
        addNote(ctx.getString(when (via) {
            CommandRoute.Via.BLE -> R.string.blecon_remote_via_ble
            CommandRoute.Via.ZERO_HOP -> R.string.blecon_remote_via_node
            else -> R.string.blecon_remote_via_nostr
        }))
        addNote(ctx.getString(R.string.blecon_remote_wait))
        if (p.sentAt == null) addView(ctx.button(ctx.getString(R.string.cancel), Btn.TEXT) { app.cancelCommand(t.id); onChange() },
            lp(WRAP, WRAP).margins(t = dp(4)))
    }

    /** Queue BLE_CONSOLE: the Nearby devices permission first (the phone's advert is the first way). */
    fun openRemote(t: Tracker) {
        if (!BleConsoleScanner.permitted(a) || !BleCommandBeacon.granted(a)) {
            pendingRemote = t
            perms.launch(REMOTE_PERMS)
            return
        }
        val other = app.store.get(t.id)?.pending?.takeIf { it.kind != CommandKind.BLE_CONSOLE.name }
        val go = {
            app.queueCommand(t.id, CommandKind.BLE_CONSOLE)
            scan()
            onChange()
        }
        if (other == null) { go(); return }
        MaterialAlertDialogBuilder(a).setTitle(R.string.blecon_remote_replace_title)
            .setMessage(a.getString(R.string.blecon_remote_replace, TagText.cmdLabel(a, other) ?: other.label))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.blecon_remote_replace_ok) { _, _ -> go() }
            .show()
    }

    private fun LinearLayout.addNote(s: String) =
        addView(context.text(R.style.TextAppearance_LibreTag_BodySmall, s, R.color.ink2), lp().margins(t = dp(8)))

    /** Connect now when the tag is listening, else the restart sheet that waits for it. */
    fun connect(t: Tracker) {
        if (!BleConsoleScanner.permitted(a)) {
            pending = t
            perms.launch(arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT))
            return
        }
        if (a.getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled != true) { snack(R.string.blecon_bt_off); return }
        app.usb.bleNoteShown()
        scan()
        val seen = app.ble.current(t.id)
        if (seen != null) {
            a.lifecycleScope.launch {
                val r = app.usb.connectBle(a, seen)
                if (r is UsbState.Failed) a.findViewById<View>(android.R.id.content)?.snack(a.getString(R.string.blecon_failed, r.message))
                onChange()
            }
            return
        }
        restartSheet(t)
    }

    private fun restartSheet(t: Tracker) {
        val s = Sheet(a, a.getString(R.string.blecon_sheet_title))
        s.p(a.getString(R.string.blecon_restart, t.name))
        s.p(a.getString(R.string.blecon_once))
        val status = a.text(R.style.TextAppearance_LibreTag_BodyMedium, a.getString(R.string.blecon_looking, t.name))
        val bar = LinearProgressIndicator(a).apply { isIndeterminate = true }
        s.body.addView(status, lp().margins(t = a.dp(8), b = a.dp(8)))
        s.body.addView(bar)
        val job = a.lifecycleScope.launch {
            val seen = withTimeoutOrNull(4 * 60_000L) {
                app.ble.seen.first { m -> m[t.id]?.let { System.currentTimeMillis() - it.at < 10_000 && it.leftNow() > 0 } == true }[t.id]
            }
            if (seen == null) {
                status.text = a.getString(R.string.blecon_not_seen, t.name)
                bar.visibility = View.GONE
                return@launch
            }
            status.text = a.getString(R.string.blecon_found)
            val r = app.usb.connectBle(a, seen)
            if (r is UsbState.Connected) s.dialog.dismiss()
            else {
                status.text = a.getString(R.string.blecon_failed, (r as? UsbState.Failed)?.message ?: "")
                bar.visibility = View.GONE
            }
            onChange()
        }
        s.dialog.setOnDismissListener { job.cancel() }
        s.show()
    }

    companion object {
        /** firmware that takes BLE_CONSOLE (opcode 0x06) */
        const val REMOTE_FW = "0.9.2"
        private val REMOTE_PERMS = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S)
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE)
        else emptyArray()
    }
}
