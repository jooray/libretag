package app.libretag.ui

import app.libretag.usb.ConsoleCommands
import app.libretag.usb.Via
import app.libretag.usb.UsbTagSession
import app.libretag.data.Status
import app.libretag.BuildConfig
import app.libretag.DiagLog
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.NestedScrollView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import app.libretag.LibreTagApp
import app.libretag.R
import app.libretag.data.Marks
import app.libretag.data.Tracker
import app.libretag.data.Zone
import app.libretag.support.SupportAsk
import app.libretag.support.SupportCheckout
import app.libretag.usb.ConsoleParsers
import app.libretag.usb.TagOps
import app.libretag.usb.UsbFind
import app.libretag.usb.UsbState
import app.libretag.usb.WifiZoneEntry
import app.libretag.usb.WriteResult
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Add tag (USB), four steps (ui-review 2.7): plug in (new / locked to you / unlock fails /
 * someone else's), name (type and mark), where it rests (its places), done (locked to this phone,
 * the defaults it got, known devices for things, welcome DM, key backup offer, support strip).
 * Long work (a Wi-Fi snap) runs in its own sheet, and the place list refreshes in place, so the
 * page is never rebuilt (or scrolled to the top) while the tag works.
 */
class AddTagActivity : AppCompatActivity() {
    private val app get() = LibreTagApp.instance
    private lateinit var usbh: UsbHelper
    private var step = WizardSteps.PLUG
    private var name = ""
    private var type = "thing"
    private var mark: Pair<String, String> = Marks.free(app.store.trackers.value.map { it.markColor to it.markSymbol })
    private var markOpen = false
    /** GPS places added here, written at pairing */
    private var zones = mutableListOf<Zone>()
    /** notify choices for Wi-Fi places snapped here (the tag has the places already), applied once paired */
    private val wifiNotify = mutableListOf<Triple<String, Boolean, Boolean>>()
    private var zoneEditor: ZoneEditor? = null
    private var restList: LinearLayout? = null
    private var pairing = false
    private var pairedId: String? = null
    private var partial = false
    private var resetting = false
    private var supportShown = false

    private lateinit var progLabel: android.widget.TextView
    private lateinit var prog: LinearProgressIndicator
    private lateinit var body: FrameLayout
    private lateinit var backBtn: View
    private lateinit var nextBtn: com.google.android.material.button.MaterialButton

