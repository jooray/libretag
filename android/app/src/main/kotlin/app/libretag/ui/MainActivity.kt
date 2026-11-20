package app.libretag.ui

import app.libretag.support.SupportCheckout
import android.Manifest
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import androidx.window.layout.WindowMetricsCalculator
import app.libretag.CommandKind
import app.libretag.ble.BleCommandBeacon
import app.libretag.LibreTagApp
import app.libretag.R
import app.libretag.TrackerService
import app.libretag.data.BatteryModel
import app.libretag.data.BatterySample
import app.libretag.data.Position
import app.libretag.data.Diagnosis
import app.libretag.data.Diagnosis.Action as A
import app.libretag.data.Status
import app.libretag.data.TrackPlan
import app.libretag.data.StatusKind
import app.libretag.data.TagStatus
import app.libretag.data.Timeline
import app.libretag.data.Tracker
import app.libretag.data.Welcome
import app.libretag.nostr.DmPublish
import app.libretag.support.SupportAsk
import app.libretag.ui.map.AllTagsOverlay
import app.libretag.ui.map.MapRepeater
import app.libretag.ui.map.MapTag
import app.libretag.ui.map.MapZone
import app.libretag.ui.map.TagMap
import app.libretag.ui.map.TrackPoint
import app.libretag.ui.map.TrailOverlay
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import org.osmdroid.util.GeoPoint

/**
 * Tags: home (map + signpost list), tag detail (swipe between tags), and the larger layouts:
 * list-detail on an unfolded foldable, list | map | panel on a tablet, map above the hinge when
 * half open. The selected tag and the selected report survive folding, rotation and recreation.
 */
class MainActivity : AppCompatActivity(), DetailHost {
    private val app get() = LibreTagApp.instance
    enum class Layout { COMPACT, MEDIUM, EXPANDED, TABLETOP }

    private var layout = Layout.COMPACT
    private var hingeTop = 0
    private var selected: String? = null
    private val sels = HashMap<String, Sel>()
    private var showReps = false
    private var order: List<String> = emptyList()
    private var trailFitKey: String? = null
    private var trailFocusN: Long? = null
    private var trailFitAll = false
    private var allFitted = false

