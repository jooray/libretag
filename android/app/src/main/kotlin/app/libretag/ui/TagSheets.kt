package app.libretag.ui

import app.libretag.usb.ConsoleParsers
import app.libretag.BuildConfig
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.NestedScrollView
import androidx.lifecycle.lifecycleScope
import app.libretag.LibreTagApp
import app.libretag.R
import app.libretag.data.AlertPrefs
import app.libretag.data.HistoryExport
import app.libretag.data.Marks
import app.libretag.data.Position
import app.libretag.data.ShareRec
import app.libretag.data.Status
import app.libretag.data.Tracker
import app.libretag.keys.ConsoleKey
import app.libretag.keys.ShareLink
import app.libretag.nostr.Bech32
import app.libretag.proto.LibreTag
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.radiobutton.MaterialRadioButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.google.zxing.BarcodeFormat
import com.journeyapps.barcodescanner.BarcodeEncoder
import kotlinx.coroutines.launch

/** A Material bottom sheet: handle, title (and subtitle), close, scrolling body, footer buttons. */
class Sheet(val ctx: Context, title: String, sub: String? = null) {
    val dialog = BottomSheetDialog(ctx)
    val body = ctx.vbox { setPadding(dp(24), 0, dp(24), dp(24)) }
    val footer = ctx.hbox { gravity = Gravity.END; setPadding(dp(24), dp(12), dp(24), dp(16)) }

    init {
        val col = ctx.vbox()
        col.addView(com.google.android.material.bottomsheet.BottomSheetDragHandleView(ctx), lp(WRAP, WRAP).apply { gravity = Gravity.CENTER_HORIZONTAL })
        val head = ctx.hbox {
            setPadding(dp(24), 0, dp(8), dp(4))
            minimumHeight = dp(56)
            addView(ctx.vbox {
                addView(ctx.text(R.style.TextAppearance_LibreTag_TitleLarge, title).apply { textSize = 20f; androidx.core.view.ViewCompat.setAccessibilityHeading(this, true) })
                sub?.let { addView(ctx.text(R.style.TextAppearance_LibreTag_LabelMedium, it, R.color.ink3)) }
            }, LinearLayout.LayoutParams(0, WRAP, 1f))
            addView(ctx.iconButton(R.drawable.ic_close, ctx.getString(R.string.a11y_close)) { dialog.dismiss() })
        }
        col.addView(head)
        col.addView(NestedScrollView(ctx).apply { addView(body) }, LinearLayout.LayoutParams(MATCH, 0, 1f))
        col.addView(ctx.divider().also { it.tag = "fdiv" })
        col.addView(footer)
        dialog.setContentView(col)
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        dialog.behavior.skipCollapsed = true
    }

    fun p(s: CharSequence) = body.addView(ctx.text(R.style.TextAppearance_LibreTag_BodyMedium, s, R.color.ink2), lp().margins(b = ctx.dp(12)))
    fun label(s: String) = body.addView(ctx.text(R.style.TextAppearance_LibreTag_Band, s, R.color.ink2).apply { textSize = 14f }, lp().margins(t = ctx.dp(16), b = ctx.dp(8)))
    fun foot(vararg v: View) { v.forEach { footer.addView(it, lp(WRAP, WRAP).margins(l = ctx.dp(8))) } }
    fun show(): Sheet {
        if (footer.childCount == 0) { footer.visibility = View.GONE; (footer.parent as LinearLayout).findViewWithTag<View>("fdiv")?.visibility = View.GONE }
        dialog.show()
        return this
    }
}

/** A list row (icon, text with a small second line, optional end view). */
fun Context.listItem(icon: Int?, title: CharSequence, sub: CharSequence? = null, end: View? = null, onClick: (() -> Unit)? = null): LinearLayout = hbox {
    minimumHeight = dp(56)
    setPadding(0, dp(8), 0, dp(8))
    icon?.let { addView(this@listItem.icon(it, R.color.ink2), LinearLayout.LayoutParams(dp(24), dp(24)).margins(r = dp(16))) }
    addView(vbox {
        addView(text(R.style.TextAppearance_LibreTag_BodyLarge, title))
        sub?.let { addView(text(R.style.TextAppearance_LibreTag_BodySmall, it)) }
    }, LinearLayout.LayoutParams(0, WRAP, 1f))
    end?.let { addView(it, lp(WRAP, WRAP).margins(l = dp(12))) }
    if (onClick != null) {
        isClickable = true; isFocusable = true
        val tv = android.util.TypedValue()
        theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
        setBackgroundResource(tv.resourceId)
        setOnClickListener { onClick() }
    }
}