    private val steps get() = listOf(R.string.wz_s1, R.string.wz2_s2, R.string.wz2_s3, R.string.wz_s6)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        usbh = UsbHelper(this) { if (step == WizardSteps.PLUG) render() }
        val bar = hbox {
            minimumHeight = dp(64)
            setPadding(dp(4), 0, dp(12), 0)
            addView(iconButton(R.drawable.ic_close, getString(R.string.wz_cancel)) { close() })
            addView(text(R.style.TextAppearance_LibreTag_TitleLarge, getString(R.string.add_tag)), LinearLayout.LayoutParams(0, WRAP, 1f).margins(l = dp(4)))
        }
        progLabel = text(R.style.TextAppearance_LibreTag_LabelMedium, color = R.color.ink3)
        prog = LinearProgressIndicator(this).apply { max = WizardSteps.COUNT; trackCornerRadius = dp(2); trackThickness = dp(4) }
        body = FrameLayout(this)
        backBtn = button(getString(R.string.back), Btn.OUTLINED, R.drawable.ic_back) { go(step - 1) }
        nextBtn = button(getString(R.string.next), Btn.FILLED) { next() }
        nextBtn.iconGravity = com.google.android.material.button.MaterialButton.ICON_GRAVITY_TEXT_END
        val foot = hbox {
            setPadding(dp(16), dp(12), dp(16), dp(16))
            addView(backBtn)
            addView(View(this@AddTagActivity), LinearLayout.LayoutParams(0, 1, 1f))
            addView(nextBtn)
        }
        val col = vbox {
            setBackgroundColor(col(R.color.bg))
            addView(bar)
            addView(vbox { setPadding(dp(16), 0, dp(16), dp(10)); addView(progLabel); addView(prog, lp().margins(t = dp(6))) })
            addView(body, LinearLayout.LayoutParams(MATCH, 0, 1f))
            addView(divider())
            addView(foot)
        }
        setContentView(col)
        col.insetPadding(top = true, bottom = true)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { if (WizardSteps.inFlow(step)) go(step - 1) else close() }
        })
        lifecycleScope.launch { repeatOnLifecycle(Lifecycle.State.STARTED) { app.usb.state.collect { if (step == WizardSteps.PLUG) render() } } }
        usbh.start()
        render()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.usbDevice()?.let { usbh.start(it) }
    }

    private fun close() {
        if (WizardSteps.inFlow(step)) {
            MaterialAlertDialogBuilder(this).setTitle(R.string.wz_stop_title).setMessage(R.string.wz_stop_text)
                .setNegativeButton(R.string.wz_keep_going, null).setPositiveButton(R.string.wz_stop) { _, _ -> finish() }.show()
        } else finish()
    }

    private fun go(s: Int) {
        step = s.coerceIn(0, WizardSteps.DONE)
        render()
    }

    private fun next() {
        when (step) {
            WizardSteps.REST -> { go(WizardSteps.DONE); pair() }
            WizardSteps.DONE -> {
                startActivity(Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_TAG, pairedId)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
                finish()
            }
            else -> go(step + 1)
        }
    }

    private fun scroll(v: View): View = NestedScrollView(this).apply { addView(v); isFillViewport = true }

    private fun page(title: Int, lead: String?, block: LinearLayout.() -> Unit): View = scroll(vbox {
        setPadding(dp(20), dp(8), dp(20), dp(24))
        addView(text(R.style.TextAppearance_LibreTag_HeadlineSmall, getString(title)).apply { androidx.core.view.ViewCompat.setAccessibilityHeading(this, true) }, lp().margins(t = dp(4), b = dp(8)))
        lead?.let { addView(text(R.style.TextAppearance_LibreTag_BodyLarge, it, R.color.ink2), lp().margins(b = dp(16))) }
        block()
    })

    private fun render() {
        progLabel.text = getString(R.string.wz_step, step + 1, WizardSteps.COUNT, getString(steps[step]))
        prog.setProgressCompat(step + 1, true)
        zoneEditor?.map?.onPause()
        zoneEditor = null
        restList = null
        body.removeAllViews()
        backBtn.visibility = if (WizardSteps.inFlow(step)) View.VISIBLE else View.INVISIBLE
        nextBtn.text = getString(if (step == WizardSteps.DONE) R.string.wz_open_tag else R.string.next)
        if (step < WizardSteps.DONE) nextBtn.setIconResource(R.drawable.ic_chev_r) else nextBtn.icon = null
        nextBtn.isEnabled = true
        when (step) {
            WizardSteps.PLUG -> stepPlug()
            WizardSteps.NAME -> stepName()
            WizardSteps.REST -> stepRest()
            WizardSteps.DONE -> stepDone()
        }
    }

    private fun card(title: String, icon: Int, kind: String = "", block: LinearLayout.() -> Unit = {}): View = vbox {
        val (bg, stroke) = when (kind) {
            "crit" -> androidx.core.graphics.ColorUtils.blendARGB(col(R.color.bg), col(R.color.crit), 0.10f) to col(R.color.crit)
            "warn" -> androidx.core.graphics.ColorUtils.blendARGB(col(R.color.bg), col(R.color.warn), 0.16f) to col(R.color.warn)
            else -> col(R.color.surf2) to col(R.color.line)
        }
        background = rounded(bg, 12f, stroke)
        setPadding(dp(16), dp(16), dp(16), dp(16))
        addView(text(R.style.TextAppearance_LibreTag_Section, title).apply {
            setCompoundDrawablesRelative(tinted(icon, col(if (kind == "crit") R.color.crit else R.color.ink), 22), null, null, null)
            compoundDrawablePadding = dp(8)
        })
        block()
        layoutParams = lp().margins(b = dp(12))
    }

    private fun LinearLayout.cardText(s: String) = addView(text(R.style.TextAppearance_LibreTag_BodyMedium, s, R.color.ink2), lp().margins(t = dp(6), b = dp(10)))

    private fun stepPlug() {
        val st = app.usb.state.value
        // a tag on Bluetooth is a paired one: the wizard is about the cable only
        val tag = (st as? UsbState.Connected)?.tag?.takeIf { it.via == Via.USB }
        nextBtn.isEnabled = tag?.find == UsbFind.NEW && !resetting
        body.addView(page(R.string.wz_plug_title, getString(R.string.wz_plug_lead)) {
            addView(ImageView(this@AddTagActivity).apply { setImageDrawable(CableIllustration(this@AddTagActivity)); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO },
                LinearLayout.LayoutParams(dp(240), dp(150)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dp(20) })
            when {
                tag == null -> addView(card(getString(R.string.wz_waiting), R.drawable.ic_usb) {
                    cardText(when (st) {
                        is UsbState.Connecting -> getString(R.string.wz_connecting)
                        is UsbState.NeedPermission -> getString(R.string.wz_permission)
                        is UsbState.Failed -> getString(R.string.wz_failed, st.message)
                        else -> getString(R.string.wz_waiting_text)
                    })
                    addView(LinearProgressIndicator(this@AddTagActivity).apply { isIndeterminate = true })
                    if (st is UsbState.Failed || st is UsbState.NeedPermission) addView(button(getString(R.string.try_again), Btn.OUTLINED) { usbh.reconnect() }, lp(WRAP, WRAP).margins(t = dp(12)))
                })
                tag.find == UsbFind.NEW -> addView(card(getString(R.string.wz_new_found), R.drawable.ic_check) {
                    cardText(listOfNotNull(tag.board ?: tag.productName, tag.version?.let { getString(R.string.wz_fw, it) },
                        tag.status.battery?.let { getString(R.string.svc_battery, it) }).joinToString(" · ") + ". " + getString(R.string.wz_new_text))
                    tag.version?.let { v -> if (ConsoleParsers.isOlder(v, BuildConfig.FIRMWARE_LATEST))
                        cardText(getString(R.string.wz_fw_old, BuildConfig.FIRMWARE_LATEST)) }
                })
                tag.find == UsbFind.MINE -> addView(card(getString(R.string.wz_mine_title), R.drawable.ic_lock) {
                    val t = tag.tagId?.let { app.store.get(it) }
                    cardText(getString(R.string.wz_mine_text, t?.name ?: ""))
                    addView(hbox { addView(button(getString(R.string.menu_service), Btn.FILLED, R.drawable.ic_usb) {
                        startActivity(Intent(this@AddTagActivity, ServiceActivity::class.java).putExtra(MainActivity.EXTRA_TAG, tag.tagId)); finish()
                    }) })
                })
                tag.find == UsbFind.UNLOCK_FAILED -> addView(card(getString(R.string.wz_unlock_failed_title), R.drawable.ic_alert, "crit") {
                    cardText(getString(R.string.wz_unlock_failed))
                    addView(FlowRow(this@AddTagActivity).apply {
                        addView(button(getString(R.string.try_again)) { lifecycleScope.launch { app.usb.retryUnlock(); render() } })
                        addView(button(getString(R.string.wz_reset_instead), Btn.OUTLINED) { resetDialog() })
                    })
                })
                tag.find == UsbFind.FOREIGN -> addView(card(getString(R.string.wz_foreign_title), R.drawable.ic_alert, "warn") {
                    cardText(getString(R.string.wz_foreign_text))
                    addView(hbox { addView(button(getString(if (resetting) R.string.working else R.string.wz_reset), Btn.CRIT_OUTLINED) { resetDialog() }.apply { isEnabled = !resetting }) })
                })
                else -> addView(card(getString(R.string.wz_not_tag_title), R.drawable.ic_alert, "warn") { cardText(getString(R.string.wz_not_tag)) })
            }
        })
    }

    private fun resetDialog() {
        MaterialAlertDialogBuilder(this).setTitle(R.string.wz_reset_title).setMessage(R.string.wz_reset_text)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.wz_reset) { _, _ ->
                resetting = true; render()
                lifecycleScope.launch {
                    app.usb.command("unpair", maxMs = 5000)
                    delay(800)
                    val s = app.usb.refreshStatus()
                    resetting = false
                    if (s?.keyHex != null) {
                        val help = app.usb.command("help", maxMs = 2000) ?: emptyList()
                        app.usb.update { it.copy(find = UsbFind.NEW, status = s, k0Hex = s.keyHex, tagId = null,
                            commands = UsbTagSession.helpCommands(help)) }
                        findViewById<View>(android.R.id.content).snack(getString(R.string.wz_reset_done))
                    } else findViewById<View>(android.R.id.content).snack(getString(R.string.wz_reset_failed))
                    render()
                }
            }.show().getButton(android.content.DialogInterface.BUTTON_POSITIVE)?.setTextColor(col(R.color.crit))
    }

    private fun stepName() {
        val nameEdit = TextInputEditText(this).apply { setText(name); setSingleLine() }
        nextBtn.isEnabled = name.isNotBlank()
        nameEdit.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { name = s.toString(); nextBtn.isEnabled = name.isNotBlank() }
        })
        val used = app.store.trackers.value.associate { (it.markColor to it.markSymbol) to it.name }
        body.addView(page(R.string.wz2_s2, null) {
            addView(OutlinedField(this@AddTagActivity).apply { hint = getString(R.string.field_name); placeholderText = getString(R.string.wz_name_ph); addView(nameEdit) }, lp().margins(b = dp(8)))
            addView(label(getString(R.string.field_type)))
            addView(typeChips(type) { type = it })
            // the suggested mark as one row; Change opens the grid
            val markBox = vbox()
            fun markRow() {
                markBox.removeAllViews()
                val (c, s) = mark
                val markName = BlazeColors.markName(this@AddTagActivity, c, s).lowercase()
                markBox.addView(hbox {
                    gravity = Gravity.CENTER_VERTICAL
                    minimumHeight = dp(56)
                    addView(ImageView(this@AddTagActivity).apply {
                        setImageDrawable(BlazeDrawable(this@AddTagActivity, c, s)); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    }, LinearLayout.LayoutParams(dp(25), dp(30)).margins(r = dp(14)))
                    addView(text(R.style.TextAppearance_LibreTag_BodyLarge, getString(R.string.wz2_mark_row, markName)), LinearLayout.LayoutParams(0, WRAP, 1f))
                    if (!markOpen) addView(button(getString(R.string.wz2_change), Btn.TEXT) { markOpen = true; markRow() })
                }, lp().margins(t = dp(12)))
                if (markOpen) markBox.addView(markGrid(c, s, used) { cc, ss -> mark = cc to ss; markRow() }, lp().margins(t = dp(4)))
            }
            markRow()
            addView(markBox)
        })
    }

    private fun label(s: String) = text(R.style.TextAppearance_LibreTag_Band, s, R.color.ink2).apply { textSize = 14f; layoutParams = lp().margins(t = dp(16), b = dp(8)) }

    /** Wi-Fi places on the tag on the cable (snapped here). */
    private fun wifiPlaces(): List<WifiZoneEntry> = app.usb.connected?.wifiZones.orEmpty()

    private fun placeCount() = zones.size + wifiPlaces().size

    /** Skip until a place exists, then Pair. Called whenever the place list changes, without a page rebuild. */
    private fun footer() {
        if (step != WizardSteps.REST) return
        nextBtn.text = getString(if (WizardSteps.pairsNow(step, placeCount())) R.string.wz_pair else R.string.wz2_skip)
        nextBtn.setIconResource(R.drawable.ic_chev_r)
    }

    private fun tagLabel() = name.trim().ifBlank { getString(R.string.wz_new_tag) }

    private fun stepRest() {
        val tag = app.usb.connected
        footer()
        val lead = getString(R.string.wz2_rest_lead, tagLabel())
        // a tag without GPS: Wi-Fi places, taken where the tag is now
        if (tag != null && tag.wifi && tag.status.lastFix == null) {
            body.addView(page(R.string.wz2_s3, lead) {
                val list = vbox()
                restList = list
                addView(list)
            })
            fillRest()
            return
        }
        val fwNote = if (tag != null && !app.usb.supports("zone")) getString(R.string.place_app_only_sub, tagLabel()) else null
        val center = tag?.status?.lastFix?.split(",")?.mapNotNull { it.trim().toDoubleOrNull() }?.takeIf { it.size == 2 }?.let { it[0] to it[1] }
        val ed = ZoneEditor(this, center, mark, tagLabel())
        zoneEditor = ed
        ed.others = zones.toList()
        val col = vbox {
            addView(vbox {
                setPadding(dp(20), dp(8), dp(20), dp(12))
                addView(text(R.style.TextAppearance_LibreTag_HeadlineSmall, getString(R.string.wz2_s3)).apply { androidx.core.view.ViewCompat.setAccessibilityHeading(this, true) })
                addView(text(R.style.TextAppearance_LibreTag_BodyMedium, lead, R.color.ink2), lp().margins(t = dp(6)))
                fwNote?.let { addView(text(R.style.TextAppearance_LibreTag_BodySmall, it, R.color.ink2), lp().margins(t = dp(6))) }
            })
            addView(ed, LinearLayout.LayoutParams(MATCH, 0, 1f))
        }
        body.addView(col, flp(MATCH, MATCH))
        ed.map.onResume()
        if (zones.isEmpty()) draft(center) else fillRest()
    }

    /** The map's panel while a place is being added: name, size, the two switches, Add place. */
    private fun draft(at: Pair<Double, Double>?) {
        val ed = zoneEditor ?: return
        val taken = zones.map { it.name } + wifiPlaces().map { it.name }
        val def = PlaceLogic.effective(null, type)
        val panel = DraftPanel(this, ed, tagLabel(), NotifyChoice(def, def), withYouNote = false)
        val n = PlaceLogic.suggestName(getString(R.string.zone_home), taken)
        panel.nameEdit.setText(n)
        ed.draft = at?.let { Zone(n.ifBlank { " " }, it.first, it.second, DraftPanel.DEFAULT_R) }
        ed.placeOnTap = { lat, lon -> Zone(panel.nameEdit.text?.toString().orEmpty().ifBlank { " " }, lat, lon, DraftPanel.DEFAULT_R) }
        panel.sync()
        if (zones.isNotEmpty()) panel.actions.addView(button(getString(R.string.cancel), Btn.TEXT) {
            ed.placeOnTap = null; ed.draft = null; fillRest()
        })
        panel.actions.addView(button(getString(R.string.place_add_btn), Btn.FILLED, R.drawable.ic_plus) {
            val d = ed.draft ?: return@button
            val name = panel.name(taken) ?: return@button
            val c = panel.choice
            zones += d.copy(name = name, notifyLeave = c?.leave?.takeIf { it != def }, notifyArrive = c?.arrive?.takeIf { it != def })
            ed.placeOnTap = null
            ed.draft = null
            ed.others = zones.toList()
            footer()
            fillRest()
            ed.announceForAccessibility(name)
        }, LinearLayout.LayoutParams(0, WRAP, 1f))
    }

    /** The places added so far, with Add a place (and the Wi-Fi snap for a tag that scans Wi-Fi). Refreshed in place. */
    private fun fillRest() {
        val ed = zoneEditor
        val box = restList ?: ed?.panel ?: return
        box.removeAllViews()
        val tag = app.usb.connected
        for (z in zones.toList()) box.addView(listItem(R.drawable.ic_zone, z.name, Format.distance(this, z.radiusM.toDouble()),
            iconButton(R.drawable.ic_trash, getString(R.string.a11y_delete_zone, z.name), R.color.ink2) {
                zones.remove(z); ed?.others = zones.toList(); footer()
                if (zones.isEmpty() && ed != null) draft(null) else fillRest()
            }))
        for (w in wifiPlaces()) box.addView(listItem(R.drawable.ic_wifi, w.name,
            resources.getQuantityString(R.plurals.wzn_networks, w.aps, w.aps), placeChip(PlaceChip.ON_TAG)))
        if (ed == null && wifiPlaces().isEmpty()) box.addView(text(R.style.TextAppearance_LibreTag_BodyMedium,
            getString(R.string.places_empty, tagLabel()), R.color.ink2), lp().margins(b = dp(8)))
        val row = FlowRow(this)
        if (ed != null) row.addView(button(getString(R.string.place_add), Btn.OUTLINED, R.drawable.ic_plus) {
            val c = ed.map.map.mapCenter
            draft(c.latitude to c.longitude)
        })
        if (tag != null && tag.wifi) {
            val label = if (ed == null) getString(R.string.place_add_here, tagLabel()) else getString(R.string.place_add_wifi)
            row.addView(button(label, if (ed == null) Btn.FILLED else Btn.TEXT, R.drawable.ic_wifi) { snap() })
        }
        box.addView(row, lp().margins(t = dp(8)))
    }

    private fun snap() {
        val taken = wifiPlaces().map { it.name }
        val def = PlaceLogic.effective(null, type)
        SnapSheet(this, tagLabel(), type, taken, withYouNote = false) { n, leave, on ->
            val i = wifiNotify.indexOfFirst { it.first == n }
            val cur = if (i >= 0) wifiNotify[i] else Triple(n, def, def)
            val next = if (leave) cur.copy(second = on) else cur.copy(third = on)
            if (i >= 0) wifiNotify[i] = next else wifiNotify += next
        }.apply { onClosed = { if (step == WizardSteps.REST) { fillRest(); footer() } } }.show()
    }

    /** Write everything to the tag, lock it, and add it to the app. The step names go to the log, not the screen. */
    private fun pair() {
        val tag = app.usb.connected ?: run { DiagLog.w(TAG, "pair: the tag isn't on the cable"); pairedId = null; render(); return }
        val key = tag.k0Hex ?: run { DiagLog.w(TAG, "pair: the tag gave no key"); pairedId = null; render(); return }
        pairing = true; partial = false; render()
        val npub = app.store.ownerNpub
        val label = tagLabel()
        val people = Tracker.defaultUse(type) == Tracker.USE_PEOPLE
        lifecycleScope.launch {
            val failed = mutableListOf<String>()
            val stillAfter = if (people) 0 else Tracker.DEFAULT_STILL_AFTER
            app.usb.session { c ->
                if (!c.set("transport channel")) failed += "channel"
                if (!c.set(ConsoleCommands.interval(INTERVAL_MIN))) failed += "interval"
                // 0.14.0 (SPEC.md 3.2 defaults): wake every 60 min, report at every 3rd, the rest only listen
                if (!c.set(ConsoleCommands.reportEvery(REPORT_EVERY))) failed += "report every"
                if (!c.set(ConsoleCommands.check(Tracker.DEFAULT_CHECK_S))) failed += "check"
                if (!c.set(ConsoleCommands.still(stillAfter, Tracker.DEFAULT_STILL_HB_H))) failed += "still"
                if (!c.set("network auto")) failed += "network"
                if (npub != null && npub != tag.status.owner && c.command("owner $npub").none { it.startsWith("owner npub1") }) failed += "owner"
            }
            val id = app.store.addOrUpdate(key, {
                Tracker(name = label, keyHex = key.lowercase(), intervalMin = INTERVAL_MIN, reportEvery = REPORT_EVERY, type = type, markColor = mark.first, markSymbol = mark.second,
                    zones = zones.toList(), createdAt = System.currentTimeMillis(), firmware = tag.version)
            }) { it.copy(name = label, intervalMin = INTERVAL_MIN, reportEvery = REPORT_EVERY, type = type, markColor = mark.first, markSymbol = mark.second, zones = zones.toList(),
                stillAfter = null, firmware = tag.version ?: it.firmware) }
            app.usb.update { it.copy(tagId = id, find = UsbFind.MINE) }
            // a Wi-Fi tag: the places snapped here are on it already; their switches are the app's
            if (tag.wifi) {
                app.usb.storeWifiZones(id, app.usb.connected?.wifiZones)
                if (wifiNotify.isNotEmpty()) app.store.updateTracker(id) { cur ->
                    wifiNotify.fold(cur) { acc, (n, l, a) -> PlaceLogic.setNotify(PlaceLogic.setNotify(acc, n, true, l), n, false, a) }
                }
            }
            val t = app.store.get(id)!!
            if (zones.isNotEmpty() && app.usb.supports("zone") && TagOps.writeZones(t) != WriteResult.OK) failed += "zones"
            if (people && app.usb.supports("anchor")) TagOps.writeKnown(app.store.get(id)!!)
            // K0 is in the app now: lock the console (K0 leaves the tag, PROTOCOL.md 6)
            val locked = app.usb.session { c -> c.command("lock").any { it.startsWith("OK locked") } } ?: false
            if (!locked) failed += "lock"
            if (failed.isNotEmpty()) DiagLog.w(TAG, "pair ${t.name}: not taken: ${failed.joinToString(", ")}")
            if (npub != null) app.scope.launch { app.sendWelcome(app.store.get(id) ?: t) }
            pairedId = id
            pairing = false
            partial = failed.isNotEmpty()
            SupportAsk.onAction(this@AddTagActivity)
            render()
        }
    }

    private fun stepDone() {
        nextBtn.isEnabled = !pairing && pairedId != null
        if (pairing) {
            body.addView(page(R.string.wz_pairing, null) {
                addView(LinearProgressIndicator(this@AddTagActivity).apply { isIndeterminate = true })
                addView(text(R.style.TextAppearance_LibreTag_BodyMedium, getString(R.string.wz_pairing_text), R.color.ink2), lp().margins(t = dp(12)))
            })
            return
        }
        val t = pairedId?.let { app.store.get(it) }
        if (t == null) {
            body.addView(page(R.string.wz_pair_failed, getString(R.string.wz2_pair_failed)) {
                addView(hbox { addView(button(getString(R.string.try_again)) { pair() }) })
            })
            nextBtn.isEnabled = false
            backBtn.visibility = View.VISIBLE
            return
        }
        body.addView(page(R.string.wz_done_title, null) {
            (getChildAt(0) as? android.widget.TextView)?.text = getString(R.string.wz_done_named, t.name)
            addView(FrameLayout(this@AddTagActivity).apply {
                background = android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(col(R.color.primary)) }
                addView(ImageView(this@AddTagActivity).apply { setImageDrawable(tinted(R.drawable.ic_check, col(R.color.on_primary), 36)) }, flp(dp(36), dp(36), Gravity.CENTER))
            }, 0, LinearLayout.LayoutParams(dp(72), dp(72)).margins(t = dp(12), b = dp(16)))
            addView(text(R.style.TextAppearance_LibreTag_BodyLarge, getString(if (app.store.ownerNpub != null) R.string.wz_done_text else R.string.wz_done_text_nonpub), R.color.ink2), lp().margins(b = dp(16)))
            if (partial) addView(card(getString(R.string.wz_done_partial), R.drawable.ic_alert, "warn") { cardText(getString(R.string.wz2_partial_text)) })
            // the defaults it got, stated instead of asked
            val places = t.zones.map { it.name } + t.wifiZones.map { it.name }
            val lines = listOfNotNull(getString(R.string.wz2_done_reports),
                places.takeIf { it.isNotEmpty() }?.let { getString(R.string.wz2_done_quiet, it.joinToString(", ")) },
                getString(R.string.wz2_done_tell))
            addView(vbox {
                for (l in lines) addView(text(R.style.TextAppearance_LibreTag_BodyMedium, l, R.color.ink), lp().margins(b = dp(4)))
                addView(hbox { addView(button(getString(R.string.menu_tag_settings), Btn.TEXT, R.drawable.ic_prefs) {
                    startActivity(Intent(this@AddTagActivity, TagSettingsActivity::class.java).putExtra(MainActivity.EXTRA_TAG, t.id))
                }) })
            }, lp().margins(b = dp(12)))
            if (!t.people) addView(card(getString(R.string.wz2_devices_title), R.drawable.ic_bt) {
                cardText(getString(R.string.wz2_devices_text, t.name))
                addView(hbox { addView(button(getString(R.string.wz2_devices_go), Btn.TONAL, R.drawable.ic_bt) {
                    startActivity(Intent(this@AddTagActivity, KnownDevicesActivity::class.java).putExtra(MainActivity.EXTRA_TAG, t.id))
                }) })
            })
            if (app.store.ownerNpub != null) app.trackerNpub(t)?.let { npub ->
                addView(card(getString(R.string.follow_title), R.drawable.ic_person) { TagSheets.followBlock(this@AddTagActivity, npub, this) })
            }
            if (!app.backup.state.value.phrase.on && app.signers.session == null && !app.prefs.backupOfferDismissed) addView(card(getString(R.string.wz_backup_title), R.drawable.ic_key) {
                cardText(getString(R.string.wz_backup_text))
                addView(FlowRow(this@AddTagActivity).apply {
                    addView(button(getString(R.string.wz_backup_go)) { startActivity(Intent(this@AddTagActivity, BackupActivity::class.java)) })
                    addView(button(getString(R.string.not_now), Btn.TEXT) { app.prefs.backupOfferDismissed = true; render() })
                })
            })
            val busy = app.store.trackers.value.any { Status.of(it).blocksSupport }
            if (supportShown || SupportAsk.consider(this@AddTagActivity, "paired:${t.id}", busy)) {
                supportShown = true
                addView(SupportAsk.strip(this@AddTagActivity, SupportCheckout.REF_PAIRED) { supportShown = false; render() }, lp().margins(t = dp(8)))
            }
        })
    }

    override fun onPause() { zoneEditor?.map?.onPause(); super.onPause() }
    override fun onResume() { super.onResume(); zoneEditor?.map?.onResume() }

    companion object {
        private const val TAG = "AddTag"
        /** a new tag reports hourly; Tag settings changes it later */
        const val INTERVAL_MIN = 60
        /** 0.14.0: report at every 3rd wake-up, listen at the others */
        const val REPORT_EVERY = 3
    }
}
