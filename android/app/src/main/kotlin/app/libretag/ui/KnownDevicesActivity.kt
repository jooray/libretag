package app.libretag.ui

import app.libretag.data.Status
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import app.libretag.CommandKind
import app.libretag.R
import app.libretag.data.BleSeenRec
import app.libretag.data.KnownDevice
import app.libretag.data.Tracker
import app.libretag.usb.BleAddressKind
import app.libretag.usb.BleDevice
import app.libretag.usb.ConsoleParsers
import app.libretag.usb.TagOps
import app.libretag.usb.Via
import app.libretag.usb.WriteResult
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** One device the tag saw: its name, else its maker and kind; stable or rotating, with a checkbox when it can be used. */
fun Context.deviceRow(d: BleDevice, checked: Boolean, onToggle: (Boolean) -> Unit): View {
    val stable = d.stable
    val cb = MaterialCheckBox(this).apply { isChecked = checked && stable; isEnabled = stable; isClickable = false }
    val maker = companyName(d.companyId)
    val kind = deviceKind(d)
    val named = d.name?.takeIf { it.isNotBlank() }
    val title = named ?: maker ?: kind?.replaceFirstChar { it.titlecase() } ?: getString(R.string.kd_unnamed)
    val sub = listOfNotNull(maker.takeIf { named != null }, kind.takeIf { named != null || maker != null }, d.address, "${d.rssi} dBm",
        getString(if (stable) R.string.kd_stable else R.string.kd_changes)).joinToString(" · ")
    val row = listItem(null, title, sub, statusTag(getString(if (stable) R.string.kd_tag_stable else R.string.kd_tag_changes), stable)) {
        if (stable) { cb.isChecked = !cb.isChecked; onToggle(cb.isChecked) }
    }
    row.addView(cb, 0, LinearLayout.LayoutParams(WRAP, WRAP).margins(r = dp(8)))
    if (!stable) row.alpha = 0.55f
    return row
}

/** What kind of device it is, from its GAP appearance or a telling service; null when it says nothing. */
fun Context.deviceKind(d: BleDevice): String? {
    val svc = d.uuids16 + listOfNotNull(d.serviceData?.first)
    val res = when {
        0xFDF0 in svc -> R.string.kd_kind_hearing          // ASHA
        0x180D in svc -> R.string.kd_kind_heart
        else -> when (d.appearance?.let { it shr 6 }) {
            0x001 -> R.string.kd_kind_phone
            0x002 -> R.string.kd_kind_computer
            0x003 -> R.string.kd_kind_watch
            0x008, 0x009 -> R.string.kd_kind_tag
            0x00D -> R.string.kd_kind_heart
            0x00F -> R.string.kd_kind_input
            0x021 -> R.string.kd_kind_speaker
            0x025 -> R.string.kd_kind_headphones
            0x029 -> R.string.kd_kind_hearing
            else -> null
        }
    }
    return res?.let { getString(it) }
}

/** A few Bluetooth SIG company ids, so "Apple" reads better than 004C. */
fun Context.companyName(id: Int?): String? = when (id) {
    null, 0xFFFF -> null
    0x004C -> "Apple"
    0x0006 -> "Microsoft"
    0x00E0 -> "Google"
    0x0075 -> "Samsung"
    0x0087 -> "Garmin"
    0x009E -> "Bose"
    0x012D -> "Sony"
    0x0157 -> "Huami"
    0x0171 -> "Amazon"
    0x038F -> "Xiaomi"
    0x0059 -> "Nordic Semiconductor"
    0x02E5 -> "Espressif"
    0x0499 -> "Ruuvi"
    0x0B1C -> "Oura"
    else -> String.format("0x%04X", id)
}

/**
 * Known devices: Bluetooth devices near which the tag stays quiet (SPEC 11.2). The tag scans its
 * surroundings over the cable; only devices with a stable address can be recognised. A phone can
 * pair with the tag (blepair) so the tag knows its rotating address.
 */
