package app.libretag.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.NestedScrollView
import androidx.lifecycle.lifecycleScope
import app.libretag.LibreTagApp
import app.libretag.R
import app.libretag.data.Tracker
import app.libretag.data.Zone
import app.libretag.ui.map.MapZone
import app.libretag.ui.map.TagMap
import app.libretag.ui.map.ZoneEditOverlay
import app.libretag.usb.WriteResult
import com.google.android.material.button.MaterialButton
import com.google.android.material.slider.Slider
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * A map with one place being edited (the draft: blue, with a size handle) over the tag's other
 * places, and a panel below it that the host fills. A tap on the map moves the draft; it never
 * adds a circle. Only while there is no draft and [placeOnTap] is set does a tap create one (a tag
 * without a fix: the owner shows where it rests). The map starts at the tag's last position, so the
 * common case (the tag is at the place now) needs no map interaction; no location permission.
 */
@SuppressLint("ViewConstructor")
class ZoneEditor(ctx: Context, private val center: Pair<Double, Double>?, mark: Pair<String, String>, tagName: String) : LinearLayout(ctx) {
    val map = TagMap(ctx)
    val panel: LinearLayout = ctx.vbox { setPadding(dp(20), dp(12), dp(20), dp(12)) }
    var others: List<Zone> = emptyList()
        set(v) { field = v; redraw() }
    var draft: Zone? = null
        set(v) { field = v; redraw() }
    /** the draft moved or changed size on the map */
    var onDraft: (Zone) -> Unit = {}
    /** creates the draft where the map was tapped, while there is none */
    var placeOnTap: ((Double, Double) -> Zone)? = null
    /** the owner moved the draft at least once (the hint can go) */
    var moved = false
        private set
    private val overlay = ZoneEditOverlay(ctx, { gp -> tap(gp.latitude, gp.longitude) }) { r -> resize(r.toInt()) }

    init {
        orientation = VERTICAL
        map.setOverlays(overlay)
        overlay.tagName = tagName
        overlay.tag = center?.let { Triple(it.first, it.second, mark) }
        overlay.labelFor = { z -> z.name + " · " + Format.distance(context, z.radiusM) }
        addView(map, LayoutParams(MATCH, 0, 1f))
        val scroll = NestedScrollView(ctx).apply { addView(panel) }
        addView(ctx.divider())
        addView(scroll, LayoutParams(MATCH, WRAP))
        // the panel takes at most 45 % under the map
        viewTreeObserver.addOnGlobalLayoutListener {
            val max = (height * 0.45f).toInt()
            val want = if (panel.height > max) max else WRAP
            if (scroll.layoutParams.height != want) { scroll.layoutParams = (scroll.layoutParams as LayoutParams).apply { height = want }; scroll.requestLayout() }
        }
        map.post { fit() }
    }

    fun fit() {
        val pts = (others + listOfNotNull(draft)).map { Triple(it.lat, it.lon, it.radiusM * 1.5) } +
            listOfNotNull(center?.let { Triple(it.first, it.second, 300.0) })
        if (pts.isNotEmpty()) map.fit(pts, 17.0) else map.map.controller.setZoom(7.0)
    }

    /** where a new draft goes: the tag, else the middle of the map */
    fun spot(): Pair<Double, Double> = center ?: map.map.mapCenter.let { it.latitude to it.longitude }

    private fun tap(lat: Double, lon: Double) {
        val d = draft ?: placeOnTap?.invoke(lat, lon) ?: return
        moved = true
        draft = d.copy(lat = lat, lon = lon)
        onDraft(draft!!)
    }

    private fun resize(r: Int) {
        val d = draft ?: return
        draft = d.copy(radiusM = r.coerceIn(MIN_R, MAX_R))
        onDraft(draft!!)
    }

    private fun redraw() {
        val all = others + listOfNotNull(draft)
        overlay.zones = all.map { MapZone(it.name, it.lat, it.lon, it.radiusM.toDouble()) }
        overlay.selected = if (draft != null) all.size - 1 else -1
        map.map.invalidate()
    }

    companion object {
        /** firmware: 20..5000 m */
        const val MIN_R = 20
        const val MAX_R = 2000
    }
}

/**
 * The panel of a place being added or moved: name, size slider (in step with the map's handle),
 * optionally the two notification switches, a one-line hint, a result line and the action button.
 */
