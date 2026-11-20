package app.libretag.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.text.Editable
import android.text.InputFilter
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import app.libretag.LibreTagApp
import app.libretag.R
import app.libretag.data.Tracker
import app.libretag.data.WifiZoneRec
import app.libretag.usb.SnapResult
import app.libretag.usb.TagOps
import app.libretag.usb.WifiTestScan
import app.libretag.usb.WifiVerdict
import app.libretag.usb.WifiZoneCommands
import app.libretag.usb.WriteResult
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.CircularProgressIndicator
import com.google.android.material.progressindicator.CircularProgressIndicatorSpec
import com.google.android.material.progressindicator.IndeterminateDrawable
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.slider.Slider
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * GPS places are written to the tag as one list. This keeps whether a write runs and how the last
 * one on this cable connection ended, so the Places list and the map editor show the same chip.
 */
object PlaceWrites {
    data class St(val writing: Boolean = false, val last: WriteResult? = null)
    val state = MutableStateFlow<Map<String, St>>(emptyMap())
    fun of(id: String) = state.value[id] ?: St()
    private fun put(id: String, s: St) { state.value = state.value + (id to s) }

    /** Writes the tag's GPS places now. NOT_CONNECTED leaves no trace: the place simply waits for the cable. */
    suspend fun write(id: String): WriteResult {
        val app = LibreTagApp.instance
        val t = app.store.get(id) ?: return WriteResult.FAILED
        if (!app.usb.tagFor(t)) return WriteResult.NOT_CONNECTED
        put(id, St(writing = true))
        val r = TagOps.writeZones(t)
        put(id, St(writing = false, last = r.takeIf { it != WriteResult.NOT_CONNECTED && it != WriteResult.OK }))
        return r
    }

    /** Starts a write in the app's scope (it outlives the screen); marked as running at once, so a second call does nothing. */
    /** Marks a write as running before the change lands in the store, so the Places list doesn't start a second one. */
    fun claim(id: String) { put(id, St(writing = true)) }

    fun launch(id: String) {
        val app = LibreTagApp.instance
        val t = app.store.get(id) ?: return
        if (!app.usb.tagFor(t) || of(id).writing) return
        put(id, St(writing = true))
        app.scope.launch { write(id) }
    }

    /** The cable went away: a failure is about that connection only (an old firmware stays old). */
    fun disconnected(id: String) {
        val s = of(id)
        if (s.last == WriteResult.FAILED) put(id, s.copy(last = null))
    }

    fun chip(t: Tracker, onCable: Boolean): PlaceChip = of(t.id).let { PlaceLogic.gpsChip(t.zonesOnTag, onCable, it.writing, it.last) }
}

/** A small state chip: on the tag (green), waiting or not written (amber), writing or app only (neutral). */
fun Context.placeChip(c: PlaceChip): View {
    val label = getString(when (c) {
        PlaceChip.ON_TAG -> R.string.place_on_tag
        PlaceChip.WAITS_CABLE -> R.string.place_waits_cable
        PlaceChip.WRITING -> R.string.place_writing
        PlaceChip.NOT_WRITTEN -> R.string.place_not_written
        PlaceChip.APP_ONLY -> R.string.place_app_only
    })
    return when (c) {
        PlaceChip.ON_TAG -> statusTag(label, true)
        PlaceChip.WAITS_CABLE, PlaceChip.NOT_WRITTEN -> statusTag(label, false)
        else -> statusTag(label, true).apply { background = rounded(col(R.color.surf2), 4f, col(R.color.line)) }
    }
}

/** Puts a spinning indicator into a button and disables it, or restores it ([label] null = keep the text). */
fun MaterialButton.working(on: Boolean, label: CharSequence? = null) {
    label?.let { text = it }
    isEnabled = !on
    if (on) {
        val spec = CircularProgressIndicatorSpec(context, null, 0, com.google.android.material.R.style.Widget_Material3_CircularProgressIndicator_ExtraSmall)
        icon = IndeterminateDrawable.createCircularDrawable(context, spec)
        iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START
    } else icon = null
}