    private lateinit var root: FrameLayout
    private var homeBar: HomeBar? = null
    private var list: TagListView? = null
    private var fab: ExtendedFloatingActionButton? = null
    private var allMap: TagMap? = null
    private var allOverlay: AllTagsOverlay? = null
    private var trailMap: TagMap? = null
    private var trailOverlay: TrailOverlay? = null
    private var repsChip: Chip? = null
    private var head: DetailHead? = null
    private var pager: ViewPager2? = null
    private var bodies: BodyAdapter? = null
    private var homeSheet: HomeSheet? = null
    private val back = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = closeDetail()
    }

    /** 0.12: "Open over Bluetooth" and "Check if it's here" from the Why card */
    private lateinit var bleh: BleConsoleHelper

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        bleh = BleConsoleHelper(this, { selected }) { render() }
        savedInstanceState?.let { b ->
            selected = b.getString("sel")
            showReps = b.getBoolean("reps")
            b.getStringArray("sels")?.forEach { e ->
                val p = e.split('|')
                if (p.size == 3) sels[p[0]] = Sel(p[1].toInt(), p[2].toIntOrNull())
            }
            order = b.getStringArray("order")?.toList() ?: emptyList()
        }
        intent?.getStringExtra(EXTRA_TAG)?.let { if (savedInstanceState == null) selected = it }
        root = FrameLayout(this).apply { setBackgroundColor(col(R.color.bg)) }
        setContentView(root)
        root.insetPadding(top = true, bottom = true)
        onBackPressedDispatcher.addCallback(this, back)
        layout = computeLayout(null)
        build()

        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        TrackerService.start(this)
        askBatteryExemption()
        SupportAsk.onLaunch(this)

        lifecycleScope.launch {
            WindowInfoTracker.getOrCreate(this@MainActivity).windowLayoutInfo(this@MainActivity).collect { info ->
                val fold = info.displayFeatures.filterIsInstance<FoldingFeature>().firstOrNull()
                val next = computeLayout(fold)
                if (next != layout || (next == Layout.TABLETOP && hingeTop != fold?.bounds?.top)) {
                    hingeTop = fold?.bounds?.top ?: 0
                    layout = next
                    build()
                }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                app.store.trackers.combine(app.reach) { t, r -> t to r }.collect { render() }
            }
        }
        lifecycleScope.launch {
            // 0.12: the diagnosis changed (path, cable, sighting, receipt)
            repeatOnLifecycle(Lifecycle.State.STARTED) { app.diag.collect { render() } }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                app.confirmed.collect { (id, k) ->
                    val t = app.store.get(id) ?: return@collect
                    // the open detail shows it on the slip; the list (or another tag) gets a snackbar
                    if (selected != id) root.snack(getString(R.string.snack_confirmed, t.name, TagText.cmdName(this@MainActivity, k)))
                    render()
                }
            }
        }
        lifecycleScope.launch {
            // a Wi-Fi lookup's progress and result show in the detail's Where row
            repeatOnLifecycle(Lifecycle.State.STARTED) { WifiPositioning.states.collect { render() } }
        }
        lifecycleScope.launch {
            // ages keep counting
            repeatOnLifecycle(Lifecycle.State.STARTED) { while (true) { delay(30_000); app.checkSilence(); render() } }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra(EXTRA_TAG)?.let { open(it) }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("sel", selected)
        outState.putBoolean("reps", showReps)
        outState.putStringArray("sels", sels.map { (k, v) -> "$k|${v.day}|${v.index ?: ""}" }.toTypedArray())
        outState.putStringArray("order", order.toTypedArray())
    }

    override fun onResume() {
        super.onResume()
        allMap?.onResume(); trailMap?.onResume()
        render()
        offerWelcome()
    }

    override fun onPause() {
        allMap?.onPause(); trailMap?.onPause()
        super.onPause()
    }

    private fun computeLayout(fold: FoldingFeature?): Layout {
        val wm = WindowMetricsCalculator.getOrCreate().computeCurrentWindowMetrics(this)
        val wDp = wm.bounds.width() / resources.displayMetrics.density
        if (fold != null && fold.state == FoldingFeature.State.HALF_OPENED && fold.orientation == FoldingFeature.Orientation.HORIZONTAL) return Layout.TABLETOP
        return when {
            wDp >= 1000 -> Layout.EXPANDED
            wDp >= 600 -> Layout.MEDIUM
            else -> Layout.COMPACT
        }
    }

    private fun tags(): List<Tracker> = app.store.trackers.value

    /** list order: alarms first, then by age */
    private fun sorted(): List<Tracker> = Status.sort(tags())

    /** pager order: stable while the detail is open */
    private fun pagerTags(): List<Tracker> {
        val all = tags()
        val byId = all.associateBy { it.id }
        val keep = order.mapNotNull { byId[it] }
        val missing = Status.sort(all.filter { it.id !in order })
        val out = keep + missing
        order = out.map { it.id }
        return out
    }

    // ------------------------------------------------------------------ building

    private fun teardown() {
        allMap?.onDetach(); trailMap?.onDetach()
        allMap = null; allOverlay = null; trailMap = null; trailOverlay = null; repsChip = null
        homeBar = null; list = null; fab = null; head = null; pager = null; bodies = null; homeSheet = null
        root.removeAllViews()
        trailFitKey = null; trailFocusN = null; trailFitAll = false
        allFitted = false
    }

    private fun build() {
        teardown()
        if (selected != null && app.store.get(selected!!) == null) selected = null
        val sel = selected
        when (layout) {
            Layout.COMPACT -> if (sel == null) buildHomeCompact() else root.addFill(detailColumn(back = true, withMap = true))
            Layout.MEDIUM -> {
                val row = hbox { gravity = Gravity.NO_GRAVITY }
                row.addView(listPane(), LinearLayout.LayoutParams(paneW(360, 0.45f), MATCH))
                row.addView(vline())
                row.addView(if (sel != null) detailColumn(back = false, withMap = true) else allMapView(), LinearLayout.LayoutParams(0, MATCH, 1f))
                root.addFill(row)
            }
            Layout.EXPANDED -> {
                val row = hbox { gravity = Gravity.NO_GRAVITY }
                row.addView(listPane(), LinearLayout.LayoutParams(paneW(360, 0.3f), MATCH))
                row.addView(vline())
                row.addView(if (sel != null) trailMapView(allTagsButton = true) else allMapView(), LinearLayout.LayoutParams(0, MATCH, 1f))
                row.addView(vline())
                row.addView(if (sel != null) detailColumn(back = false, withMap = false) else hintPane(), LinearLayout.LayoutParams(paneW(400, 0.36f), MATCH))
                root.addFill(row)
            }
            Layout.TABLETOP -> {
                val col = vbox()
                val topH = (hingeTop - root.paddingTop).takeIf { it > dp(200) } ?: (resources.displayMetrics.heightPixels / 2)
                val top = vbox()
                if (sel != null) {
                    top.addView(makeHead(back = false), lp())
                    top.addView(trailMapView(allTagsButton = false), LinearLayout.LayoutParams(MATCH, 0, 1f))
                } else {
                    top.addView(makeHomeBar(), lp())
                    top.addView(allMapView(), LinearLayout.LayoutParams(MATCH, 0, 1f))
                }
                col.addView(top, LinearLayout.LayoutParams(MATCH, topH))
                col.addView(View(this).apply {
                    background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(col(R.color.surf3), col(R.color.line), col(R.color.surf3)))
                }, LinearLayout.LayoutParams(MATCH, dp(12)))
                val bottom = FrameLayout(this)
                if (sel != null) bottom.addFill(makePager()) else {
                    bottom.addFill(makeList())
                    bottom.addView(makeFab(), fabLp())
                }
                col.addView(bottom, LinearLayout.LayoutParams(MATCH, 0, 1f))
                root.addFill(col)
            }
        }
        back.isEnabled = selected != null
        render()
    }

    /** a side pane: [maxDp] wide, at most [frac] of the window */
    private fun paneW(maxDp: Int, frac: Float): Int =
        minOf(dp(maxDp), (WindowMetricsCalculator.getOrCreate().computeCurrentWindowMetrics(this).bounds.width() * frac).toInt())

    private fun vline() = View(this).apply { setBackgroundColor(col(R.color.line)); layoutParams = LinearLayout.LayoutParams(maxOf(1, dp(1)), MATCH) }

    private fun fabLp() = flp(WRAP, WRAP, Gravity.BOTTOM or Gravity.END).apply { setMargins(dp(16), dp(16), dp(16), dp(16)) }

    private fun makeHomeBar(): HomeBar = HomeBar(this, { startActivity(Intent(this, ConnectionsActivity::class.java)) }) { appMenu(it) }.also { homeBar = it }

    private fun makeList(): TagListView = TagListView(this, { open(it) }) { banner ->
        when (banner) {
            "repeaters" -> openRepeaterMap()
            "scan-share" -> ShareImport.scan(this)
            "restore-backup" -> startActivity(Intent(this, BackupActivity::class.java))
        }
    }.also { list = it }

    private fun makeFab(): ExtendedFloatingActionButton = ExtendedFloatingActionButton(this).apply {
        text = getString(R.string.add_tag)
        setIconResource(R.drawable.ic_plus)
        setOnClickListener { startActivity(Intent(this@MainActivity, AddTagActivity::class.java)) }
    }.also { fab = it }

    private fun listPane(): View {
        val pane = FrameLayout(this)
        val col = vbox()
        col.addView(makeHomeBar(), lp())
        col.addView(makeList().apply { listOrderNote = true }, LinearLayout.LayoutParams(MATCH, 0, 1f))
        pane.addFill(col)
        pane.addView(makeFab(), fabLp())
        return pane
    }

    private fun hintPane(): View {
        val v = vbox {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(32), dp(32), dp(32), dp(32))
        }
        v.tag = "hint"
        return v
    }

    private fun renderHint(v: LinearLayout) {
        v.removeAllViews()
        val all = tags()
        val st = all.associate { it.id to Status.of(it) }
        val need = all.filter { st[it.id]!!.needsLook }
        val count = resources.getQuantityString(R.plurals.home_tags, all.size, all.size)
        val sum = if (need.isNotEmpty()) getString(R.string.home_count_need, count, resources.getQuantityString(R.plurals.home_need_look, need.size, need.size)) else count
        v.addView(text(R.style.TextAppearance_LibreTag_TitleLarge, sum).apply { textSize = 20f })
        v.addView(text(R.style.TextAppearance_LibreTag_BodyLarge, getString(R.string.hint_pick), R.color.ink2).apply { maxWidth = dp(320) }, lp(WRAP, WRAP).margins(t = dp(12)))
        for (t in need) v.addView(button(t.name, Btn.TONAL) { open(t.id) }.apply {
            icon = BlazeDrawable(this@MainActivity, t.markColor, t.markSymbol); iconTint = null; iconSize = dp(18)
        }, lp(WRAP, WRAP).margins(t = dp(12)))
    }

    private fun buildHomeCompact() {
        val col = vbox()
        col.addView(makeHomeBar(), lp())
        val sheet = HomeSheet(this, allMapView(home = true), makeList()) { c -> app.prefs.mapCollapsed = c; updateHomeControls() }
        homeSheet = sheet
        col.addView(sheet, LinearLayout.LayoutParams(MATCH, 0, 1f))
        root.addFill(col)
        root.addView(makeFab(), fabLp())
        sheet.setCollapsed(app.prefs.mapCollapsed, animate = false)
    }

    private fun updateHomeControls() {
        val c = app.prefs.mapCollapsed
        list?.let { l ->
            l.header.findViewWithTag<View>("showmap")?.let { l.header.removeView(it) }
            if (c) l.header.addView(button(getString(R.string.show_map), Btn.TEXT, R.drawable.ic_down) { homeSheet?.setCollapsed(false, true) }.apply { tag = "showmap" })
        }
    }

    private fun allMapView(home: Boolean = false): TagMap {
        val m = TagMap(this)
        val o = AllTagsOverlay(this) { open(it) }
        m.setOverlays(o)
        m.contentDescription = getString(R.string.a11y_map_all)
        m.bottomEnd.addView(button(getString(R.string.fit_all), Btn.ON_MAP, R.drawable.ic_fit) { fitAll(true) }, lp(WRAP, WRAP).margins(l = dp(8)))
        if (home) {
            m.bottomEnd.addView(button(getString(R.string.hide_map), Btn.ON_MAP, R.drawable.ic_up) { homeSheet?.setCollapsed(true, true) }, lp(WRAP, WRAP).margins(l = dp(8)))
            m.bottomInset = dp(20)
        }
        allMap = m; allOverlay = o
        return m
    }

    private fun trailMapView(allTagsButton: Boolean): TagMap {
        val m = TagMap(this)
        val o = TrailOverlay(this)
        m.setOverlays(o)
        repsChip = Chip(this).apply {
            text = getString(R.string.repeaters)
            isCheckable = true
            isChecked = showReps
            chipBackgroundColor = android.content.res.ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(col(R.color.primary), col(R.color.chip)))
            setTextColor(android.content.res.ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(col(R.color.on_primary), col(R.color.ink))))
            chipStrokeWidth = 0f
            elevation = dpf(2f)
            chipIcon = tinted(R.drawable.ic_rpt, col(R.color.ink), 18)
            isChipIconVisible = !showReps
            checkedIconTint = android.content.res.ColorStateList.valueOf(col(R.color.on_primary))
            setOnCheckedChangeListener { _, c -> showReps = c; isChipIconVisible = !c; o.bubble = -1; updateTrail() }
        }
        m.topStart.addView(repsChip)
        m.bottomEnd.addView(button(getString(R.string.fit), Btn.ON_MAP, R.drawable.ic_fit) { trailFitAll = true; updateTrail() }, lp(WRAP, WRAP).margins(l = dp(8)))
        if (allTagsButton) m.bottomEnd.addView(button(getString(R.string.all_tags), Btn.ON_MAP, R.drawable.ic_map) { closeDetail() }, lp(WRAP, WRAP).margins(l = dp(8)))
        trailMap = m; trailOverlay = o
        return m
    }

    private fun makeHead(back: Boolean): DetailHead = DetailHead(this, back, { closeDetail() }, { step(it) }) { tagMenu(it) }.also { head = it }

    private fun makePager(): ViewPager2 {
        val p = ViewPager2(this)
        val a = BodyAdapter(this) {}
        bodies = a
        a.tags = pagerTags()
        p.adapter = a
        p.offscreenPageLimit = 1
        (p.getChildAt(0) as? RecyclerView)?.itemAnimator = null
        val idx = a.tags.indexOfFirst { it.id == selected }.coerceAtLeast(0)
        p.setCurrentItem(idx, false)
        p.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                val t = bodies?.tags?.getOrNull(position) ?: return
                if (t.id != selected) {
                    selected = t.id
                    showReps = false; repsChip?.isChecked = false
                    SupportAsk.onAction(this@MainActivity)
                    updateDetailChrome()
                    list?.let { renderList() }
                }
            }
        })
        pager = p
        return p
    }

    private fun detailColumn(back: Boolean, withMap: Boolean): View {
        val col = vbox()
        col.addView(makeHead(back), lp())
        col.addView(View(this).apply { setBackgroundColor(col(R.color.line)) }, LinearLayout.LayoutParams(MATCH, maxOf(1, dp(1))))
        if (withMap) col.addView(trailMapView(allTagsButton = false), LinearLayout.LayoutParams(MATCH, 0, 42f))
        if (withMap) col.addView(View(this).apply { setBackgroundColor(col(R.color.line)) }, LinearLayout.LayoutParams(MATCH, maxOf(1, dp(1))))
        col.addView(makePager(), LinearLayout.LayoutParams(MATCH, 0, if (withMap) 58f else 1f))
        return col
    }

    // ------------------------------------------------------------------ rendering

    private fun render() {
        if (selected != null && app.store.get(selected!!) == null) {
            selected = null
            build()
            return
        }
        homeBar?.bind(app.reach.value)
        app.detailOpenId = selected
        renderList()
        renderAllMap()
        root.findViewWithTag<LinearLayout>("hint")?.let { renderHint(it) }
        bodies?.let { a ->
            val pt = pagerTags()
            if (pt.map { it.id } != a.tags.map { it.id }) {
                a.tags = pt
                a.notifyDataSetChanged()
                pager?.setCurrentItem(pt.indexOfFirst { it.id == selected }.coerceAtLeast(0), false)
            } else {
                a.tags = pt
                pager?.let { p -> pt.forEach { t -> a.bodyFor(p, t.id)?.bind(t) } }
            }
        }
        updateDetailChrome()
    }

    private fun renderList() {
        list?.render(sorted(), if (layout == Layout.COMPACT) null else selected, app.reach.value)
        // 0.12: a bootloader on the cable that fits no tag (tag-diagnosis 7.4)
        list?.let { l ->
            l.extra.removeAllViews()
            if (app.cableUnattributed()) l.extra.addView(bootStrip())
        }
        if (layout == Layout.COMPACT) updateHomeControls()
    }

    private fun updateDetailChrome() {
        val id = selected ?: return
        val t = app.store.get(id) ?: return
        val pt = bodies?.tags ?: pagerTags()
        head?.bind(t, pt.indexOfFirst { it.id == id }.coerceAtLeast(0), pt.size)
        updateTrail()
    }

    private fun lastPos(t: Tracker): Position? = t.positions.lastOrNull { it.hasFix || it.est?.state == "ok" || t.wifiFixes[it.n] != null || it.lastFixTime > 0 }

    private fun renderAllMap() {
        val o = allOverlay ?: return
        val m = allMap ?: return
        val now = System.currentTimeMillis()
        val mt = tags().mapNotNull { t ->
            val p = lastPos(t) ?: return@mapNotNull null
            val s = Status.of(t, now)
            val wifi = t.wifiFixes[p.n]
            val (lat, lon, area) = when {
                p.hasFix -> Triple(p.lat, p.lon, null)
                wifi != null -> Triple(wifi.lat, wifi.lon, wifi.r68)
                p.est?.state == "ok" -> Triple(p.est.lat, p.est.lon, p.est.r68)
                else -> Triple(p.lastLat, p.lastLon, null)
            }
            val heard = t.last?.receivedAt ?: p.receivedAt
            val age = now - heard
            // when this position was taken: the fix, the report with the area, or the last fix
            val posAt = when {
                p.hasFix -> if (p.fixTime > 0) p.fixTime * 1000 else p.receivedAt
                wifi != null || p.est?.state == "ok" -> p.receivedAt
                else -> p.lastFixTime * 1000
            }
            val alpha = when { age < 3_600_000L -> 1f; age < 3 * 3_600_000L -> 0.8f; age < 8 * 3_600_000L -> 0.62f; else -> 0.5f }
            val ring = when (s.level) { TagStatus.Level.CRIT -> col(R.color.crit); TagStatus.Level.WARN -> col(R.color.warn); else -> null }
            // 0.12: where the drawn position would mislead, a short suffix (tag-diagnosis 7.3)
            val dg = app.diag.value[t.id]?.takeIf { Diagnosis.shown(it, s) }
            val suffix = when (dg?.cause) {
                Diagnosis.Cause.WITH_YOU -> getString(R.string.dg_pill_with_you)
                Diagnosis.Cause.ON_CABLE, Diagnosis.Cause.BOOTLOADER, Diagnosis.Cause.ESP_DOWNLOAD -> getString(R.string.dg_pill_cable)
                else -> null
            }
            val ringD = if (Diagnosis.band(dg, s)) ring else null
            MapTag(t.id, if (suffix != null) "${t.name} · $suffix" else t.name, t.markColor, t.markSymbol, lat, lon, area,
                if (area != null) getString(R.string.map_area_at, Format.distance(this, area), Format.at(this, posAt, now)) else Format.at(this, posAt, now),
                alpha, ringD, if (s.needsLook) 2 else 1)
        }
        o.tags = mt
        o.zones = tags().flatMap { t -> t.zones.map { MapZone(it.name, it.lat, it.lon, it.radiusM.toDouble()) } }
            .distinctBy { "${it.name}${it.lat}${it.lon}" }
        m.map.invalidate()
        if (!allFitted && mt.isNotEmpty()) { allFitted = true; fitAll(false) }
    }

    private fun fitAll(animate: Boolean) {
        val o = allOverlay ?: return
        val pts = o.tags.map { Triple(it.lat, it.lon, it.areaM ?: 150.0) }
        allMap?.fit(pts, 15.0)
    }

    private fun updateTrail() {
        val o = trailOverlay ?: return
        val m = trailMap ?: return
        val id = selected ?: return
        val t = app.store.get(id) ?: return
        val body = pager?.let { bodies?.bodyFor(it, id) }
        val sel = selOf(id)
        val days = Timeline.days(t)
        val dayReps = days.getOrNull(sel.day.coerceIn(0, maxOf(0, days.size - 1)))?.reports ?: emptyList()
        val p = body?.selectedReport() ?: dayReps.getOrNull(sel.index ?: (dayReps.size - 1)) ?: t.last
        fun point(x: Position): TrackPoint? {
            val w = t.wifiFixes[x.n]
            return when {
                x.hasFix -> TrackPoint(x.n, x.lat, x.lon)
                w != null -> TrackPoint(x.n, w.lat, w.lon, w.r68, w.r95)
                x.est?.state == "ok" -> TrackPoint(x.n, x.est.lat, x.est.lon, x.est.r68, x.est.r95)
                else -> null
            }
        }
        o.color = t.markColor; o.symbol = t.markSymbol; o.name = t.name
        val now = System.currentTimeMillis()
        val pts = dayReps.mapNotNull { x -> point(x)?.copy(at = fixAt(x)) }
        o.points = pts
        o.timeOf = { ms -> Format.time(this, ms) }
        o.bubbleOf = { tp -> Triple(Format.at(this, tp.at, now), Format.agoLong(this, tp.at, now), getString(R.string.map_point_n, tp.n.toInt())) }
        val sp = p?.let { point(it)?.copy(at = fixAt(it)) }
        o.selected = sp
        val latest = p != null && p.n == t.last?.n
        // the tag's label: its name and the clock time of the report shown (not the report number)
        o.selectedLabel = t.name
        o.selectedAge = p?.let { Format.at(this, fixAt(it), now) }
        val s = Status.of(t)
        o.selectedRing = if (latest) when (s.level) { TagStatus.Level.CRIT -> col(R.color.crit); TagStatus.Level.WARN -> col(R.color.warn); else -> null } else null
        o.selectedAlpha = 1f
        o.lastGps = if (p != null && !p.hasFix) {
            when {
                p.lastFixTime > 0 -> GeoPoint(p.lastLat, p.lastLon) to Format.at(this, p.lastFixTime * 1000, now)
                else -> t.positions.lastOrNull { it.hasFix && it.n < p.n }?.let { GeoPoint(it.lat, it.lon) to Format.at(this, fixAt(it), now) }
            }
        } else null
        o.zones = t.zones.map { MapZone(it.name, it.lat, it.lon, it.radiusM.toDouble()) }
        o.repeaters = if (showReps) (p?.est?.anchors ?: emptyList()).map { MapRepeater(it.name, it.lat, it.lon, it.snrDb) } else emptyList()
        m.map.invalidate()
        val focus = focusOf(sp, p, o.lastGps?.first)
        val key = "$id/${sel.day}"
        when {
            trailFitAll -> {
                // "Fit": the whole day's trail
                trailFitAll = false
                trailFitKey = key; trailFocusN = sp?.n
                val all = pts.map { Triple(it.lat, it.lon, if (it.n == sp?.n) (it.areaM95 ?: 120.0) else (it.areaM68 ?: 120.0)) } +
                    listOfNotNull(o.lastGps?.let { Triple(it.first.latitude, it.first.longitude, 120.0) })
                if (all.isNotEmpty()) m.fit(all, 16.0, animate = true)
            }
            trailFitKey != key -> {
                // a tag was opened (or another day picked): go to where it is
                trailFitKey = key; trailFocusN = sp?.n
                if (focus.isNotEmpty()) m.fit(focus, FOCUS_ZOOM, animate = true)
            }
            trailFocusN != sp?.n -> {
                // another report picked on the timeline: follow it only when it is off screen
                trailFocusN = sp?.n
                if (focus.isNotEmpty() && !m.shows(focus)) m.fit(focus, FOCUS_ZOOM, animate = true)
            }
        }
    }

    /** When a position was taken: the fix time when the report carries one, else when it came. */
    private fun fixAt(x: Position): Long = if (x.hasFix && x.fixTime > 0) x.fixTime * 1000 else x.receivedAt

    /**
     * Where the tag is, as circles to fit: a GPS fix at street level (or its accuracy circle when
     * that is larger), an estimate's 95 % circle (68 % without one), plus the last GPS fix when
     * the map shows it too.
     */
    private fun focusOf(sp: TrackPoint?, p: Position?, lastGps: GeoPoint?): List<Triple<Double, Double, Double>> {
        val out = mutableListOf<Triple<Double, Double, Double>>()
        if (sp != null) {
            val r = when {
                sp.areaM68 != null -> sp.areaM95 ?: sp.areaM68
                else -> maxOf(STREET_M, (p?.haccM ?: 0).toDouble())
            }
            out += Triple(sp.lat, sp.lon, r)
        }
        lastGps?.let { out += Triple(it.latitude, it.longitude, STREET_M) }
        return out
    }

    // ------------------------------------------------------------------ navigation

    fun open(id: String) {
        if (app.store.get(id) == null) return
        val was = selected
        selected = id
        SupportAsk.onAction(this)
        if (was == null || layout == Layout.COMPACT || pager == null) {
            order = sorted().map { it.id }
            build()
        } else {
            val idx = bodies?.tags?.indexOfFirst { it.id == id } ?: -1
            if (idx >= 0) pager?.setCurrentItem(idx, true) else build()
        }
    }

    private fun closeDetail() {
        selected = null
        build()
    }

    private fun step(d: Int) {
        val p = pager ?: return
        val n = p.currentItem + d
        if (n in 0 until (bodies?.itemCount ?: 0)) p.setCurrentItem(n, true)
    }

    // ------------------------------------------------------------------ DetailHost

    override fun selOf(id: String): Sel = sels[id] ?: Sel()

    override fun select(id: String, sel: Sel) {
        val prev = selOf(id)
        sels[id] = sel
        pager?.let { p -> bodies?.bodyFor(p, id)?.let { b -> if (prev.day != sel.day) app.store.get(id)?.let { b.bind(it) } else b.bindSelection() } }
        if (id == selected) updateTrail()
    }

    override fun command(t: Tracker, kind: CommandKind) {
        if (!t.canCommand) return
        when (kind) {
            CommandKind.LOST -> lostDialog(t)
            CommandKind.TRACK -> trackDialog(t)
            else -> send(t, kind)
        }
    }

    /**
     * Track: how often and how long (every 5 min for 1 h preselected, one more tap sends it). Says
     * how many reports a choice means, and warns when it would reach the tag's daily limit
     * (data/TrackPlan).
     */
    private fun trackDialog(t: Tracker) {
        var iv = Status.TRACK_INTERVAL_S
        var dur = TrackPlan.DEFAULT_MIN
        val summary = text(R.style.TextAppearance_LibreTag_BodyMedium, null, R.color.ink2)
        val warn = text(R.style.TextAppearance_LibreTag_BodySmall, null, R.color.warn)
        var ok: android.widget.Button? = null
        fun update() {
            val n = TrackPlan.reports(iv, dur)
            summary.text = resources.getQuantityString(R.plurals.track_reports, n, n,
                Format.clock(this, System.currentTimeMillis() + dur * 60_000L))
            val cap = TrackPlan.capAfterMin(iv, dur)
            warn.visibility = if (cap != null) View.VISIBLE else View.GONE
            cap?.let { warn.text = getString(R.string.track_cap_warn, Format.duration(this, it * 60_000L)) }
            ok?.text = getString(R.string.track_choose_ok_for, Format.duration(this, dur * 60_000L))
        }
        fun chips(values: List<Int>, sel: Int, label: (Int) -> String, pick: (Int) -> Unit) =
            com.google.android.material.chip.ChipGroup(this).apply {
                isSingleSelection = true; isSelectionRequired = true
                values.forEach { v ->
                    addView(com.google.android.material.chip.Chip(this@MainActivity).apply {
                        text = label(v); isCheckable = true; isChecked = v == sel
                        setOnCheckedChangeListener { _, on -> if (on) { pick(v); update() } }
                    })
                }
            }
        val body = vbox {
            setPadding(dp(24), dp(8), dp(24), 0)
            addView(text(R.style.TextAppearance_LibreTag_LabelLarge, getString(R.string.track_how_often)))
            addView(chips(TrackPlan.INTERVALS_S, iv, { Format.every(this@MainActivity, it) }) { iv = it }, lp().margins(t = dp(4)))
            addView(text(R.style.TextAppearance_LibreTag_LabelLarge, getString(R.string.track_how_long)), lp().margins(t = dp(12)))
            addView(chips(TrackPlan.DURATIONS_MIN, dur, { Format.duration(this@MainActivity, it * 60_000L) }) { dur = it }, lp().margins(t = dp(4)))
            addView(summary, lp().margins(t = dp(12)))
            addView(warn, lp().margins(t = dp(4)))
        }
        val d = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.track_choose_title2)
            .setView(android.widget.ScrollView(this).apply { addView(body) })
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.track_choose_ok) { _, _ -> send(t, CommandKind.TRACK, iv, dur) }
            .show()
        ok = d.getButton(android.content.DialogInterface.BUTTON_POSITIVE)
        update()
    }

    private fun send(t: Tracker, kind: CommandKind, arg: Int = 0, durationMin: Int = 0) {
        app.queueCommand(t.id, kind, arg, durationMin = durationMin)
        SupportAsk.onAction(this)
        val cur = app.store.get(t.id) ?: t
        // no snackbar: the slip under the commands says it, in place of the reach line
        BleCommandBeacon.offerPermission(this, root, cur, null)
        render()
    }

    private fun lostDialog(t: Tracker) {
        val s = Status.of(t)
        val d = MaterialAlertDialogBuilder(this)
            .setIcon(tinted(R.drawable.ic_lost, col(R.color.crit), 24))
            .setTitle(getString(R.string.lost_dialog_title, t.name))
            .setMessage(getString(R.string.lost_dialog_text) + "\n\n" + TagText.reachLine(this, t, s).let { if (it.endsWith(".")) it else "$it." })
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.lost_dialog_ok) { _, _ -> send(t, CommandKind.LOST) }
            .show()
        d.getButton(android.content.DialogInterface.BUTTON_POSITIVE)?.setTextColor(col(R.color.crit))
    }

    override fun openInMap(t: Tracker, p: Position) = TagSheets.openWith(this, t, p)

    override fun openRepeaterMap() { startActivity(Intent(this, RepeaterMapActivity::class.java)) }

    override fun guard(t: Tracker, on: Boolean) { app.setGuard(t.id, on) }

    override fun rearmGuard(t: Tracker) { app.rearmGuard(t.id) }

    override fun cancelCommand(t: Tracker) { app.cancelCommand(t.id) }

    override fun supportStrip(t: Tracker, s: TagStatus): View? {
        val lc = t.lastCommand ?: return null
        if (t.pending != null || System.currentTimeMillis() - lc.confirmedAt > TagText.SLIP_KEEP_MS) return null
        val busy = tags().any { Status.of(it).blocksSupport }
        val key = "cmd:${t.id}:${lc.seq}:${lc.confirmedAt}"
        if (!SupportAsk.consider(this, key, busy)) return null
        return SupportAsk.strip(this, SupportCheckout.REF_COMMAND_CONFIRMED) { render() }
    }

    override fun batteryEstimate(t: Tracker): String = batteryText(this, t)

    override fun wifiLookup(t: Tracker, p: Position) = WifiPositioning.tap(this, t, p)

    private fun bootStrip(): View = vbox {
        background = rounded(col(R.color.plate_bg), 4f, col(R.color.plate))
        setPadding(dp(14), dp(12), dp(12), dp(8))
        addView(text(R.style.TextAppearance_LibreTag_BodyMedium, getString(R.string.dg_strip_bootloader)).apply {
            compoundDrawablePadding = dp(8)
            setCompoundDrawablesRelative(tinted(R.drawable.ic_info, col(R.color.ink2), 18), null, null, null)
        })
        addView(FlowRow(this@MainActivity).apply {
            addView(button(getString(R.string.dg_act_finish_update), Btn.TONAL) { startActivity(Intent(this@MainActivity, FirmwareUpdateActivity::class.java)) }.small())
            addView(button(getString(R.string.dg_act_how_to_restart), Btn.TEXT) { howToRestart() }.small())
        }, lp().margins(t = dp(6)))
        layoutParams = lp().margins(b = dp(8))
    }

    private fun howToRestart() {
        MaterialAlertDialogBuilder(this).setTitle(R.string.dg_how_to_restart_title)
            .setMessage(R.string.dg_how_to_restart_text).setPositiveButton(R.string.ok, null).show()
    }

    /** A console command for this tag on the cable or over Bluetooth; false when no console is open for it. */
    private fun consoleCommand(t: Tracker, cmd: String, done: (List<String>) -> Unit = {}): Boolean {
        if (app.usb.connected?.tagId != t.id) { root.snack(getString(R.string.dg_no_console)); return false }
        lifecycleScope.launch { app.usb.command(cmd, maxMs = 5000)?.let(done) }
        return true
    }

    override fun diagAction(t: Tracker, a: Diagnosis.Action) {
        when (a) {
            A.FINISH_UPDATE, A.UPDATE_FIRMWARE -> startActivity(Intent(this, FirmwareUpdateActivity::class.java).putExtra(EXTRA_TAG, t.id))
            A.HOW_TO_RESTART -> howToRestart()
            A.RESTART_ESP -> lifecycleScope.launch {
                if (app.usb.espRestart()) {
                    root.snack(getString(R.string.dg_restart_sent))
                    // the Service screen connects to the tag on the cable again
                    startActivity(Intent(this@MainActivity, ServiceActivity::class.java).putExtra(EXTRA_TAG, t.id))
                } else root.snack(getString(R.string.dg_no_console))
            }
            A.RESTART_CONSOLE -> if (consoleCommand(t, "reboot")) root.snack(getString(R.string.dg_restart_sent))
            A.READ_LOG -> consoleCommand(t, "log 20") { lines ->
                MaterialAlertDialogBuilder(this).setTitle(R.string.dg_act_read_log)
                    .setMessage(lines.filter { it.startsWith("L ") }.joinToString("\n").ifEmpty { lines.joinToString("\n") })
                    .setPositiveButton(R.string.ok, null).show()
            }
            A.OPEN_BLE -> if (app.ble.current(t.id) != null) bleh.connect(t) else bleh.openRemote(t)
            A.CHECK_HERE -> { bleh.openRemote(t); app.checkHere(t) }
            A.SEND_REPORT -> if (consoleCommand(t, "report")) root.snack(getString(R.string.dg_report_sent))
            A.CONNECTIONS -> startActivity(Intent(this, ConnectionsActivity::class.java))
            A.TRY_AGAIN -> app.retryRelays()
            A.ADD_RELAY -> app.receipts.value[t.id]?.relay?.let { url ->
                MaterialAlertDialogBuilder(this).setTitle(R.string.dg_add_relay_title)
                    .setMessage(getString(R.string.dg_add_relay_text, url))
                    .setNegativeButton(R.string.cancel, null)
                    .setPositiveButton(R.string.dg_add_relay_ok) { _, _ -> app.addRelay(url); root.snack(getString(R.string.dg_relay_added)) }
                    .show()
            }
            A.USE_TAG_SCHEDULE -> t.health?.let { h ->
                app.store.updateTracker(t.id) { cur ->
                    cur.copy(intervalMin = h.intervalMin ?: cur.intervalMin, reportEvery = h.reportEvery ?: cur.reportEvery,
                        settingsReadAt = System.currentTimeMillis())
                }
                root.snack(getString(R.string.dg_schedule_adopted))
            }
            A.SEND_MINE -> {
                app.queueCommand(t.id, CommandKind.INTERVAL, t.intervalMin, t.checkS, if (TagText.fw073(t)) t.reportEvery else 0)
                render()
            }
            A.CATCH_UP -> t.health?.n?.let { n ->
                app.store.updateTracker(t.id) { it.copy(lastN = n - 1) }
                app.forgetWindow(t.id)
                lifecycleScope.launch { app.quickSync() }
                root.snack(getString(R.string.dg_caught_up))
            }
            A.DATE_SETTINGS -> runCatching { startActivity(Intent(android.provider.Settings.ACTION_DATE_SETTINGS)) }
            A.OPEN_MAP -> (t.lastFix ?: t.last)?.let { openInMap(t, it) }
            A.SERVICE -> startActivity(Intent(this, ServiceActivity::class.java).putExtra(EXTRA_TAG, t.id))
        }
    }

    // ------------------------------------------------------------------ menus

    private fun appMenu(anchor: View) {
        iconMenu(anchor, listOf(
            Triple(R.string.menu_settings, R.drawable.ic_prefs, false),
            Triple(R.string.menu_repeaters, R.drawable.ic_rpt, false),
            Triple(R.string.menu_nostr_key, R.drawable.ic_key, false),
            Triple(R.string.menu_connections, R.drawable.ic_mesh, false),
            Triple(R.string.menu_support, R.drawable.ic_heart, false),
            Triple(R.string.menu_get_tag, R.drawable.ic_chip, false),
            Triple(R.string.menu_about, R.drawable.ic_info, false),
        )) { id ->
            when (id) {
                R.string.menu_settings -> startActivity(Intent(this, SettingsActivity::class.java))
                R.string.menu_repeaters -> openRepeaterMap()
                R.string.menu_nostr_key -> startActivity(Intent(this, NostrKeyActivity::class.java))
                R.string.menu_connections -> startActivity(Intent(this, ConnectionsActivity::class.java))
                R.string.menu_support -> SupportAsk.openFromMenu(this)
                R.string.menu_get_tag -> TagSheets.getTag(this)
                R.string.menu_about -> startActivity(Intent(this, AboutActivity::class.java))
            }
        }
    }

    private fun tagMenu(anchor: View) {
        val t = selected?.let { app.store.get(it) } ?: return
        val items = if (t.isShared) listOf(
            Triple(R.string.menu_tag_settings, R.drawable.ic_prefs, false),
            Triple(R.string.menu_export, R.drawable.ic_download, false),
            Triple(R.string.menu_diag, R.drawable.ic_diag, false),
            Triple(R.string.menu_stop_following, R.drawable.ic_trash, true),
        ) else listOf(
            // reporting, places, known devices, alerts, edit and Nostr live in Tag settings
            Triple(R.string.menu_tag_settings, R.drawable.ic_prefs, false),
            Triple(R.string.menu_share, R.drawable.ic_share, false),
            Triple(R.string.menu_service, R.drawable.ic_chip, false),
            Triple(R.string.menu_export, R.drawable.ic_download, false),
            Triple(R.string.menu_diag, R.drawable.ic_diag, false),
            Triple(R.string.menu_remove, R.drawable.ic_trash, true),
        )
        iconMenu(anchor, items) { id ->
            when (id) {
                R.string.menu_tag_settings -> startActivity(Intent(this, TagSettingsActivity::class.java).putExtra(EXTRA_TAG, t.id))
                R.string.menu_share -> TagSheets.share(this, t)
                R.string.menu_service -> startActivity(Intent(this, ServiceActivity::class.java).putExtra(EXTRA_TAG, t.id))
                R.string.menu_diag -> TagSheets.diagnostics(this, t)
                R.string.menu_export -> TagSheets.export(this, t)
                R.string.menu_remove, R.string.menu_stop_following -> removeDialog(t)
            }
        }
    }

    private fun removeDialog(t: Tracker) {
        val d = MaterialAlertDialogBuilder(this)
            .setTitle(if (t.isShared) getString(R.string.remove_shared_title, t.name) else getString(R.string.remove_title, t.name))
            .setMessage(if (t.isShared) getString(R.string.remove_shared_text, t.sharedBy) else getString(R.string.remove_text))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(if (t.isShared) R.string.menu_stop_following else R.string.menu_remove) { _, _ ->
                app.removeTag(t.id)
                selected = null
                build()
                root.snack(getString(R.string.removed_snack, t.name), getString(R.string.undo)) {
                    if (app.undoRemove()) render()
                }
            }
            .show()
        d.getButton(android.content.DialogInterface.BUTTON_POSITIVE)?.setTextColor(col(R.color.crit))
    }

    /** "Send welcome message again": the result per relay (OK count, or why each relay refused). */
    private fun sendWelcome(t: Tracker) {
        if (app.store.ownerNpub == null) { root.snack(getString(R.string.welcome_no_owner), long = true); return }
        root.snack(getString(R.string.welcome_sending))
        lifecycleScope.launch {
            val r = app.sendWelcome(t) ?: return@launch
            if (r.ok > 0) root.snack(resources.getQuantityString(R.plurals.welcome_sent, r.ok, r.ok), long = true)
            else MaterialAlertDialogBuilder(this@MainActivity)
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

    /** Once per tag: tags paired before the owner npub was set (or whose welcome no relay took). */
    private var welcomeAsked = false
    private fun offerWelcome() {
        if (welcomeAsked || battAskedNow) return
        val ownerSet = app.store.ownerNpub != null
        val t = app.store.trackers.value.firstOrNull { Welcome.needsOffer(it, ownerSet, System.currentTimeMillis()) } ?: return
        welcomeAsked = true
        app.store.updateTracker(t.id) { it.copy(welcomeOffered = true) }
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.welcome_offer_title, t.name))
            .setMessage(R.string.welcome_offer_text)
            .setPositiveButton(R.string.welcome_offer_send) { _, _ -> sendWelcome(t) }
            .setNegativeButton(R.string.not_now, null)
            .show()
    }

    /** Once: without the exemption Android suspends the background connection and reports are missed. */
    private var battAskedNow = false
    private fun askBatteryExemption() {
        val pm = getSystemService(android.os.PowerManager::class.java)
        if (pm.isIgnoringBatteryOptimizations(packageName) || app.prefs.askedBattery) return
        if (tags().isEmpty()) return
        app.prefs.askedBattery = true
        battAskedNow = true
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.battery_exempt_title)
            .setMessage(R.string.battery_exempt_text)
            .setPositiveButton(R.string.battery_exempt_ok) { _, _ ->
                runCatching {
                    @SuppressLint("BatteryLife")
                    val i = Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, android.net.Uri.parse("package:$packageName"))
                    startActivity(i)
                }
            }
            .setNegativeButton(R.string.not_now, null)
            .show()
    }

    companion object {
        const val EXTRA_TAG = "tag_id"
        /** a GPS fix opens at street level */
        const val FOCUS_ZOOM = 17.0
        /** radius kept around a GPS fix when focusing on it (about zoom 16.5–17 on a phone) */
        const val STREET_M = 90.0

        /** "~5 weeks at this pace", or why there's no estimate yet. */
        fun batteryText(ctx: android.content.Context, t: Tracker): String {
            val samples = t.positions.filter { it.battery != null && it.receivedAt > 0 }
                .map { BatterySample(it.receivedAt, it.battery!!, it.mode) }
            val e = BatteryModel.estimate(samples) ?: return ctx.getString(R.string.batt_estimate_later)
            if (e.capped) return ctx.getString(R.string.batt_estimate_long)
            val h = e.remainingHours
            val txt = when {
                h < 48 -> ctx.resources.getQuantityString(R.plurals.hours, h.toInt().coerceAtLeast(1), h.toInt().coerceAtLeast(1))
                h < 24 * 14 -> ctx.resources.getQuantityString(R.plurals.days, (h / 24).toInt(), (h / 24).toInt())
                h < 24 * 60 -> ctx.resources.getQuantityString(R.plurals.weeks, (h / 168).toInt(), (h / 168).toInt())
                else -> ctx.resources.getQuantityString(R.plurals.months, (h / 720).toInt(), (h / 720).toInt())
            }
            return ctx.getString(R.string.batt_estimate, txt)
        }
    }
}