class DraftPanel(
    private val a: AppCompatActivity,
    private val ed: ZoneEditor,
    private val tagName: String,
    notify: NotifyChoice?,
    withYouNote: Boolean,
) {
    private val ctx: Context = a
    val nameEdit = TextInputEditText(a).apply { setSingleLine() }
    val nameBox = OutlinedField(a).apply { hint = a.getString(R.string.place_name); addView(nameEdit) }
    private val sizeLabel = a.text(R.style.TextAppearance_LibreTag_BodyMedium, null, R.color.ink2)
    private val slider = Slider(a).apply {
        valueFrom = ZoneEditor.MIN_R.toFloat(); valueTo = ZoneEditor.MAX_R.toFloat(); stepSize = 10f
        addOnChangeListener { _, v, fromUser -> if (fromUser) ed.draft?.let { ed.draft = it.copy(radiusM = v.toInt()); sizeText(v.toInt()) } }
    }
    val hint: TextView = a.text(R.style.TextAppearance_LibreTag_BodySmall, null, R.color.ink2)
    val status: TextView = a.text(R.style.TextAppearance_LibreTag_BodyMedium, null, R.color.ink).apply { visibility = View.GONE }
    val actions: LinearLayout = a.hbox()
    val choice = notify

    init {
        ed.panel.removeAllViews()
        ed.panel.addView(nameBox, lp())
        ed.panel.addView(sizeLabel, lp().margins(t = a.dp(12)))
        ed.panel.addView(slider, lp())
        notify?.let { ed.panel.addView(it.view(a, withYouNote)) }
        ed.panel.addView(hint, lp().margins(t = a.dp(4)))
        ed.panel.addView(status, lp().margins(t = a.dp(8)))
        ed.panel.addView(actions, lp().margins(t = a.dp(12)))
        ed.onDraft = { sync() }
        // the circle's label on the map follows the name
        nameEdit.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(e: android.text.Editable?) {
                ed.draft?.let { d -> ed.draft = d.copy(name = e?.toString()?.trim().orEmpty().ifBlank { " " }) }
            }
        })
        sync()
    }

    private fun sizeText(r: Int) { sizeLabel.text = ctx.getString(R.string.place_size) + " · " + Format.distance(ctx, r.toDouble()) }

    /** the slider, the size and the hint follow the draft */
    fun sync() {
        val d = ed.draft
        val r = d?.radiusM ?: DEFAULT_R
        sizeText(r)
        slider.isEnabled = d != null
        val v = (r / 10 * 10).toFloat().coerceIn(slider.valueFrom, slider.valueTo)
        if (slider.value != v) slider.value = v
        slider.contentDescription = ctx.getString(R.string.a11y_zone_radius, d?.name ?: "")
        hint.text = when {
            d == null -> ctx.getString(R.string.place_tap_hint, tagName)
            !ed.moved -> ctx.getString(R.string.place_move_hint)
            else -> null
        }
        hint.visibility = if (hint.text.isNullOrEmpty()) View.GONE else View.VISIBLE
    }

    /** The name if it is usable among [taken] (else the field shows why). */
    fun name(taken: Collection<String>, self: String? = null): String? {
        val n = PlaceLogic.cleanName(nameEdit.text?.toString().orEmpty(), taken, self)
        nameBox.error = when {
            n != null -> null
            nameEdit.text.isNullOrBlank() -> ctx.getString(R.string.place_name_empty)
            else -> ctx.getString(R.string.place_name_taken)
        }
        return n
    }

    fun enable(on: Boolean) {
        nameBox.isEnabled = on; slider.isEnabled = on && ed.draft != null
    }

    fun say(s: String?) { status.text = s; status.visibility = if (s == null) View.GONE else View.VISIBLE }

    companion object { const val DEFAULT_R = 150 }
}

/**
 * The map editor of a tag's GPS places. With [EXTRA_ADD]: a new place, its circle already on the
 * tag's last position. With [EXTRA_PLACE]: move or resize that place. On save it writes to the tag
 * right away when the tag is on the cable and shows that on its button; the screen stays until the
 * tag answered. Without either extra it opens the Places list (the tag menu's entry).
 */