/** The close button of a [Sheet] (found by its description), so a sheet at work can't be closed. */
internal fun Sheet.lock(on: Boolean) {
    dialog.setCancelable(!on)
    dialog.behavior.isDraggable = !on
    val found = ArrayList<View>()
    dialog.window?.decorView?.findViewsWithText(found, ctx.getString(R.string.a11y_close), View.FIND_VIEWS_WITH_CONTENT_DESCRIPTION)
    found.forEach { it.isEnabled = !on; it.alpha = if (on) 0.3f else 1f }
}

/**
 * The Places of one tag (ui-review 2.4): GPS places and Wi-Fi places in one list, each with its
 * state chip; tapping one opens its sheet (name, size, the two notification switches, delete);
 * "Add a place" opens the map editor or the Wi-Fi snap sheet. It follows the store and the cable by
 * itself, so a host only adds it ([showTitle] false when the host has its own heading).
 */
@SuppressLint("ViewConstructor")
class PlacesView(ctx: Context, private val tagId: String, private val showTitle: Boolean = true) : LinearLayout(ctx) {
    private val a = ctx as AppCompatActivity
    private val app get() = LibreTagApp.instance
    private var job: Job? = null
    private var wasOnCable = false

    init { orientation = VERTICAL }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        job?.cancel()
        job = a.lifecycleScope.launch {
            combine(app.store.trackers, app.usb.state, PlaceWrites.state) { _, _, _ -> }.collect { render() }
        }
    }

    override fun onDetachedFromWindow() { job?.cancel(); job = null; super.onDetachedFromWindow() }

    private fun gpsCapable(t: Tracker) = !t.wifiTag || t.zones.isNotEmpty() || t.lastFix != null

    private fun render() {
        val t = app.store.get(tagId) ?: return
        val onCable = app.usb.tagFor(t)
        if (wasOnCable && !onCable) PlaceWrites.disconnected(t.id)
        wasOnCable = onCable
        val ws = PlaceWrites.of(t.id)
        if (PlaceLogic.autoWrite(t.zonesOnTag, onCable, ws.writing, ws.last, t.zones.isNotEmpty())) {
            PlaceWrites.launch(t.id)
        }
        removeAllViews()
        val ctx = context
        if (showTitle) addView(ctx.text(R.style.TextAppearance_LibreTag_TitleSmall, ctx.getString(R.string.places_title)).apply {
            androidx.core.view.ViewCompat.setAccessibilityHeading(this, true)
        }, lp().margins(t = ctx.dp(8)))
        val empty = t.zones.isEmpty() && t.wifiZones.isEmpty()
        addView(ctx.text(R.style.TextAppearance_LibreTag_BodyMedium,
            ctx.getString(if (empty) R.string.places_empty else R.string.places_lead, t.name), R.color.ink2), lp().margins(t = ctx.dp(4), b = ctx.dp(4)))
        val chip = PlaceWrites.chip(t, onCable)
        for (z in t.zones) addView(ctx.listItem(R.drawable.ic_zone, z.name, Format.distance(ctx, z.radiusM.toDouble()), ctx.placeChip(chip)) {
            gpsSheet(z.name)
        }.apply { contentDescription = z.name + ", " + Format.distance(ctx, z.radiusM.toDouble()) })
        for (z in t.wifiZones) addView(ctx.listItem(R.drawable.ic_wifi, z.name,
            ctx.resources.getQuantityString(R.plurals.wzn_networks, z.aps, z.aps), ctx.placeChip(PlaceChip.ON_TAG)) { wifiSheet(z.name) })
        // one line under the list when the GPS places are not where they should be
        if (t.zones.isNotEmpty()) when (chip) {
            PlaceChip.WAITS_CABLE -> addView(note(ctx.getString(R.string.place_waits_cable_sub, t.name)))
            PlaceChip.APP_ONLY -> addView(note(ctx.getString(R.string.place_app_only_sub, t.name)))
            PlaceChip.NOT_WRITTEN -> {
                addView(note(ctx.getString(R.string.svc_write_failed)))
                addView(ctx.hbox { addView(ctx.button(ctx.getString(R.string.try_again), Btn.OUTLINED, R.drawable.ic_sync) {
                    PlaceWrites.launch(t.id)
                }) })
            }
            else -> {}
        }
        // add
        val gps = gpsCapable(t)
        val wifi = t.wifiTag
        val add = ctx.button(if (gps) ctx.getString(R.string.place_add) else ctx.getString(R.string.place_add_here, t.name), Btn.OUTLINED, R.drawable.ic_plus) {
            when {
                gps && wifi -> chooseAdd(t, onCable)
                gps -> openMap(add = true)
                else -> snap(t)
            }
        }
        if (!gps && !onCable) add.isEnabled = false
        addView(ctx.hbox { addView(add) }, lp().margins(t = ctx.dp(8)))
        if (!gps && !onCable) addView(note(ctx.getString(R.string.place_needs_cable)))
    }

    private fun note(s: String) = context.text(R.style.TextAppearance_LibreTag_BodySmall, s, R.color.ink2).apply {
        layoutParams = lp().margins(t = context.dp(4), b = context.dp(4))
    }

    private fun chooseAdd(t: Tracker, onCable: Boolean) {
        val items = arrayOf(context.getString(R.string.place_add_map),
            context.getString(R.string.place_add_wifi) + if (onCable) "" else "\n" + context.getString(R.string.place_needs_cable))
        MaterialAlertDialogBuilder(a).setTitle(R.string.place_add).setItems(items) { _, i ->
            if (i == 0) openMap(add = true)
            else if (app.usb.tagFor(t)) snap(t)
            else a.findViewById<View>(android.R.id.content).snack(context.getString(R.string.place_needs_cable))
        }.setNegativeButton(R.string.cancel, null).show()
    }

    private fun openMap(add: Boolean, place: String? = null) {
        val i = Intent(a, ZonesActivity::class.java).putExtra(MainActivity.EXTRA_TAG, tagId)
        if (add) i.putExtra(ZonesActivity.EXTRA_ADD, true)
        place?.let { i.putExtra(ZonesActivity.EXTRA_PLACE, it) }
        a.startActivity(i)
    }

    private fun snap(t: Tracker) {
        SnapSheet(a, t.name, t.type, PlaceLogic.names(t), t.knownDevices.isNotEmpty() && !t.people) { name, leave, on ->
            app.store.updateTracker(t.id) { PlaceLogic.setNotify(it, name, leave, on) }
        }.show()
    }

    /** The sheet of a GPS place: name, size, the two switches, move, delete. Saved with Save. */
    private fun gpsSheet(name: String) {
        val t = app.store.get(tagId) ?: return
        val z = t.zones.firstOrNull { it.name == name } ?: return
        val ctx = context
        val s = Sheet(a, z.name, t.name)
        val nameEdit = TextInputEditText(a).apply { setText(z.name); setSingleLine() }
        val nameBox = OutlinedField(a).apply { hint = ctx.getString(R.string.place_name); addView(nameEdit) }
        s.body.addView(nameBox, lp().margins(t = ctx.dp(4)))
        var radius = z.radiusM
        val sizeLabel = ctx.text(R.style.TextAppearance_LibreTag_BodyMedium, sizeText(radius), R.color.ink2)
        s.body.addView(sizeLabel, lp().margins(t = ctx.dp(16)))
        s.body.addView(Slider(a).apply {
            valueFrom = ZoneEditor.MIN_R.toFloat(); valueTo = ZoneEditor.MAX_R.toFloat(); stepSize = 10f
            value = (radius / 10 * 10).toFloat().coerceIn(valueFrom, valueTo)
            contentDescription = ctx.getString(R.string.a11y_zone_radius, z.name)
            addOnChangeListener { _, v, fromUser -> if (fromUser) { radius = v.toInt(); sizeLabel.text = sizeText(radius) } }
        }, lp())
        s.body.addView(ctx.hbox { addView(ctx.button(ctx.getString(R.string.place_move), Btn.TEXT, R.drawable.ic_map) {
            s.dialog.dismiss(); openMap(add = false, place = z.name)
        }) })
        when (PlaceWrites.chip(t, app.usb.tagFor(t))) {
            PlaceChip.WAITS_CABLE -> s.body.addView(note(ctx.getString(R.string.place_waits_cable_sub, t.name)))
            PlaceChip.APP_ONLY -> s.body.addView(note(ctx.getString(R.string.place_app_only_sub, t.name)))
            else -> {}
        }
        val choice = NotifyChoice(PlaceLogic.effective(z.notifyLeave, t.type), PlaceLogic.effective(z.notifyArrive, t.type))
        s.body.addView(choice.view(a, t.knownDevices.isNotEmpty() && !t.people), lp().margins(t = ctx.dp(8)))
        s.body.addView(ctx.hbox { addView(ctx.button(ctx.getString(R.string.place_delete), Btn.TEXT, R.drawable.ic_trash) {
            confirmDelete(z.name, null) {
                app.store.updateTracker(t.id) { PlaceLogic.delete(it, z.name) }
                s.dialog.dismiss()
                PlaceWrites.launch(t.id)
            }
        }.apply { setTextColor(ctx.col(R.color.crit)); iconTint = android.content.res.ColorStateList.valueOf(ctx.col(R.color.crit)) }) },
            lp().margins(t = ctx.dp(8)))
        s.foot(ctx.button(ctx.getString(R.string.cancel), Btn.TEXT) { s.dialog.dismiss() },
            ctx.button(ctx.getString(R.string.save)) {
                val cur = app.store.get(tagId) ?: return@button
                val n = PlaceLogic.cleanName(nameEdit.text?.toString().orEmpty(), PlaceLogic.names(cur), self = z.name) ?: run {
                    nameBox.error = ctx.getString(if (nameEdit.text.isNullOrBlank()) R.string.place_name_empty else R.string.place_name_taken)
                    return@button
                }
                val resized = radius != z.radiusM
                app.store.updateTracker(tagId) { tr ->
                    var out = tr
                    if (resized) out = PlaceLogic.reshape(out, z.name, z.lat, z.lon, radius)
                    if (n != z.name) out = PlaceLogic.rename(out, z.name, n)
                    out = PlaceLogic.setNotify(out, n, true, choice.leave)
                    PlaceLogic.setNotify(out, n, false, choice.arrive)
                }
                s.dialog.dismiss()
                if (resized) PlaceWrites.launch(tagId)
            })
        s.show()
    }

    /** The sheet of a Wi-Fi place: the two switches, Check here, delete (both over the cable). */
    private fun wifiSheet(name: String) {
        val t = app.store.get(tagId) ?: return
        val z = t.wifiZones.firstOrNull { it.name == name } ?: return
        val ctx = context
        val onCable = app.usb.tagFor(t)
        val s = Sheet(a, z.name, ctx.resources.getQuantityString(R.plurals.wzn_networks, z.aps, z.aps))
        val choice = NotifyChoice(PlaceLogic.effective(z.notifyLeave, t.type), PlaceLogic.effective(z.notifyArrive, t.type))
        s.body.addView(choice.view(a, t.knownDevices.isNotEmpty() && !t.people))
        // Check here
        val result = ctx.hbox { gravity = Gravity.CENTER_VERTICAL }
        val check = ctx.button(ctx.getString(R.string.place_check_here), Btn.OUTLINED, R.drawable.ic_sync)
        check.setOnClickListener {
            result.removeAllViews()
            check.working(true)
            s.lock(true)
            result.addView(ctx.text(R.style.TextAppearance_LibreTag_BodySmall, ctx.getString(R.string.snap_step_check), R.color.ink2))
            a.lifecycleScope.launch {
                val r = TagOps.testWifiZones(1)
                s.lock(false)
                check.working(false)
                result.removeAllViews()
                when (PlaceLogic.checkVerdict(z.name, r, TagOps.wifiThresholds())) {
                    null -> result.addView(ctx.text(R.style.TextAppearance_LibreTag_BodySmall, ctx.getString(R.string.snap_no_answer), R.color.ink2))
                    WifiVerdict.INSIDE -> result.addView(ctx.statusTag(ctx.getString(R.string.place_check_ok), true))
                    WifiVerdict.WEAK -> result.addView(ctx.statusTag(ctx.getString(R.string.place_check_weak), false))
                    WifiVerdict.OUTSIDE -> result.addView(ctx.statusTag(ctx.getString(R.string.place_check_no), false))
                }
            }
        }
        check.isEnabled = onCable
        s.body.addView(FlowRow(a).apply { addView(check) }, lp().margins(t = ctx.dp(12)))
        s.body.addView(result, lp().margins(t = ctx.dp(4)))
        if (!onCable) s.body.addView(note(ctx.getString(R.string.place_needs_cable)))
        val del = ctx.button(ctx.getString(R.string.place_delete), Btn.TEXT, R.drawable.ic_trash) {
            confirmDelete(z.name, ctx.getString(R.string.wzn_delete_text)) {
                a.lifecycleScope.launch {
                    s.lock(true)
                    val ok = TagOps.deleteWifiZone(z.name)
                    s.lock(false)
                    s.dialog.dismiss()
                    a.findViewById<View>(android.R.id.content).snack(ctx.getString(if (ok) R.string.wzn_deleted else R.string.svc_write_failed, z.name))
                }
            }
        }.apply {
            isEnabled = onCable
            if (onCable) { setTextColor(ctx.col(R.color.crit)); iconTint = android.content.res.ColorStateList.valueOf(ctx.col(R.color.crit)) }
        }
        s.body.addView(ctx.hbox { addView(del) }, lp().margins(t = ctx.dp(8)))
        if (!onCable) s.body.addView(note(ctx.getString(R.string.place_delete_cable)))
        s.foot(ctx.button(ctx.getString(R.string.cancel), Btn.TEXT) { s.dialog.dismiss() },
            ctx.button(ctx.getString(R.string.save)) {
                app.store.updateTracker(tagId) { tr ->
                    PlaceLogic.setNotify(PlaceLogic.setNotify(tr, z.name, true, choice.leave), z.name, false, choice.arrive)
                }
                s.dialog.dismiss()
            })
        s.show()
    }

    private fun confirmDelete(name: String, text: String?, go: () -> Unit) {
        MaterialAlertDialogBuilder(a).setTitle(context.getString(R.string.wzn_delete_title, name)).apply { text?.let { setMessage(it) } }
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.wzn_delete) { _, _ -> go() }
            .show().getButton(android.content.DialogInterface.BUTTON_POSITIVE)?.setTextColor(context.col(R.color.crit))
    }

    private fun sizeText(r: Int) = context.getString(R.string.place_size) + " · " + Format.distance(context, r.toDouble())
}

