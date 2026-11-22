package app.libretag.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import app.libretag.CommandKind
import app.libretag.R
import app.libretag.data.BatteryModel
import app.libretag.data.BatterySample
import app.libretag.data.CritBattery
import app.libretag.data.Status
import app.libretag.data.Tracker
import app.libretag.nostr.DmPublish
import app.libretag.proto.Command
import app.libretag.proto.LibreTag
import app.libretag.usb.TagOps
import app.libretag.usb.Via
import app.libretag.usb.WriteResult
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Tag settings (tag menu), one screen in the order of the mental model (ui-review 2.6): the tag
 * (Edit), Reports (how often away, how often it listens), Quiet (places, the owner's devices, not
 * moving), Tell me (not heard, low battery) and Nostr. Every row shows its value and what it does;
 * a value that is not on the tag yet says so on the row ("goes out ~14:31", "waits for cable").
 */
class TagSettingsActivity : ScreenActivity() {
    private var tagId: String? = null
    private var places: PlacesView? = null
    private lateinit var usbh: UsbHelper
    private lateinit var bleh: BleConsoleHelper
    /** the tag was connected at the last render (a new connection writes what waits for it) */
    private var wasConnected = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tagId = intent.getStringExtra(MainActivity.EXTRA_TAG)
        // a tag plugged in while this is open connects (places, settings written over the cable)
        usbh = UsbHelper(this)
        usbh.start()
        // 0.8.0: or over Bluetooth after a restart of the tag
        bleh = BleConsoleHelper(this, { tagId }) { render() }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                app.store.trackers.combine(app.usb.state) { _, _ -> }.collect { render() }
            }
        }
    }

    private fun tag(): Tracker? = tagId?.let { app.store.get(it) }

    private fun render() {
        val t = tag() ?: run { finish(); return }
        val scrollY = (content.parent?.parent as? View)?.scrollY ?: 0
        content.removeAllViews()
        setTitles(getString(R.string.ts_title), t.name)
        header(t)
        if (t.hasConsole) {
            reports(t)
            hr()
            quiet(t)
        }
        hr()
        tellMe(t)
        if (!t.isShared) nostr(t)
        if (t.hasConsole) pendingLine(t)
        content.post { (content.parent?.parent as? View)?.scrollTo(0, scrollY) }
    }

    // ---- the tag ----

    private fun header(t: Tracker) {
        val row = hbox {
            minimumHeight = dp(64)
            addView(ImageView(this@TagSettingsActivity).apply { setImageDrawable(BlazeDrawable(this@TagSettingsActivity, t.markColor, t.markSymbol)) },
                LinearLayout.LayoutParams(dp(25), dp(30)).margins(r = dp(16)))
            addView(vbox {
                addView(text(R.style.TextAppearance_LibreTag_TitleMedium, t.name).apply { ellipsize1() })
                addView(text(R.style.TextAppearance_LibreTag_BodySmall,
                    TagText.typeName(this@TagSettingsActivity, t.type).replaceFirstChar { it.uppercase() } + " · " +
                        BlazeColors.markName(this@TagSettingsActivity, t.markColor, t.markSymbol), R.color.ink2))
            }, LinearLayout.LayoutParams(0, WRAP, 1f))
            addView(button(getString(R.string.ts_edit), Btn.TEXT, R.drawable.ic_edit) { TagSheets.edit(this@TagSettingsActivity, t) })
        }
        content.addView(row, lp().margins(l = dp(16), t = dp(4), r = dp(8)))
    }

    // ---- Reports ----

    private fun reports(t: Tracker) {
        h3(getString(R.string.ts_reports))
        val pending = t.pending?.takeIf { it.kind == CommandKind.INTERVAL.name }
        val pendingCmd = pending?.let { p -> LibreTag.hexToBytes(p.body)?.let { Command.decode(it) } }
        val wakeMin = pendingCmd?.let { Command.idleIntervalMin(it) } ?: t.intervalMin
        val every = (pendingCmd?.let { Command.idleReportEvery(it) } ?: t.reportEvery).coerceAtLeast(1)
        val periodMin = wakeMin * every
        val goesOut = pending?.let { Status.of(t).commandAt }?.let { getString(R.string.ts_goes_out, Format.clock(this, it)) }

        label(getString(R.string.ts_away_reports), goesOut)
        pad(chips(Status.IDLE_CHOICES_MIN, periodMin, { Format.every(this, it * 60) }) { m ->
            // keep the listen interval when it still fits, else listen every hour (also away from a place)
            val (w, e) = Status.splitForPeriod(m, wakeMin, every)
            setInterval(t, w, e)
        })
        if (pending == null) perCharge(t)?.let { small(getString(R.string.ts_per_charge, it)) }

        // listen for commands more often than it reports
        val listensAt = t.checkS > 0 && every > 1
        label(getString(R.string.ts_listens), null)
        val opts = Status.CHECK_EVERY_CHOICES_MIN.filter { it < periodMin && periodMin % it == 0 } + periodMin
        pad(chips(opts, if (listensAt) wakeMin else periodMin,
            { if (it == periodMin) getString(R.string.ts_listens_with_report) else Format.every(this, it * 60) }) { c ->
            val (w, e) = Status.wakeSplit(periodMin, c)
            setInterval(t, w, e)
        })
        small(getString(R.string.ts_cmd_waits, Format.duration(this, (if (listensAt) wakeMin else periodMin) * 60_000L)))
    }

    /** "5 weeks": a full charge at the pace measured so far; null until [BatteryModel] has an estimate. */
    private fun perCharge(t: Tracker): String? {
        val samples = t.positions.filter { it.battery != null && it.receivedAt > 0 }.map { BatterySample(it.receivedAt, it.battery!!, it.mode) }
        val e = BatteryModel.estimate(samples) ?: return null
        if (e.capped || e.ratePctPerDay <= 0) return null
        val d = (100 / e.ratePctPerDay).toInt()
        return when {
            d < 2 -> null
            d < 14 -> resources.getQuantityString(R.plurals.days, d, d)
            d < 60 -> resources.getQuantityString(R.plurals.weeks, d / 7, d / 7)
            else -> resources.getQuantityString(R.plurals.months, d / 30, d / 30)
        }
    }

    // ---- Quiet ----

    private fun quiet(t: Tracker) {
        h3(getString(R.string.ts_quiet))
        val waits = getString(R.string.ts_waits_cable)

        // places: the shared list (one instance, so it keeps its cable state across re-renders)
        val pv = places ?: PlacesView(this, t.id, showTitle = false).also { places = it }
        (pv.parent as? ViewGroup)?.removeView(pv)
        pad(pv)

        // the owner's devices: on = Things (quiet while one is near), off = People
        val near = !t.people
        val devices = t.knownDevices.map { it.name.ifBlank { it.address } }
        val devWait = near && (t.cableDirty || (devices.isNotEmpty() && !t.knownOnTag))
        val devSub = when {
            !near && t.type == "person" -> getString(R.string.ts_devices_people_note)
            else -> withState(devices.joinToString(", ").ifEmpty { getString(R.string.ts_devices_none) }, if (devWait) waits else null)
        }
        pad(switchItem(R.drawable.ic_bt, getString(R.string.ts_devices_near), devSub, near,
            onOpen = { startActivity(Intent(this, KnownDevicesActivity::class.java).putExtra(MainActivity.EXTRA_TAG, t.id)) }) { on ->
            change(t) { it.copy(use = if (on) Tracker.USE_THINGS else Tracker.USE_PEOPLE) }
        })

        // not moving (cable)
        val after = t.stillAfterEff
        val stillSub = when {
            after > 0 -> withState(getString(R.string.ts_still_after, Format.duration(this, after.toLong() * t.reportPeriodMin * 60_000L)), if (t.cableDirty) waits else null)
            else -> withState(getString(R.string.ts_still_off), if (t.cableDirty) waits else null)
        }
        pad(switchItem(R.drawable.ic_clock, getString(R.string.ts_still), stillSub, after > 0,
            onOpen = if (after > 0) ({ stillSheet(t) }) else null) { on ->
            change(t) { it.copy(stillAfter = if (on) Tracker.DEFAULT_STILL_AFTER else 0) }
        })
    }

    /** After how many reports in one spot it goes quiet, and how often it still sends a sign of life. */
    private fun stillSheet(t0: Tracker) {
        val s = Sheet(this, getString(R.string.ts_still), t0.name)
        s.label(getString(R.string.ts_still_sheet_after))
        s.body.addView(chips(listOf(2, 3, 4, 6), t0.stillAfterEff, {
            resources.getQuantityString(R.plurals.reports_n, it, it) + " (" + Format.duration(this, it.toLong() * t0.reportPeriodMin * 60_000L) + ")"
        }) { v -> tag()?.let { t -> change(t) { it.copy(stillAfter = v) } } })
        s.label(getString(R.string.ts_still_sheet_life))
        s.body.addView(chips(listOf(2, 4, 6, 8, 12), t0.stillHbH, { Format.every(this, it * 3600) }) { v ->
            tag()?.let { t -> change(t) { it.copy(stillHbH = v) } }
        })
        s.foot(button(getString(R.string.done)) { s.dialog.dismiss() })
        s.show()
    }

    // ---- Tell me ----

    /** The tag's own switches; the app-wide copies are gone (folded into each tag at app start). */
    private fun tellMe(t: Tracker) {
        h3(getString(R.string.ts_tell))
        val al = t.alerts
        pad(switchRow(getString(R.string.ts_not_heard),
            getString(R.string.ts_not_heard_sub, Format.duration(this, Status.SILENT_MARGIN_MS), Format.every(this, t.reportPeriodMin * 60)), al.silent) { on ->
            app.store.updateTracker(t.id) { it.copy(alerts = it.alerts.copy(silent = on)) }
        })
        pad(switchRow(getString(R.string.ts_low_batt), getString(R.string.ts_low_batt_sub, Status.LOW_BATTERY_PCT, CritBattery.PCT), al.lowBattery) { on ->
            app.store.updateTracker(t.id) { it.copy(alerts = it.alerts.copy(lowBattery = on)) }
        })
    }

    // ---- Nostr ----

    private fun nostr(t: Tracker) {
        val npub = app.trackerNpub(t)
        hr()
        h3(getString(R.string.ts_nostr))
        if (npub != null) pad(listItem(R.drawable.ic_person, getString(R.string.menu_follow), null, icon(R.drawable.ic_chev_r, R.color.ink2)) {
            TagSheets.follow(this, t)
        })
        pad(listItem(R.drawable.ic_send, getString(R.string.menu_welcome_dm)) { sendWelcome(t) })
    }

    /** "Send welcome message again": the result per relay (OK count, or why each relay refused). */
    private fun sendWelcome(t: Tracker) {
        if (app.store.ownerNpub == null) { content.snack(getString(R.string.welcome_no_owner), long = true); return }
        content.snack(getString(R.string.welcome_sending))
        lifecycleScope.launch {
            val r = app.sendWelcome(t) ?: return@launch
            if (r.ok > 0) content.snack(resources.getQuantityString(R.plurals.welcome_sent, r.ok, r.ok), long = true)
            else MaterialAlertDialogBuilder(this@TagSettingsActivity)
                .setTitle(R.string.menu_welcome_dm)
                .setMessage(getString(R.string.welcome_failed, r.failed.joinToString("\n") { "${it.host}: ${relayReason(it.reason)}" }))
                .setPositiveButton(R.string.close, null)
                .show()
        }
    }

    private fun relayReason(r: String) = when (r) {
        DmPublish.TIMEOUT -> getString(R.string.relay_no_answer)
        DmPublish.UNREACHABLE -> getString(R.string.relay_unreachable)
        else -> r
    }

    // ---- what waits for the cable ----

    private fun pendingLine(t: Tracker) {
        val n = listOf(
            t.cableDirty,
            !t.wifiTag && t.zones.isNotEmpty() && !t.zonesOnTag,
            !t.people && t.knownDevices.isNotEmpty() && !t.knownOnTag,
        ).count { it }
        val connected = app.usb.tagFor(t)
        // a connection that just came (cable or Bluetooth) takes the settings that wait for it
        if (connected && !wasConnected && t.cableDirty) writeIfConnected()
        wasConnected = connected
        // reading the settings, the Wi-Fi snap and pending writes all work over Bluetooth too: always offered
        val ble = if (!connected) bleh.view(t) else null
        if (n == 0 && ble == null) return
        hr()
        if (n > 0) {
            val txt = if (connected && t.cableDirty) getString(R.string.set_cable_writing)
            else resources.getQuantityString(R.plurals.ts_changes_wait, n, n)
            content.addView(text(R.style.TextAppearance_LibreTag_BodyMedium, txt, R.color.ink2).apply {
                setCompoundDrawablesRelative(tinted(if (app.usb.via == Via.BLE) R.drawable.ic_bt else R.drawable.ic_usb, col(R.color.ink2), 20), null, null, null)
                compoundDrawablePadding = dp(12)
            }, lp().margins(l = dp(16), t = dp(12), r = dp(16), b = dp(8)))
        }
        ble?.let { pad(it) }
    }

    // ---- building blocks ----

    private fun label(s: String, state: String?) = content.addView(hbox {
        addView(text(R.style.TextAppearance_LibreTag_LabelLarge, s, R.color.ink), LinearLayout.LayoutParams(0, WRAP, 1f))
        state?.let { addView(text(R.style.TextAppearance_LibreTag_LabelMedium, it, R.color.ink2), lp(WRAP, WRAP).margins(l = dp(12))) }
    }, lp().margins(l = dp(16), t = dp(12), r = dp(16), b = dp(2)))

    private fun small(s: String) = content.addView(text(R.style.TextAppearance_LibreTag_BodySmall, s, R.color.ink2),
        lp().margins(l = dp(16), t = dp(2), r = dp(16), b = dp(4)))

    private fun withState(sub: String, state: String?) = if (state == null) sub else "$sub · $state"

    /** A row with a switch at the end: the switch changes the value, the row opens its details ([onOpen]). */
    private fun switchItem(icon: Int, title: String, sub: String, on: Boolean, enabled: Boolean = true,
                           onOpen: (() -> Unit)? = null, onChange: (Boolean) -> Unit): LinearLayout {
        val sw = MaterialSwitch(this).apply { isChecked = on; isEnabled = enabled; contentDescription = title }
        val row = listItem(icon, title, sub, sw, onOpen ?: { if (enabled) sw.toggle() })
        sw.setOnCheckedChangeListener { _, c -> onChange(c) }
        return row
    }

    private fun chips(values: List<Int>, cur: Int, label: (Int) -> String, onPick: (Int) -> Unit) = ChipGroup(this).apply {
        isSingleSelection = true; isSelectionRequired = true
        for (v in values) addView(Chip(this@TagSettingsActivity).apply {
            text = label(v); isCheckable = true; isChecked = v == cur; id = View.generateViewId()
            setOnClickListener { onPick(v) }
        })
    }

    // ---- writing ----

    /** A setting the tag only takes over the cable: kept in the app, written now if connected. */
    private fun change(t: Tracker, f: (Tracker) -> Tracker) {
        app.store.updateTracker(t.id) { f(it).copy(cableDirty = true) }
        writeIfConnected()
    }

    private fun writeIfConnected() {
        val t = tag() ?: return
        if (!app.usb.tagFor(t)) return
        lifecycleScope.launch {
            val r = TagOps.writeSettings(t)
            content.snack(getString(when (r) {
                WriteResult.OK -> R.string.set_written
                WriteResult.UNSUPPORTED -> R.string.kd_fw_old
                else -> R.string.svc_write_failed
            }))
        }
    }

    /** [min]: wake-up interval; [every]: report at every n-th wake-up. Cable now, else by radio. */
    private fun setInterval(t: Tracker, min: Int, every: Int = 1) {
        // listening between reports needs a listen window; the tag's default when it had none
        if (every > 1 && t.checkS <= 0) app.store.updateTracker(t.id) { it.copy(checkS = Tracker.DEFAULT_CHECK_S, cableDirty = true) }
        val same = min == t.intervalMin && every == t.reportEvery
        if (app.usb.tagFor(t)) {
            if (same && t.pending?.kind != CommandKind.INTERVAL.name) return
            lifecycleScope.launch {
                if (TagOps.setInterval(min, every)) {
                    app.store.updateTracker(t.id) { it.copy(intervalMin = min, reportEvery = every, pending = it.pending?.takeIf { p -> p.kind != CommandKind.INTERVAL.name }) }
                    content.snack(getString(R.string.set_written))
                } else content.snack(getString(R.string.svc_write_failed))
            }
            return
        }
        if (same) {
            if (t.pending?.kind == CommandKind.INTERVAL.name) app.cancelCommand(t.id)
            return
        }
        if (!t.canCommand) return
        val cur = app.store.get(t.id) ?: t
        val checkS = when {
            cur.checkS > 0 -> cur.checkS
            else -> Command.CHECK_OFF
        }
        // the row itself shows "goes out ~14:31" from now on
        app.queueCommand(t.id, CommandKind.INTERVAL, min, checkS, every)
    }
}