fun Context.switchRow(title: String, sub: String?, on: Boolean, onChange: (Boolean) -> Unit): LinearLayout {
    val sw = MaterialSwitch(this).apply { isChecked = on; contentDescription = title }
    val row = listItem(null, title, sub, sw) { sw.toggle() }
    sw.setOnCheckedChangeListener { _, c -> onChange(c) }
    return row
}

/** The 4 × 5 mark picker; marks other tags use are faded with their name. */
fun Context.markGrid(selColor: String, selSymbol: String, used: Map<Pair<String, String>, String>, onPick: (String, String) -> Unit): View {
    val grid = android.widget.GridLayout(this).apply { columnCount = 5 }
    val cells = mutableListOf<Pair<View, Pair<String, String>>>()
    fun refresh(c: String, s: String) {
        cells.forEach { (v, m) ->
            val on = m.first == c && m.second == s
            v.background = rounded(col(if (on) R.color.sel else R.color.bg), 8f, col(if (on) R.color.primary else R.color.line), if (on) 2f else 1f)
            v.isSelected = on
        }
    }
    for (c in Marks.COLORS) for (s in Marks.SYMBOLS) {
        val u = used[c to s]
        val cell = android.widget.FrameLayout(this).apply {
            addView(ImageView(this@markGrid).apply { setImageDrawable(BlazeDrawable(this@markGrid, c, s)) }, flp(dp(25), dp(30), Gravity.CENTER))
            if (u != null) {
                alpha = 0.45f
                addView(text(R.style.TextAppearance_LibreTag_LabelSmall, u, R.color.ink3).apply { textSize = 10f; ellipsize1(); gravity = Gravity.CENTER },
                    flp(MATCH, WRAP, Gravity.BOTTOM).apply { bottomMargin = dp(1) })
            }
            contentDescription = if (u != null) getString(R.string.mark_used_by, BlazeColors.markName(this@markGrid, c, s), u) else BlazeColors.markName(this@markGrid, c, s)
            isClickable = true; isFocusable = true
            setOnClickListener { onPick(c, s); refresh(c, s) }
        }
        grid.addView(cell, android.widget.GridLayout.LayoutParams(
            android.widget.GridLayout.spec(android.widget.GridLayout.UNDEFINED, 1f),
            android.widget.GridLayout.spec(android.widget.GridLayout.UNDEFINED, 1f)).apply {
            width = 0; height = dp(56); setMargins(dp(3), dp(3), dp(3), dp(3))
        })
        cells += cell to (c to s)
    }
    refresh(selColor, selSymbol)
    return grid
}

fun Context.typeChips(sel: String, onPick: (String) -> Unit): ChipGroup = ChipGroup(this).apply {
    isSingleSelection = true; isSelectionRequired = true
    for ((v, l) in listOf("person" to R.string.type_person_cap, "thing" to R.string.type_thing_cap, "vehicle" to R.string.type_vehicle_cap)) {
        addView(Chip(this@typeChips).apply {
            text = getString(l); isCheckable = true; isChecked = v == sel; id = View.generateViewId()
            setOnClickListener { onPick(v) }
        })
    }
}

fun Context.qrImage(content: String, sizeDp: Int = 200): ImageView {
    val px = dp(sizeDp)
    val bmp: Bitmap = BarcodeEncoder().encodeBitmap(content, BarcodeFormat.QR_CODE, px, px)
    return ImageView(this).apply {
        setImageBitmap(bmp)
        background = rounded(Color.WHITE, 12f)
        setPadding(dp(12), dp(12), dp(12), dp(12))
        layoutParams = LinearLayout.LayoutParams(px + dp(24), px + dp(24)).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(8); bottomMargin = dp(12) }
    }
}