/** The two notification switches of a place ("Tell me when it leaves / arrives"), with the with-you note. */
class NotifyChoice(var leave: Boolean, var arrive: Boolean) {
    fun view(ctx: Context, withYouNote: Boolean): View = ctx.vbox {
        addView(ctx.switchRow(ctx.getString(R.string.place_tell_leave),
            if (withYouNote) ctx.getString(R.string.place_not_with_you) else null, leave) { leave = it })
        addView(ctx.switchRow(ctx.getString(R.string.place_tell_arrive), null, arrive) { arrive = it })
    }
}

/**
 * Add a Wi-Fi place where the tag is now (`wzone snap`), as a bottom sheet with its own state, so
 * the progress never depends on the page behind it: name and Start, then three steps (listening
 * k of n, saving, checking) with the current one marked and a moving bar, then the result.
 * [onNotify] stores a switch change for the saved place; [type] gives the switches' defaults.
 */
class SnapSheet(
    private val a: AppCompatActivity,
    private val tagName: String,
    private val type: String,
    private val taken: List<String>,
    private val withYouNote: Boolean,
    private val onNotify: (name: String, leave: Boolean, on: Boolean) -> Unit,
) {
    private val ctx: Context = a
    private val s = Sheet(a, a.getString(R.string.place_add), tagName)
    private val nameEdit = TextInputEditText(a).apply {
        setSingleLine()
        filters = arrayOf(InputFilter.LengthFilter(WifiZoneCommands.NAME_MAX_BYTES))
    }
    private val nameBox = OutlinedField(a).apply { hint = a.getString(R.string.place_name); addView(nameEdit) }
    private val scans = WifiZoneCommands.SNAP_DEFAULT
    private val warn = ctx.text(R.style.TextAppearance_LibreTag_BodySmall, null, R.color.ink2)
    var onClosed: (() -> Unit)? = null

    init {
        nameEdit.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(e: Editable?) = checkName()
        })
    }

    /** Snapping an existing name replaces that place; a full tag takes no new one. */
    private fun checkName() {
        val clean = WifiZoneCommands.cleanName(nameEdit.text?.toString().orEmpty())
        warn.text = when {
            clean != null && taken.contains(clean) -> ctx.getString(R.string.wzn_replaces, clean)
            taken.size >= MAX_PLACES -> ctx.getString(R.string.wzn_full, MAX_PLACES)
            else -> null
        }
        warn.visibility = if (warn.text.isNullOrEmpty()) View.GONE else View.VISIBLE
    }

    companion object {
        /** Wi-Fi places a tag holds (firmware) */
        const val MAX_PLACES = 8
    }

    fun show() {
        nameEdit.setText(PlaceLogic.suggestName(ctx.getString(R.string.zone_home), taken))
        s.dialog.setOnDismissListener { onClosed?.invoke() }
        form()
        s.show()
    }

    private fun clear() { s.body.removeAllViews(); s.footer.removeAllViews(); s.footer.visibility = View.VISIBLE }

    private fun form(msg: String? = null) {
        clear()
        msg?.let { s.body.addView(ctx.text(R.style.TextAppearance_LibreTag_BodyMedium, it, R.color.ink), lp().margins(b = ctx.dp(12))) }
        (nameBox.parent as? android.view.ViewGroup)?.removeView(nameBox)
        s.body.addView(nameBox)
        (warn.parent as? android.view.ViewGroup)?.removeView(warn)
        checkName()
        s.body.addView(warn, lp().margins(t = ctx.dp(4)))
        s.body.addView(ctx.text(R.style.TextAppearance_LibreTag_BodySmall, ctx.getString(R.string.snap_wait, PlaceLogic.snapSeconds(scans)), R.color.ink2),
            lp().margins(t = ctx.dp(8)))
        s.foot(ctx.button(ctx.getString(R.string.cancel), Btn.TEXT) { s.dialog.dismiss() },
            ctx.button(ctx.getString(R.string.snap_start), Btn.FILLED, R.drawable.ic_wifi) { start() })
    }

    private lateinit var stepRows: List<Triple<View, TextView, android.widget.FrameLayout>>
    private var baseFace: android.graphics.Typeface? = null

    /** The three steps; [phase] is the current one, those before it are done. */
    private fun steps(phase: SnapPhase, scansDone: Int) {
        val labels = listOf(
            ctx.getString(R.string.snap_step_listen, PlaceLogic.scanShown(scansDone, scans), scans),
            ctx.getString(R.string.snap_step_save),
            ctx.getString(R.string.snap_step_check))
        stepRows.forEachIndexed { i, (row, label, mark) ->
            label.text = labels[i]
            val state = i.compareTo(phase.ordinal)
            label.setTextColor(ctx.col(if (state > 0) R.color.ink3 else R.color.ink))
            label.setTypeface(baseFace, if (state == 0) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
            mark.removeAllViews()
            when {
                state < 0 -> mark.addView(ctx.icon(R.drawable.ic_check, R.color.ok, 20), flp(ctx.dp(20), ctx.dp(20), Gravity.CENTER))
                state == 0 -> mark.addView(CircularProgressIndicator(ctx).apply {
                    isIndeterminate = true; indicatorSize = ctx.dp(18); trackThickness = ctx.dp(2)
                }, flp(WRAP, WRAP, Gravity.CENTER))
                else -> mark.addView(View(ctx).apply { background = ctx.rounded(0, 99f, ctx.col(R.color.ink3), 1.5f) }, flp(ctx.dp(12), ctx.dp(12), Gravity.CENTER))
            }
            row.contentDescription = labels[i]
        }
    }

    private fun start() {
        val name = WifiZoneCommands.cleanName(nameEdit.text?.toString().orEmpty()) ?: run {
            nameBox.error = ctx.getString(R.string.place_name_empty); return
        }
        nameBox.error = null
        (ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(nameEdit.windowToken, 0)
        clear()
        s.footer.visibility = View.GONE
        s.lock(true)
        stepRows = List(3) {
            val mark = android.widget.FrameLayout(ctx)
            val label = ctx.text(R.style.TextAppearance_LibreTag_BodyLarge).also { if (baseFace == null) baseFace = it.typeface }
            val row = ctx.hbox {
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = ctx.dp(44)
                addView(mark, LinearLayout.LayoutParams(ctx.dp(24), ctx.dp(24)).margins(r = ctx.dp(14)))
                addView(label, LinearLayout.LayoutParams(0, WRAP, 1f))
            }
            s.body.addView(row)
            Triple(row, label, mark)
        }
        s.body.addView(LinearProgressIndicator(ctx).apply { isIndeterminate = true; trackCornerRadius = ctx.dp(2) }, lp().margins(t = ctx.dp(12)))
        s.body.addView(ctx.text(R.style.TextAppearance_LibreTag_BodySmall, ctx.getString(R.string.snap_wait, PlaceLogic.snapSeconds(scans)), R.color.ink2),
            lp().margins(t = ctx.dp(8)))
        steps(SnapPhase.LISTEN, 0)
        s.body.announceForAccessibility(ctx.getString(R.string.snap_step_listen_short))
        a.lifecycleScope.launch {
            var done = 0
            val r: SnapResult? = TagOps.snapWifiZone(name, scans) { step ->
                a.runOnUiThread { done = step.k + 1; steps(PlaceLogic.snapPhase(done, scans, false), done) }
            }
            var test: List<WifiTestScan>? = null
            if (r?.zone != null) {
                steps(SnapPhase.CHECK, scans)
                test = TagOps.testWifiZones(1)
            }
            s.lock(false)
            result(r, PlaceLogic.snapOutcome(r, test, TagOps.wifiThresholds()))
        }
    }

    private fun result(r: SnapResult?, out: SnapOutcome) {
        val saved = r?.zone?.name
        when (out) {
            SnapOutcome.NONE -> form(ctx.getString(R.string.snap_none))
            SnapOutcome.NO_ANSWER -> form(ctx.getString(R.string.snap_no_answer))
            SnapOutcome.WEAK -> {
                clear()
                s.body.addView(ctx.text(R.style.TextAppearance_LibreTag_BodyLarge, ctx.getString(R.string.snap_weak)))
                s.foot(ctx.button(ctx.getString(R.string.snap_keep), Btn.TEXT) { s.dialog.dismiss() },
                    ctx.button(ctx.getString(R.string.try_again), Btn.FILLED, R.drawable.ic_sync) { nameEdit.setText(saved); start() })
            }
            SnapOutcome.OK, SnapOutcome.UNCHECKED -> {
                clear()
                s.body.addView(ctx.text(R.style.TextAppearance_LibreTag_BodyLarge,
                    ctx.getString(if (out == SnapOutcome.OK) R.string.snap_ok else R.string.snap_unchecked, saved)), lp().margins(b = ctx.dp(8)))
                val def = PlaceLogic.effective(null, type)
                val choice = NotifyChoice(def, def)
                s.body.addView(choice.view(ctx, withYouNote))
                s.foot(ctx.button(ctx.getString(R.string.done)) {
                    if (saved != null) {
                        if (choice.leave != def) onNotify(saved, true, choice.leave)
                        if (choice.arrive != def) onNotify(saved, false, choice.arrive)
                    }
                    s.dialog.dismiss()
                })
            }
        }
    }
}