/**
 * Home on a phone: the map on top (~40 %) and the list as a bottom sheet over it. Scrolling the
 * list or dragging the handle collapses the map so the list fills the screen; pulling down at the
 * top brings it back. The state is remembered.
 */
@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
class HomeSheet(ctx: android.content.Context, private val map: TagMap, private val list: TagListView, private val onChange: (Boolean) -> Unit) : FrameLayout(ctx) {
    private val sheet = ctx.vbox()
    private val handle = FrameLayout(ctx)
    private val sheetBg = GradientDrawable().apply { setColor(ctx.col(R.color.bg)) }
    private var frac = 1f // 1 = map shown, 0 = collapsed
    private var collapsed = false
    private var anim: ValueAnimator? = null

    init {
        addView(map, LayoutParams(MATCH, 0))
        sheet.background = sheetBg
        sheet.elevation = ctx.dpf(6f)
        handle.addView(View(ctx).apply { background = ctx.rounded(ctx.col(R.color.outline), 2f); alpha = 0.6f },
            flp(ctx.dp(32), ctx.dp(4), Gravity.CENTER))
        handle.minimumHeight = ctx.dp(22)
        handle.isClickable = true
        handle.isFocusable = true
        handle.setOnClickListener { setCollapsed(!collapsed, true) }
        var downY = 0f
        handle.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> downY = e.rawY
                MotionEvent.ACTION_UP -> {
                    val dy = e.rawY - downY
                    if (kotlin.math.abs(dy) > ctx.dp(16)) { setCollapsed(dy < 0, true); return@setOnTouchListener true }
                }
            }
            false
        }
        sheet.addView(handle, LinearLayout.LayoutParams(MATCH, ctx.dp(22)))
        sheet.addView(list, LinearLayout.LayoutParams(MATCH, 0, 1f))
        addView(sheet, LayoutParams(MATCH, MATCH))
        list.recycler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                if (dy > ctx.dp(4) && !collapsed) setCollapsed(true, true)
            }
        })
        var pullY: Float? = null
        list.recycler.setOnTouchListener { rv, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> pullY = if (!rv.canScrollVertically(-1)) e.rawY else null
                MotionEvent.ACTION_MOVE -> pullY?.let { y0 ->
                    if (collapsed && e.rawY - y0 > ctx.dp(50) && !rv.canScrollVertically(-1)) { setCollapsed(false, true); pullY = null }
                }
            }
            false
        }
        handle.contentDescription = ctx.getString(R.string.a11y_hide_map)
    }

    fun setCollapsed(c: Boolean, animate: Boolean) {
        collapsed = c
        onChange(c)
        handle.contentDescription = context.getString(if (c) R.string.a11y_show_map else R.string.a11y_hide_map)
        anim?.cancel()
        val target = if (c) 0f else 1f
        if (!animate || !ValueAnimator.areAnimatorsEnabled()) { frac = target; relayout(); return }
        anim = ValueAnimator.ofFloat(frac, target).apply {
            duration = 280
            interpolator = android.view.animation.PathInterpolator(0.2f, 0f, 0f, 1f)
            addUpdateListener { frac = it.animatedValue as Float; relayout() }
            start()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        post { relayout() }
    }

    private fun relayout() {
        val h = height
        if (h == 0) return
        val mapH = (h * 0.4f).toInt()
        (map.layoutParams as LayoutParams).height = mapH
        map.visibility = if (frac <= 0.01f) INVISIBLE else VISIBLE
        val overlap = context.dp(20)
        val top = ((mapH - overlap) * frac).toInt().coerceAtLeast(0)
        (sheet.layoutParams as LayoutParams).topMargin = top
        val r = context.dpf(28f) * frac
        sheetBg.cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
        map.requestLayout(); sheet.requestLayout()
    }
}