object TagSheets {
    private val app get() = LibreTagApp.instance

    /** The follow card's body: why, the tag's npub, Open in a Nostr app (nostr: intent, njump.me without one), Copy. */
    fun followBlock(a: AppCompatActivity, npub: String, box: LinearLayout) {
        box.addView(a.text(R.style.TextAppearance_LibreTag_BodyMedium, a.getString(R.string.follow_why), R.color.ink2), lp().margins(t = a.dp(6), b = a.dp(8)))
        box.addView(a.text(R.style.TextAppearance_LibreTag_Status, npub, R.color.ink2).apply { setTextIsSelectable(true) }, lp().margins(b = a.dp(10)))
        box.addView(FlowRow(a).apply {
            addView(a.button(a.getString(R.string.follow_open), Btn.FILLED, R.drawable.ic_open) { openNostr(a, npub) })
            addView(a.button(a.getString(R.string.copy), Btn.OUTLINED, R.drawable.ic_copy) {
                a.copyText("npub", npub); android.widget.Toast.makeText(a, a.getString(R.string.copied), android.widget.Toast.LENGTH_SHORT).show()
            })
        })
    }

    fun openNostr(a: AppCompatActivity, npub: String) {
        if (runCatching { a.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("nostr:$npub"))) }.isFailure) a.openUrl("https://njump.me/$npub")
    }

    /** Tag menu > Follow on Nostr. */
    fun follow(a: AppCompatActivity, t: Tracker) {
        val npub = app.trackerNpub(t) ?: return
        val s = Sheet(a, a.getString(R.string.follow_title), t.name)
        followBlock(a, npub, s.body)
        s.foot(a.button(a.getString(R.string.close), Btn.TEXT) { s.dialog.dismiss() })
        s.show()
    }

    /** Open in map: geo: to an app of the owner's choice. For an area the centre goes, the radius in the label. */
    fun openWith(a: AppCompatActivity, t: Tracker, p: Position) {
        val target = TagText.target(t, p) ?: return
        val (lat, lon, r) = target
        val c = String.format(java.util.Locale.ROOT, "%.5f,%.5f", lat, lon)
        val label = if (r != null) a.getString(R.string.geo_area_label, t.name, Format.distance(a, r)) else t.name
        val uri = Uri.parse("geo:$c?q=$c(${Uri.encode(label)})")
        val view = Intent(Intent.ACTION_VIEW, uri)
        val apps = a.packageManager.queryIntentActivities(view, 0).distinctBy { it.activityInfo.packageName }
        val s = Sheet(a, a.getString(R.string.open_with), if (r != null) a.getString(R.string.open_with_area) else null)
        if (apps.isEmpty()) s.p(a.getString(R.string.open_with_none))
        for (ri in apps.sortedBy { order(it.activityInfo.packageName) }) {
            val row = a.listItem(null, ri.loadLabel(a.packageManager)) {
                s.dialog.dismiss()
                runCatching { a.startActivity(Intent(view).setPackage(ri.activityInfo.packageName)) }
            }
            row.addView(ImageView(a).apply { setImageDrawable(ri.loadIcon(a.packageManager)) }, 0, LinearLayout.LayoutParams(a.dp(32), a.dp(32)).margins(r = a.dp(16)))
            s.body.addView(row)
        }
        s.body.addView(a.text(R.style.TextAppearance_LibreTag_LabelMedium, uri.toString(), R.color.ink2).apply { setTextIsSelectable(true) }, lp().margins(t = a.dp(12)))
        s.foot(a.button(a.getString(R.string.copy_coordinates), Btn.OUTLINED, R.drawable.ic_copy) {
            a.copyText("coordinates", Format.coord(lat, lon)); s.dialog.dismiss()
            a.findViewById<View>(android.R.id.content).snack(a.getString(R.string.coordinates_copied))
        })
        s.show()
    }

    /** OsmAnd, CoMaps, Organic Maps first (no account, offline), then the rest. */
    private fun order(pkg: String) = when {
        pkg.startsWith("net.osmand") -> 0
        pkg.contains("comaps") -> 1
        pkg.contains("organicmaps") -> 2
        pkg.contains("google") -> 4
        else -> 3
    }

    fun edit(a: AppCompatActivity, t: Tracker) {
        val s = Sheet(a, a.getString(R.string.edit_title, t.name))
        val name = TextInputEditText(a).apply { setText(t.name) }
        s.body.addView(OutlinedField(a).apply { hint = a.getString(R.string.field_name); addView(name) }, lp().margins(t = a.dp(12)))
        var type = t.type; var c = t.markColor; var sy = t.markSymbol
        s.label(a.getString(R.string.field_type))
        s.body.addView(a.typeChips(type) { type = it })
        s.label(a.getString(R.string.field_mark))
        val used = app.store.trackers.value.filter { it.id != t.id }.associate { (it.markColor to it.markSymbol) to it.name }
        s.body.addView(a.markGrid(c, sy, used) { cc, ss -> c = cc; sy = ss })
        s.foot(a.button(a.getString(R.string.cancel), Btn.TEXT) { s.dialog.dismiss() },
            a.button(a.getString(R.string.save)) {
                val n = name.text?.toString()?.trim().orEmpty().ifBlank { t.name }
                app.store.updateTracker(t.id) { it.copy(name = n, type = type, markColor = c, markSymbol = sy) }
                s.dialog.dismiss()
                a.findViewById<View>(android.R.id.content).snack(a.getString(R.string.saved))
            })
        s.show()
    }

    fun diagnostics(a: AppCompatActivity, t: Tracker) {
        val s = Sheet(a, a.getString(R.string.menu_diag), t.name)
        val p = t.last
        val rows = mutableListOf<Pair<String, String>>()
        if (p != null) {
            rows += a.getString(R.string.diag_last) to "n ${p.n} · ${Format.at(a, p.receivedAt)}"
            rows += a.getString(R.string.route) to TagText.route(a, p)
            if (p.via == "mesh") rows += "SNR" to String.format(java.util.Locale.ROOT, "%+.1f dB", p.snrDb)
            p.hops?.let { rows += a.getString(R.string.diag_hops) to it.toString() }
            if (p.neighbours.isNotEmpty()) {
                val names = p.neighbours.map { nb -> p.est?.anchors?.firstOrNull { it.hash.startsWith(nb.hash) }?.name?.let { "$it (${nb.hash})" } ?: nb.hash }
                rows += a.getString(R.string.diag_repeaters) to names.joinToString(", ") +
                    if (p.est?.state == "ambiguous") " · " + a.getString(R.string.diag_ambiguous) else ""
            }
            rows += a.getString(R.string.diag_nb_total) to "${p.nbTotal}" + if (p.nbFromDiscover) " · discover" else ""
            p.energyMah?.let { rows += a.getString(R.string.diag_energy) to "≥ $it mAh" }
            p.nextInS?.let { rows += a.getString(R.string.diag_next_in) to Format.duration(a, it * 1000) }
            if (p.quiet) rows += a.getString(R.string.diag_quiet) to (p.quietReason ?: "zone")
            if (p.checks) rows += a.getString(R.string.diag_checks) to (Status.checkPlan(t)?.let { pl ->
                "${Format.every(a, pl.intervalMin * 60)} · ${pl.offsetsS.size}" + (pl.next(System.currentTimeMillis())?.let { (k, at) -> " · k=$k ${Format.clock(a, at)}" } ?: "")
            } ?: "CHECKS")
            p.bleDevices?.let { rows += a.getString(R.string.diag_ble) to "$it · ${p.bleStable ?: 0} stable" }
            p.wifi?.let { rows += "Wi-Fi" to "${it.apCount} APs" }
            rows += a.getString(R.string.diag_epoch) to LibreTag.epoch(p.n).toString()
        }
        rows += a.getString(R.string.diag_stored) to t.positions.size.toString()
        rows += a.getString(R.string.diag_interval) to Format.every(a, t.intervalMin * 60)
        rows += a.getString(R.string.diag_firmware) to (t.firmware?.let { fw ->
            if (ConsoleParsers.isOlder(fw, BuildConfig.FIRMWARE_LATEST))
                a.getString(R.string.fw_available_short, fw, BuildConfig.FIRMWARE_LATEST) else fw
        } ?: a.getString(R.string.diag_unknown_usb))
        t.board?.let { rows += a.getString(R.string.diag_hardware) to it }
        app.trackerNpub(t)?.let { rows += a.getString(R.string.menu_nostr_contact) to it }
        val grid = android.widget.GridLayout(a).apply { columnCount = 2 }
        for ((k, v) in rows) {
            grid.addView(a.text(R.style.TextAppearance_LibreTag_RowKey, k).apply { setPadding(0, a.dp(3), a.dp(16), a.dp(3)) })
            grid.addView(a.text(R.style.TextAppearance_LibreTag_Status, v).apply {
                setTextIsSelectable(true); gravity = Gravity.END; setPadding(0, a.dp(3), 0, a.dp(3))
            }, android.widget.GridLayout.LayoutParams().apply { width = 0; columnSpec = android.widget.GridLayout.spec(1, 1f) })
        }
        s.body.addView(grid)
        val txt = "LibreTag ${BuildConfig.VERSION_NAME} diagnostics ${t.name}\n" + rows.joinToString("\n") { "${it.first}: ${it.second}" }
        s.foot(a.button(a.getString(R.string.copy), Btn.OUTLINED, R.drawable.ic_copy) {
            a.copyText("diagnostics", txt); a.findViewById<View>(android.R.id.content).snack(a.getString(R.string.copied))
        }, a.button(a.getString(R.string.close)) { s.dialog.dismiss() })
        s.show()
    }

    /** what a share gives: view (V_e), view and control (also C_e), full access (also usb_key and id) */
    enum class ShareLevel { VIEW, CONTROL, ADMIN }

    private fun levelName(l: ShareLevel) = when (l) {
        ShareLevel.VIEW -> R.string.share_view
        ShareLevel.CONTROL -> R.string.share_control
        ShareLevel.ADMIN -> R.string.share_admin
    }

    private fun ShareRec.level() = if (admin) ShareLevel.ADMIN else if (control) ShareLevel.CONTROL else ShareLevel.VIEW

    /**
     * Share: view only (V_e from the current epoch), view and control (V_e and C_e, including Lost)
     * or full access (also the console key: settings over USB and Bluetooth, behind a warning), by
     * QR code or a Nostr DM from the tag's key. Honest about revocation.
     */
    fun share(a: AppCompatActivity, t: Tracker) {
        val chain = app.chainFor(t) ?: return
        val epoch = LibreTag.epoch(maxOf(0, t.lastN))
        val console = app.consoleFor(t)
        val s = Sheet(a, a.getString(R.string.share_title, t.name))
        var level = ShareLevel.VIEW
        var method = "qr"
        val dynamic = a.vbox()
        val levels = a.vbox()
        val radios = mutableMapOf<ShareLevel, MaterialRadioButton>()
        fun pick(l: ShareLevel) {
            level = l
            radios.forEach { (k, rb) -> rb.isChecked = k == l }
            render(a, t, s, dynamic, level, method, chain, console, epoch)
        }
        fun level(l: ShareLevel, sub: Int) {
            val rb = MaterialRadioButton(a).also { radios[l] = it }
            levels.addView(a.listItem(null, a.getString(levelName(l)), a.getString(sub)) {
                if (l != ShareLevel.ADMIN || level == ShareLevel.ADMIN) { pick(l); return@listItem }
                // whoever holds the console key can change and unpair the tag: say so before the code shows
                com.google.android.material.dialog.MaterialAlertDialogBuilder(a)
                    .setTitle(R.string.share_admin_warn_title).setMessage(R.string.share_admin_warn)
                    .setNegativeButton(R.string.cancel, null)
                    .setPositiveButton(R.string.share_admin_warn_ok) { _, _ -> pick(ShareLevel.ADMIN) }
                    .show().getButton(android.content.DialogInterface.BUTTON_POSITIVE)?.setTextColor(a.col(R.color.crit))
            }.apply { addView(rb, 0, lp(WRAP, WRAP).margins(r = a.dp(12))); rb.isClickable = false })
        }
        level(ShareLevel.VIEW, R.string.share_view_sub)
        if (chain.canCommand) level(ShareLevel.CONTROL, R.string.share_control_sub)
        if (chain.canCommand && console != null) level(ShareLevel.ADMIN, R.string.share_admin_sub)
        radios[ShareLevel.VIEW]!!.isChecked = true
        s.body.addView(levels)
        s.label(a.getString(R.string.share_send_with))
        val toggle = MaterialButtonToggleGroup(a).apply { isSingleSelection = true; isSelectionRequired = true }
        val bQr = a.segmentButton(a.getString(R.string.share_qr))
        val bDm = a.segmentButton(a.getString(R.string.share_dm))
        toggle.addView(bQr); toggle.addView(bDm)
        toggle.check(bQr.id)
        toggle.addOnButtonCheckedListener { _, id, checked -> if (checked) { method = if (id == bQr.id) "qr" else "dm"; render(a, t, s, dynamic, level, method, chain, console, epoch) } }
        s.body.addView(toggle, lp(WRAP, WRAP))
        s.body.addView(dynamic, lp())
        s.p(a.getString(R.string.share_revoke_note))
        if (t.shares.isNotEmpty()) {
            s.label(a.getString(R.string.share_with))
            for (r in t.shares) s.body.addView(a.listItem(R.drawable.ic_person, r.who,
                a.getString(levelName(r.level())) + " · " + a.getString(R.string.share_since, Format.date(r.since)),
                a.button(a.getString(R.string.share_stop), Btn.TEXT) {
                    app.store.updateTracker(t.id) { it.copy(shares = it.shares - r) }
                    s.dialog.dismiss()
                    a.findViewById<View>(android.R.id.content).snack(a.getString(R.string.share_stopped, r.who))
                }))
        }
        render(a, t, s, dynamic, level, method, chain, console, epoch)
        s.show()
    }

    private fun render(a: AppCompatActivity, t: Tracker, s: Sheet, box: LinearLayout, level: ShareLevel, method: String,
                       chain: app.libretag.keys.KeyChain, console: ConsoleKey?, epoch: Long) {
        box.removeAllViews()
        s.footer.removeAllViews()
        val control = level != ShareLevel.VIEW
        val admin = level == ShareLevel.ADMIN && console != null
        val link = ShareLink.build(t.name, t.type, "${t.markColor}-${t.markSymbol}", epoch, chain.view(epoch)!!,
            if (control) chain.cmd(epoch) else null, if (admin) console!!.usbKey else null, if (admin) console!!.id else null)
        fun rec(who: String) = ShareRec(who, control, System.currentTimeMillis(), epoch, admin)
        if (admin) box.addView(a.text(R.style.TextAppearance_LibreTag_BodySmall, a.getString(R.string.share_admin_note), R.color.crit),
            lp().margins(t = a.dp(12)))
        if (method == "qr") {
            box.addView(a.qrImage(link, 220))
            box.addView(a.text(R.style.TextAppearance_LibreTag_BodyMedium, a.getString(R.string.share_qr_hint), R.color.ink2).apply { gravity = Gravity.CENTER }, lp())
            s.foot(a.button(a.getString(R.string.share_copy_link), Btn.OUTLINED, R.drawable.ic_copy) {
                a.copySecret("LibreTag", link)
                app.store.updateTracker(t.id) { it.copy(shares = it.shares + rec(a.getString(R.string.share_someone_link))) }
                s.dialog.dismiss()
                a.findViewById<View>(android.R.id.content).snack(a.getString(R.string.share_link_copied))
            })
            s.foot(a.button(a.getString(R.string.done)) {
                app.store.updateTracker(t.id) { it.copy(shares = it.shares + rec(a.getString(R.string.share_someone_qr))) }
                s.dialog.dismiss()
            })
        } else {
            val npub = TextInputEditText(a)
            box.addView(OutlinedField(a).apply { hint = a.getString(R.string.share_their_npub); addView(npub) }, lp().margins(t = a.dp(16)))
            if (t.isShared || app.keysFor(t) == null) box.addView(a.text(R.style.TextAppearance_LibreTag_BodySmall, a.getString(R.string.share_dm_needs_owner)))
            s.foot(a.button(a.getString(R.string.share_send_invite), Btn.FILLED, R.drawable.ic_send) {
                val raw = npub.text?.toString().orEmpty()
                val pk = Bech32.normalizeNpub(raw)?.let { Bech32.pubkey(it) }
                if (pk == null) { npub.error = a.getString(R.string.npub_invalid); return@button }
                a.lifecycleScope.launch {
                    val ok = app.sendDm(t, pk, a.getString(R.string.share_dm_text, t.name, link)).ok > 0
                    if (ok) app.store.updateTracker(t.id) { it.copy(shares = it.shares + rec(Bech32.normalizeNpub(raw)!!.take(16) + "…")) }
                    s.dialog.dismiss()
                    a.findViewById<View>(android.R.id.content).snack(a.getString(if (ok) R.string.share_dm_sent else R.string.share_dm_failed))
                }
            })
        }
    }

    /** Tag menu > Export history: GPX (GPS fixes) or CSV (every report), through the share sheet. */
    fun export(a: AppCompatActivity, t: Tracker) {
        val s = Sheet(a, a.getString(R.string.export_title), t.name)
        val fixes = t.positions.count { it.hasFix }
        s.p(a.getString(R.string.export_text, t.positions.size, fixes))
        fun share(ext: String, mime: String, body: String) {
            val dir = a.cacheDir.resolve("export").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() } // only the newest export stays on the phone
            val f = dir.resolve(HistoryExport.fileName(t, ext, System.currentTimeMillis()))
            f.writeText(body)
            val uri = androidx.core.content.FileProvider.getUriForFile(a, "${a.packageName}.files", f)
            a.startActivity(android.content.Intent.createChooser(android.content.Intent(android.content.Intent.ACTION_SEND).setType(mime)
                .putExtra(android.content.Intent.EXTRA_STREAM, uri).putExtra(android.content.Intent.EXTRA_SUBJECT, t.name)
                .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION), a.getString(R.string.export_title)))
            s.dialog.dismiss()
        }
        s.body.addView(a.listItem(R.drawable.ic_map, a.getString(R.string.export_gpx), a.getString(R.string.export_gpx_sub)) {
            if (fixes == 0) a.findViewById<View>(android.R.id.content).snack(a.getString(R.string.export_no_fixes))
            else share("gpx", "application/gpx+xml", HistoryExport.gpx(t, "LibreTag ${BuildConfig.VERSION_NAME}"))
        })
        s.body.addView(a.listItem(R.drawable.ic_download, a.getString(R.string.export_csv), a.getString(R.string.export_csv_sub)) {
            share("csv", "text/csv", HistoryExport.csv(t))
        })
        s.p(a.getString(R.string.export_privacy))
        s.foot(a.button(a.getString(R.string.close), Btn.TEXT) { s.dialog.dismiss() })
        s.show()
    }

    /** "Get a tag": the hardware, with affiliate links (marked as such). */
    fun getTag(a: AppCompatActivity) {
        val s = Sheet(a, a.getString(R.string.get_tag_title))
        s.p(a.getString(R.string.get_tag_text))
        s.body.addView(a.listItem(R.drawable.ic_chip, a.getString(R.string.get_tag_x1), a.getString(R.string.get_tag_x1_sub)) { a.openUrl(GET_TAG_X1) })
        s.body.addView(a.listItem(R.drawable.ic_chip, a.getString(R.string.get_tag_l1), a.getString(R.string.get_tag_l1_sub)) { a.openUrl(GET_TAG_L1) })
        s.body.addView(a.text(R.style.TextAppearance_LibreTag_BodySmall, a.getString(R.string.get_tag_affiliate), R.color.ink3), lp().margins(t = a.dp(12)))
        s.foot(a.button(a.getString(R.string.close), Btn.TEXT) { s.dialog.dismiss() })
        s.show()
    }

    /** Seeed affiliate links (the SenseCAP X1 is the primary target). */
    const val GET_TAG_X1 = "https://www.seeedstudio.com/sensecap-meshtracker-x1-meshtastic-gps-tracker-p-6935.html?sensecap_affiliate=sRobOdH&referring_service=link"
    const val GET_TAG_L1 = "https://www.seeedstudio.com/Wio-Tracker-L1-Pro-p-6454.html?sensecap_affiliate=sRobOdH&referring_service=link"
}