class ZonesActivity : AppCompatActivity() {
    private val app get() = LibreTagApp.instance
    private lateinit var editor: ZoneEditor
    private lateinit var panel: DraftPanel
    private lateinit var go: MaterialButton
    private lateinit var usbh: UsbHelper
    private lateinit var tagId: String
    private var place: String? = null
    /** the change is in the app already (a retry only writes) */
    private var stored = false

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val t = intent.getStringExtra(MainActivity.EXTRA_TAG)?.let { app.store.get(it) } ?: return finish()
        tagId = t.id
        place = intent.getStringExtra(EXTRA_PLACE)?.takeIf { n -> t.zones.any { it.name == n } }
        if (place == null && !intent.getBooleanExtra(EXTRA_ADD, false)) {
            startActivity(Intent(this, TagSettingsActivity::class.java).putExtra(MainActivity.EXTRA_TAG, t.id))
            return finish()
        }
        usbh = UsbHelper(this)
        usbh.start()
        val fix = fixOf(t)
        editor = ZoneEditor(this, fix, t.markColor to t.markSymbol, t.name)
        val editing = t.zones.firstOrNull { it.name == place }
        editor.others = t.zones.filter { it.name != place }
        val adding = editing == null
        val notify = if (adding) PlaceLogic.effective(null, t.type).let { NotifyChoice(it, it) } else null
        panel = DraftPanel(this, editor, t.name, notify, t.knownDevices.isNotEmpty() && !t.people)
        val home = getString(R.string.zone_home)
        if (adding) {
            val name = PlaceLogic.suggestName(home, PlaceLogic.names(t))
            panel.nameEdit.setText(name)
            editor.draft = fix?.let { Zone(name.ifBlank { " " }, it.first, it.second, DraftPanel.DEFAULT_R) }
            editor.placeOnTap = { lat, lon -> Zone(panel.nameEdit.text?.toString().orEmpty().ifBlank { " " }, lat, lon, DraftPanel.DEFAULT_R) }
        } else {
            panel.nameEdit.setText(editing!!.name)
            editor.draft = editing
        }
        panel.sync()
        go = button(getString(if (adding) R.string.place_add_btn else R.string.save)) { save() }
        panel.actions.addView(go, LinearLayout.LayoutParams(0, WRAP, 1f))
        val bar = hbox {
            minimumHeight = dp(64)
            setPadding(dp(4), 0, dp(12), 0)
            addView(iconButton(R.drawable.ic_close, getString(R.string.a11y_close)) { finish() })
            addView(vbox {
                addView(text(R.style.TextAppearance_LibreTag_TitleLarge, if (adding) getString(R.string.place_add) else editing!!.name).apply { ellipsize1() })
                addView(text(R.style.TextAppearance_LibreTag_LabelMedium, t.name, R.color.ink3))
            }, LinearLayout.LayoutParams(0, WRAP, 1f).margins(l = dp(4)))
        }
        val col = vbox {
            setBackgroundColor(col(R.color.bg))
            addView(bar)
            addView(divider())
            addView(editor, LinearLayout.LayoutParams(MATCH, 0, 1f))
        }
        setContentView(col)
        col.insetPadding(top = true, bottom = true)
    }

    override fun onResume() { super.onResume(); if (::editor.isInitialized) editor.map.onResume() }
    override fun onPause() { if (::editor.isInitialized) editor.map.onPause(); super.onPause() }

    private fun save() {
        val t = app.store.get(tagId) ?: return finish()
        if (!stored) {
            val d = editor.draft ?: run { panel.hint.animate().alpha(0.2f).withEndAction { panel.hint.animate().alpha(1f) }; return }
            val name = panel.name(PlaceLogic.names(t), self = place) ?: return
            val z = d.copy(name = name)
            val before = t.zones.firstOrNull { it.name == place }
            val moved = before == null || before.lat != z.lat || before.lon != z.lon || before.radiusM != z.radiusM
            if (moved && app.usb.tagFor(t)) PlaceWrites.claim(tagId)
            app.store.updateTracker(tagId) { cur ->
                if (before == null) {
                    val c = panel.choice
                    val def = PlaceLogic.effective(null, cur.type)
                    PlaceLogic.add(cur, z.copy(notifyLeave = c?.leave?.takeIf { it != def }, notifyArrive = c?.arrive?.takeIf { it != def }))
                } else {
                    var out = cur
                    if (moved) out = PlaceLogic.reshape(out, before.name, z.lat, z.lon, z.radiusM)
                    if (name != before.name) out = PlaceLogic.rename(out, before.name, name)
                    out
                }
            }
            stored = true
            if (!moved || !app.usb.tagFor(t)) return finish()
        }
        write(t)
    }

    /** Writes on the cable with the progress on the button; the write runs in the app's scope, so leaving doesn't cut it. */
    private fun write(t: Tracker) {
        panel.enable(false)
        panel.say(null)
        go.working(true, getString(R.string.place_writing_to, t.name))
        lifecycleScope.launch {
            val r = app.scope.async { PlaceWrites.write(t.id) }.await()
            when (r) {
                WriteResult.OK -> {
                    go.working(false, getString(R.string.place_saved_on, t.name))
                    go.isEnabled = false
                    go.setIconResource(R.drawable.ic_check)
                    go.announceForAccessibility(go.text)
                    delay(900)
                    finish()
                }
                WriteResult.NOT_CONNECTED, WriteResult.UNSUPPORTED -> finish()
                WriteResult.FAILED -> {
                    go.working(false, getString(R.string.try_again))
                    panel.say(getString(R.string.svc_write_failed))
                    if (panel.actions.childCount == 1) panel.actions.addView(button(getString(R.string.done), Btn.TEXT) { finish() }, 0)
                }
            }
        }
    }

    companion object {
        const val EXTRA_ADD = "add_place"
        const val EXTRA_PLACE = "place"

        /** where the tag was last: its newest fix, else a usable estimate */
        fun fixOf(t: Tracker): Pair<Double, Double>? = t.lastFix?.let { it.lat to it.lon }
            ?: t.last?.takeIf { it.lastFixTime > 0 }?.let { it.lastLat to it.lastLon }
            ?: t.last?.est?.takeIf { it.state == "ok" }?.let { it.lat to it.lon }
    }
}