class KnownDevicesActivity : ScreenActivity() {
    private lateinit var usbh: UsbHelper
    private lateinit var bleh: BleConsoleHelper
    private lateinit var tagId: String
    private var scan: List<BleDevice>? = null
    private var scanning = false
    private var scanLeft = 0
    private var picked = mutableSetOf<String>()
    private var pairing: String? = null
    private var job: Job? = null
    private var scanBtn: com.google.android.material.button.MaterialButton? = null
    /** a write to the tag runs; [writeAgain]: the list changed meanwhile, write once more after it */
    private var writing = false
    private var writeAgain = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tagId = intent.getStringExtra(MainActivity.EXTRA_TAG) ?: return finish()
        val t = app.store.get(tagId) ?: return finish()
        picked = t.knownDevices.map { it.address }.toMutableSet()
        setTitles(getString(R.string.menu_known), t.name)
        usbh = UsbHelper(this) { render() }
        usbh.start()
        // 0.8.0: or over Bluetooth after a restart of the tag
        bleh = BleConsoleHelper(this, { tagId }) { render() }
        lifecycleScope.launch { repeatOnLifecycle(Lifecycle.State.STARTED) { app.usb.state.collect { render() } } }
        lifecycleScope.launch { repeatOnLifecycle(Lifecycle.State.STARTED) { app.store.trackers.collect { render() } } }
    }

    /** The list's state on the tag, one vocabulary with places: on the tag, writing, waits for the cable. */
    private fun savedChip(t: Tracker, onCable: Boolean): View = placeChip(when {
        writing -> PlaceChip.WRITING
        t.knownOnTag -> PlaceChip.ON_TAG
        onCable -> PlaceChip.NOT_WRITTEN
        else -> PlaceChip.WAITS_CABLE
    })

    /** A pick or a removal: stored, and on the cable written to the tag right away. */
    private fun changed() {
        val t = app.store.get(tagId) ?: return
        if (!app.usb.tagFor(t)) return
        if (writing) { writeAgain = true; return }
        write(t)
    }

    private fun render() {
        val t = app.store.get(tagId) ?: return
        // keep the scroll position while the screen redraws
        val sv = content.parent?.parent as? androidx.core.widget.NestedScrollView
        val y = sv?.scrollY ?: 0
        content.removeAllViews()
        para(getString(R.string.kd_lead_short, t.name))
        val onCable = app.usb.tagFor(t)
        // saved
        h3(getString(R.string.kd_saved))
        if (t.knownDevices.isEmpty()) para(getString(R.string.kd_none))
        for (d in t.knownDevices) pad(listItem(R.drawable.ic_bt, d.name.ifBlank { d.address },
            listOfNotNull(companyName(d.companyId), if (d.kind == "irk") getString(R.string.kd_phone_paired) else d.address).joinToString(" · "),
            hbox {
                gravity = android.view.Gravity.CENTER_VERTICAL
                addView(savedChip(t, onCable))
                addView(iconButton(R.drawable.ic_trash, getString(R.string.a11y_delete_zone, d.name.ifBlank { d.address }), R.color.ink2) {
                    app.store.updateTracker(t.id) { it.copy(knownDevices = it.knownDevices - d, knownOnTag = false) }
                    picked.remove(d.address)
                    changed()
                })
            }, onClick = if (d.kind == "name") null else ({ rename(t, d) })))
        if (t.knownDevices.isNotEmpty() && !t.knownOnTag && !onCable) para(getString(R.string.place_waits_cable_sub, t.name))
        hr()
        // scan over the cable
        h3(getString(R.string.kd_seen_now))
        if (!onCable) {
            para(getString(R.string.kd_need_cable, t.name))
            bleh.view(t)?.let { pad(it) }
        }
        else pad(text(R.style.TextAppearance_LibreTag_BodySmall, getString(R.string.kd_scan_note), R.color.ink3))
        scan?.let { list ->
            if (list.isEmpty()) para(getString(R.string.kd_scan_empty))
            for (d in ConsoleParsers.forPicking(list)) {
                pad(deviceRow(d, d.address in picked) { on ->
                    if (on) picked += d.address else picked -= d.address
                    app.store.updateTracker(t.id) { cur ->
                        val keep = cur.knownDevices.filter { it.address != d.address }
                        cur.copy(knownDevices = if (on) keep + TagOps.knownFrom(d) else keep, knownOnTag = false)
                    }
                    changed()
                })
            }
        }
        // the countdown runs on the button itself
        val sb = button(scanLabel(), Btn.OUTLINED, R.drawable.ic_sync) { startScan() }
        if (scanning) sb.working(true) else sb.isEnabled = onCable
        scanBtn = sb
        pad(FlowRow(this).apply { addView(sb) })
        // the mesh: the tag reports its surroundings (BLE_SCAN)
        hr()
        h3(getString(R.string.kd_mesh_title))
        para(getString(R.string.kd_mesh_text))
        t.bleScan?.let { bs ->
            para(getString(R.string.kd_mesh_last, Format.at(this, bs.at), bs.total))
            for (d in bs.devices.filter { ConsoleParsers.validAddress(it.address) }.sortedWith(
                compareByDescending<BleSeenRec> { !it.name.isNullOrBlank() }.thenByDescending { it.rssi })) {
                val dev = BleDevice(d.address, when (d.kind) { "public" -> BleAddressKind.PUBLIC; "static" -> BleAddressKind.STATIC; "rpa" -> BleAddressKind.RPA; else -> BleAddressKind.NRPA },
                    d.rssi, 1, d.company, d.name)
                pad(deviceRow(dev, d.address in picked) { on ->
                    if (on) picked += d.address else picked -= d.address
                    app.store.updateTracker(t.id) { cur ->
                        val keep = cur.knownDevices.filter { it.address != d.address }
                        cur.copy(knownDevices = if (on) keep + TagOps.knownFrom(dev) else keep, knownOnTag = false)
                    }
                    changed()
                })
            }
        }
        pad(hbox { addView(button(getString(R.string.kd_mesh_go), Btn.OUTLINED, R.drawable.ic_bt) {
            app.queueCommand(t.id, CommandKind.BLE_SCAN)
            val cur = app.store.get(t.id) ?: t
            content.snack(TagText.slip(this@KnownDevicesActivity, cur, Status.of(cur))?.text ?: TagText.cmdName(this@KnownDevicesActivity, CommandKind.BLE_SCAN))
        }.apply { isEnabled = t.canCommand }) })
        // pair this phone
        if (onCable && app.usb.supports("blepair") && app.usb.via == Via.BLE) {
            // blepair needs the peripheral role and the connection the console uses
            hr()
            h3(getString(R.string.kd_pair_title))
            para(getString(R.string.blecon_needs_cable))
        } else if (onCable && app.usb.supports("blepair")) {
            hr()
            h3(getString(R.string.kd_pair_title))
            para(getString(R.string.kd_pair_text))
            pairing?.let { pad(text(R.style.TextAppearance_LibreTag_Status, it)) }
            pad(FlowRow(this).apply {
                addView(button(getString(R.string.kd_pair_go), Btn.TONAL, R.drawable.ic_lock) { pair(t) }.apply { isEnabled = pairing == null })
                addView(button(getString(R.string.kd_pair_settings), Btn.TEXT, R.drawable.ic_open) {
                    runCatching { startActivity(Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS)) }
                })
            })
        }
        sv?.post { sv.scrollTo(0, y) }
    }

    private fun scanLabel() = when {
        scanning -> getString(R.string.kd_scanning, scanLeft)
        scan == null -> getString(R.string.kd_scan)
        else -> getString(R.string.kd_scan_again)
    }

    private fun startScan() {
        if (scanning) return
        scanning = true; scanLeft = 30; render()
        job = lifecycleScope.launch {
            val tick = launch { while (scanLeft > 0) { delay(1000); scanLeft--; scanBtn?.text = scanLabel() } }
            val r = TagOps.bleScan(30)
            tick.cancel()
            scanning = false
            scan = r?.devices ?: emptyList()
            if (r == null) content.snack(getString(R.string.kd_scan_failed))
            render()
        }
    }

    /** Writes the list; only a failure is said (the chips show the rest). */
    private fun write(t: Tracker) {
        writing = true; render()
        lifecycleScope.launch {
            do {
                writeAgain = false
                val cur = app.store.get(t.id) ?: break
                val r = TagOps.writeKnown(cur)
                if (r != WriteResult.OK) {
                    if (r == WriteResult.UNSUPPORTED || r == WriteResult.FAILED)
                        content.snack(getString(if (r == WriteResult.UNSUPPORTED) R.string.kd_fw_old else R.string.svc_write_failed))
                    break
                }
            } while (writeAgain)
            writing = false
            render()
        }
    }

    /** blepair: the tag advertises as "LibreTag"; the owner pairs from the phone's Bluetooth settings. */
    private fun pair(t: Tracker) {
        pairing = getString(R.string.kd_pair_waiting); render()
        runCatching { startActivity(Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS)) }
        lifecycleScope.launch {
            val lines = TagOps.blePair() ?: emptyList()
            val bonded = lines.firstOrNull { it.startsWith("PAIR bonded") }
            pairing = null
            if (bonded != null && bonded.contains("irk")) {
                val anchors = TagOps.readAnchors() ?: emptyList()
                val irk = anchors.lastOrNull { it.kind == "irk" }
                if (irk != null) app.store.updateTracker(t.id) {
                    val addr = irk.address ?: "phone"
                    val same = it.knownDevices.firstOrNull { d -> d.kind == "irk" && d.address == addr }
                    it.copy(knownDevices = it.knownDevices.filter { d -> d !== same } +
                        KnownDevice(addr, same?.name?.takeIf { n -> n.isNotBlank() } ?: phoneName(it.knownDevices), "irk", null, System.currentTimeMillis()),
                        knownOnTag = it.knownOnTag)
                }
                MaterialAlertDialogBuilder(this@KnownDevicesActivity).setTitle(R.string.kd_pair_title).setMessage(R.string.kd_pair_ok)
                    .setPositiveButton(R.string.ok, null).show()
            } else {
                content.snack(getString(if (bonded != null) R.string.kd_pair_no_irk else R.string.kd_pair_failed))
            }
            render()
        }
    }

    /**
     * A name for a newly paired phone. The tracker reports only the phone's identity address
     * (`PAIR bonded with <addr> (public)`), and Android does not tell an app its own Bluetooth
     * address, so the app cannot say whether it was this phone: "Phone", then "Phone 2", "Phone 3".
     */
    private fun phoneName(known: List<KnownDevice>): String {
        val taken = known.map { it.name }.toSet()
        val first = getString(R.string.kd_phone)
        if (first !in taken) return first
        return (2..99).map { getString(R.string.kd_phone_n, it) }.firstOrNull { it !in taken } ?: first
    }

    /** The name is only a label in the app (the tag matches by address or key), so any entry but a name anchor can be renamed. */
    private fun rename(t: Tracker, d: KnownDevice) {
        val field = TextInputEditText(this).apply { setText(d.name); setSingleLine() }
        val box = OutlinedField(this).apply { hint = getString(R.string.field_name); addView(field) }
        val wrap = FrameLayout(this).apply { setPadding(dp(24), dp(8), dp(24), 0); addView(box) }
        MaterialAlertDialogBuilder(this).setTitle(R.string.kd_rename).setView(wrap)
            .setPositiveButton(R.string.save) { _, _ ->
                val n = field.text?.toString()?.trim().orEmpty()
                if (n.isNotEmpty()) app.store.updateTracker(t.id) { cur ->
                    cur.copy(knownDevices = cur.knownDevices.map { if (it.address == d.address && it.kind == d.kind) it.copy(name = n) else it })
                }
            }
            .setNegativeButton(R.string.cancel, null).show()
    }

    override fun onDestroy() { job?.cancel(); super.onDestroy() }
}
