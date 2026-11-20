package app.libretag.ui

import app.libretag.data.WifiZoneTransit
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.widget.NestedScrollView
import app.libretag.CommandKind
import app.libretag.LibreTagApp
import app.libretag.R
import app.libretag.Reach
import app.libretag.data.Position
import app.libretag.data.Status
import app.libretag.data.StatusKind
import app.libretag.data.TagStatus
import app.libretag.data.Timeline
import app.libretag.data.Tracker
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.slider.Slider
import java.time.LocalDate

/** Which report the detail shows: a day (0 = newest) and a report on it (null = the latest). */
data class Sel(val day: Int = 0, val index: Int? = null)

interface DetailHost {
    fun selOf(id: String): Sel
    fun select(id: String, sel: Sel)
    /** a command button: Lost asks first, Track asks how often, the rest go at once */
    fun command(t: Tracker, kind: CommandKind)
    fun openInMap(t: Tracker, p: Position)
    fun openRepeaterMap()
    /** guard the newest fix (on) or stop guarding (off) */
    fun guard(t: Tracker, on: Boolean)
    /** after a guard alarm: "Guard the new spot" */
    fun rearmGuard(t: Tracker)
    fun cancelCommand(t: Tracker)
    /** a support strip for this tag right now, or null */
    fun supportStrip(t: Tracker, s: TagStatus): View?
    fun batteryEstimate(t: Tracker): String
    /** "Look up Wi-Fi position" was tapped; progress and the result come back through [WifiPositioning.states] */
    fun wifiLookup(t: Tracker, p: Position)
    /** 0.12: an action on the Why card (docs/design/tag-diagnosis.md 7.2) */
    fun diagAction(t: Tracker, a: app.libretag.data.Diagnosis.Action)
}

/**
 * The body of the tag detail: the big status plate (the state, and since when or what ends it),
 * Where / When / Battery / Route for the selected report, the commands with the one line that says
 * when a command reaches the tag (or the slip that replaces it), and the timeline (day chips, a
 * scrubber and stations) that drives the map and the rows together. Each block has one job and
 * one kind of time (data/StatusLines.kt).
 */
@SuppressLint("ViewConstructor")
class TagBodyView(ctx: Context, private val host: DetailHost) : NestedScrollView(ctx) {
    private val col = ctx.vbox { setPadding(dp(16), dp(16), dp(16), dp(40)) }
    private val bigPlate = PlateDrawable(ctx, ctx.col(R.color.plate_bg), ctx.col(R.color.plate))
    private val bigText = ctx.text(R.style.TextAppearance_LibreTag_BigStatus).apply { compoundDrawablePadding = dp(10) }
    private val bigSub = ctx.text(R.style.TextAppearance_LibreTag_Status)
    /** a guard alarm: re-arm at the newest fix, or stop guarding (both end the alarm) */
    private val guardActs = FlowRow(ctx).apply {
        addView(ctx.button(ctx.getString(R.string.guard_new_spot), Btn.FILLED, R.drawable.ic_lock) { t?.let { host.rearmGuard(it) } }.small())
        addView(ctx.button(ctx.getString(R.string.guard_stop), Btn.OUTLINED) { t?.let { host.guard(it, false) } }.small())
    }
    private val histBar = ctx.hbox {
        background = ctx.rounded(ctx.col(R.color.surf3), 8f)
        setPadding(dp(12), dp(2), dp(6), dp(2))
    }
    private val histText = ctx.text(R.style.TextAppearance_LibreTag_Status)
    private val rows = ctx.vbox()
    private val why = WhyCard(ctx) { t, a -> host.diagAction(t, a) }
    private val cmdGrid = ctx.vbox()
    private val cmdNotes = ctx.vbox()
    private val slipBox = FrameLayout(ctx)
    private val supportBox = FrameLayout(ctx)
    private val tlHead = sectionHead(ctx.getString(R.string.timeline))
    private val tlCount = ctx.text(R.style.TextAppearance_LibreTag_LabelMedium, color = R.color.ink3)
    private val days = ChipGroup(ctx).apply { isSingleLine = true; isSingleSelection = true; isSelectionRequired = true; chipSpacingHorizontal = dp(8) }
    private val scrubLabel = ctx.text(R.style.TextAppearance_LibreTag_Status, color = R.color.ink2)
    private val scrubRange = ctx.text(R.style.TextAppearance_LibreTag_Status, color = R.color.ink2)
    private val slider = Slider(ctx).apply { contentDescription = ctx.getString(R.string.scrub_a11y) }
    private val stations = ctx.vbox()
    private var t: Tracker? = null
    private var dayList: List<Timeline.Day> = emptyList()
    private var bindingSlider = false
    var onScrollChange: ((Int) -> Unit)? = null

    init {
        isFillViewport = true
        val plateBox = FrameLayout(ctx).apply {
            background = bigPlate
            setPadding(dp(14), dp(12), bigPlate.tipWidth + dp(4), dp(12))
            addView(ctx.vbox { addView(bigText); addView(bigSub, lp().margins(t = dp(4))) })
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
            accessibilityLiveRegion = ACCESSIBILITY_LIVE_REGION_POLITE
        }
        col.addView(plateBox, lp())
        col.addView(guardActs, lp().margins(t = dp(10)))
        col.addView(why, lp().margins(t = dp(10)))
        histBar.addView(histText, LinearLayout.LayoutParams(0, WRAP, 1f))
        histBar.addView(ctx.button(ctx.getString(R.string.back_to_latest), Btn.TEXT) { t?.let { host.select(it.id, Sel()) } })
        col.addView(histBar, lp().margins(t = dp(12)))
        col.addView(rows, lp().margins(t = dp(12)))
        col.addView(sectionHead(ctx.getString(R.string.commands)))
        col.addView(cmdGrid, lp())
        col.addView(cmdNotes, lp())
        col.addView(slipBox, lp().margins(t = dp(10)))
        col.addView(supportBox, lp())
        val head = ctx.hbox { addView(tlHead, LinearLayout.LayoutParams(0, WRAP, 1f)); addView(tlCount) }
        col.addView(head, lp().margins(t = dp(28), b = dp(10)))
        col.addView(HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = false
            addView(days)
        }, lp().margins(b = dp(8)))
        col.addView(ctx.hbox {
            addView(scrubLabel, LinearLayout.LayoutParams(0, WRAP, 1f))
            addView(scrubRange)
        }, lp().margins(t = dp(6)))
        col.addView(slider, lp())
        col.addView(stations, lp().margins(t = dp(4)))
        addView(col)
        slider.addOnChangeListener { _, v, fromUser ->
            if (bindingSlider || !fromUser) return@addOnChangeListener
            val tag = t ?: return@addOnChangeListener
            val sel = host.selOf(tag.id)
            host.select(tag.id, sel.copy(index = v.toInt()))
        }
        setOnScrollChangeListener { _: NestedScrollView, _: Int, y: Int, _: Int, _: Int -> onScrollChange?.invoke(y) }
    }

    private fun sectionHead(s: String) = context.text(R.style.TextAppearance_LibreTag_Section, s).apply {
        layoutParams = lp().margins(t = dp(28), b = dp(10))
        isFocusable = true
        androidx.core.view.ViewCompat.setAccessibilityHeading(this, true)
    }

    val tagId: String? get() = t?.id

    fun bind(tag: Tracker, now: Long = System.currentTimeMillis()) {
        t = tag
        val ctx = context
        // the detail keeps the state under a low battery; the Battery row carries the warning
        val s = Status.of(tag, now, lowBattery = false)
        // 0.12: the diagnosis: its sentence on the plate, its evidence on the Why card
        val la = app.libretag.LibreTagApp.instance
        val d = la.diag.value[tag.id]
        val band = app.libretag.data.Diagnosis.band(d, s)
        val lvl = if (band) s.level else TagStatus.Level.NONE
        why.bind(tag, s, d, la.diagContext(tag, now), app.libretag.usb.dfu.BundledFirmware.version, now)
        // big status plate
        val (fill, stroke, fg) = when (if (band) s.level else TagStatus.Level.NONE) {
            TagStatus.Level.CRIT -> Triple(R.color.crit, R.color.crit, R.color.on_crit)
            TagStatus.Level.WARN -> Triple(R.color.warn, R.color.warn, R.color.on_warn)
            else -> Triple(R.color.plate_bg, R.color.plate, R.color.ink)
        }
        bigPlate.fillColor = ctx.col(fill); bigPlate.strokeColor = ctx.col(stroke); bigPlate.invalidateSelf()
        bigText.text = DiagText.status(ctx, tag, s, d, now, compact = false)
        bigText.setTextColor(ctx.col(fg))
        val ic = TagRowView.statusIcon(s.kind)
        bigText.setCompoundDrawablesRelative(ic?.let { ctx.tinted(it, ctx.col(fg), 24) }, null, null, null)
        val sub = TagText.statusSub(ctx, tag, s, now)
        bigSub.text = sub ?: ""
        bigSub.visibility = if (sub == null) GONE else VISIBLE
        bigSub.setTextColor(ctx.col(if (lvl == TagStatus.Level.NONE) R.color.ink2 else fg))
        bigSub.setPaddingRelative(if (lvl != TagStatus.Level.NONE && ic != null) dp(34) else 0, 0, 0, 0)
        (bigText.parent.parent as View).contentDescription = bigText.text.toString() + (sub?.let { ". $it" } ?: "")
        guardActs.visibility = if (s.kind == StatusKind.ALARM) VISIBLE else GONE

        dayList = Timeline.days(tag, now = now)
        bindRows(tag, s, now)
        bindCommands(tag, s, now)
        bindTimeline(tag, now)
    }

    /** Scrubbing: only the rows and the timeline highlight change (the map is updated by the host). */
    fun bindSelection(now: Long = System.currentTimeMillis()) {
        val tag = t ?: return
        bindRows(tag, Status.of(tag, now, lowBattery = false), now)
        bindTimeline(tag, now)
    }

    private fun selected(tag: Tracker): Triple<Position?, Boolean, Int> {
        val sel = host.selOf(tag.id)
        val day = dayList.getOrNull(sel.day) ?: return Triple(tag.last, true, 0)
        val reps = day.reports
        val i = sel.index?.coerceIn(0, maxOf(0, reps.size - 1)) ?: (reps.size - 1)
        val p = reps.getOrNull(i)
        val latest = sel.day == 0 && (sel.index == null || i == reps.size - 1) && p?.n == tag.last?.n
        return Triple(p, latest, i)
    }

    private fun bindRows(tag: Tracker, s: TagStatus, now: Long) {
        val ctx = context
        val (p, latest, _) = selected(tag)
        histBar.visibility = if (latest || p == null) GONE else VISIBLE
        if (p != null) histText.text = ctx.getString(R.string.showing_report, Format.at(ctx, p.receivedAt, now))
        rows.removeAllViews()
        rows.addView(ctx.divider())
        // Where
        val where = ctx.vbox()
        val (main, sub) = whereText(tag, p, now)
        where.addView(ctx.text(R.style.TextAppearance_LibreTag_RowValue, main))
        sub?.let { where.addView(ctx.text(R.style.TextAppearance_LibreTag_BodySmall, it).apply { setTextIsSelectable(true) }, lp().margins(t = dp(1))) }
        // a Wi-Fi lookup: its progress and how it ended, right here (not a snackbar)
        val look = p?.takeIf { WifiPositioning.canLookUp(tag, it) }?.let { WifiPositioning.stateOf(tag.id, it.n) }
        lookupNote(look)?.let { where.addView(ctx.text(R.style.TextAppearance_LibreTag_BodySmall, it, R.color.ink2), lp().margins(t = dp(2))) }
        // guarding: since when, and how far it may move
        val guardLine = tag.guardSince?.takeIf { tag.guarding && s.kind != StatusKind.ALARM && latest }
            ?.let { ctx.getString(R.string.guard_since, Format.at(ctx, it * 1000, now), Format.distance(ctx, LibreTagApp.GUARD_M)) }
        guardLine?.let { where.addView(ctx.text(R.style.TextAppearance_LibreTag_BodySmall, it).apply {
            setCompoundDrawablesRelative(ctx.tinted(R.drawable.ic_lock, ctx.col(R.color.ink2), 14), null, null, null)
            compoundDrawablePadding = dp(4)
        }, lp().margins(t = dp(2))) }
        val acts = FlowRow(ctx)
        val target = p?.let { TagText.target(tag, it) }
        if (p != null && target != null) {
            acts.addView(ctx.button(ctx.getString(R.string.open_in_map), Btn.OUTLINED, R.drawable.ic_open) { host.openInMap(tag, p) }.small())
            acts.addView(ctx.button(ctx.getString(R.string.copy_coordinates), Btn.OUTLINED, R.drawable.ic_copy) {
                ctx.copyText("coordinates", Format.coord(target.first, target.second))
                it.snack(ctx.getString(R.string.coordinates_copied))
            }.small())
        } else if (p != null && p.est?.state == "nodb") {
            acts.addView(ctx.button(ctx.getString(R.string.banner_action_map), Btn.OUTLINED, R.drawable.ic_download) { host.openRepeaterMap() }.small())
        }
        // the lookup does not need a position to exist: a Wi-Fi-only tag has none until it runs
        if (p != null && WifiPositioning.canLookUp(tag, p)) {
            val running = look?.running == true
            acts.addView(ctx.button(ctx.getString(if (running) R.string.wifi_looking else R.string.where_wifi_lookup), Btn.OUTLINED, R.drawable.ic_wifi) {
                host.wifiLookup(tag, p)
            }.small().apply { isEnabled = !running })
        }
        // guard lives here: a spot with a fix; an alarm has its own two buttons on the plate
        if (latest && s.kind != StatusKind.ALARM) {
            if (tag.guarding) acts.addView(ctx.button(ctx.getString(R.string.guard_stop), Btn.OUTLINED, R.drawable.ic_lock) { host.guard(tag, false) }.small())
            else if (p?.hasFix == true) acts.addView(ctx.button(ctx.getString(R.string.guard_here), Btn.OUTLINED, R.drawable.ic_lock) { host.guard(tag, true) }.small())
        }
        if (acts.childCount > 0) where.addView(acts, lp().margins(t = dp(10)))
        rows.addView(row(ctx.getString(R.string.where), where))
        // When
        val whenBox = ctx.vbox()
        if (p == null) {
            whenBox.addView(ctx.text(R.style.TextAppearance_LibreTag_RowValue, ctx.getString(R.string.when_waiting)))
        } else if (latest) {
            whenBox.addView(ctx.text(R.style.TextAppearance_LibreTag_RowValue,
                ctx.getString(R.string.when_heard, Format.agoLong(ctx, p.receivedAt, now), (if (p.approx) "~" else "") + Format.at(ctx, p.receivedAt, now))))
            // exactly one "next report" line; listen times live only in the command block
            TagText.nextLine(ctx, tag, s, now)?.let { whenBox.addView(ctx.text(R.style.TextAppearance_LibreTag_BodySmall, it)) }
        } else {
            whenBox.addView(ctx.text(R.style.TextAppearance_LibreTag_RowValue,
                ctx.getString(R.string.when_report_at, Format.agoAt(ctx, p.receivedAt, now))))
            val last = tag.last
            val rel = if (last != null && LocalDate.now().toEpochDay() - dayOf(p.receivedAt) == 0L)
                ctx.getString(R.string.when_report_before, Format.duration(ctx, last.receivedAt - p.receivedAt))
            else ctx.getString(R.string.when_report_earlier)
            // the report number is for the curious: small, after the times
            val sub = ctx.getString(R.string.stn_sub, p.n.toInt(), rel)
            whenBox.addView(ctx.text(R.style.TextAppearance_LibreTag_BodySmall, sub))
        }
        rows.addView(row(ctx.getString(R.string.`when`), whenBox))
        // Battery
        val bat = ctx.vbox()
        val b = p?.battery
        bat.addView(ctx.text(R.style.TextAppearance_LibreTag_RowValue, when {
            b == null -> ctx.getString(R.string.batt_unknown)
            b < 20 -> ctx.getString(R.string.batt_value_low, b)
            else -> ctx.getString(R.string.batt_value, b)
        }))
        bat.addView(ctx.text(R.style.TextAppearance_LibreTag_BodySmall, host.batteryEstimate(tag)))
        rows.addView(row(ctx.getString(R.string.battery), bat))
        // Route
        if (p != null) {
            val route = ctx.vbox()
            route.addView(ctx.text(R.style.TextAppearance_LibreTag_RowValue, TagText.route(ctx, p)))
            val sub2 = when (p.via) {
                "nostr" -> ctx.getString(R.string.route_nostr_sub)
                "usb", "usb-quiet" -> ctx.getString(R.string.route_usb_sub)
                else -> null // SNR is in Diagnostics
            }
            sub2?.let { route.addView(ctx.text(R.style.TextAppearance_LibreTag_BodySmall, it)) }
            val names = p.est?.anchors?.map { it.name }?.filter { it.isNotBlank() }
            if (!names.isNullOrEmpty()) route.addView(ctx.text(R.style.TextAppearance_LibreTag_BodySmall,
                ctx.getString(R.string.route_last_repeater, names.joinToString(", "))))
            rows.addView(row(ctx.getString(R.string.route), route))
        }
    }

    private fun dayOf(ms: Long) = java.time.Instant.ofEpochMilli(ms).atZone(java.time.ZoneId.systemDefault()).toLocalDate().toEpochDay()

    private fun whereText(tag: Tracker, p: Position?, now: Long): Pair<String, String?> {
        val ctx = context
        if (p == null) return ctx.getString(R.string.pos_none) to null
        // quiet at one of its places: the place is the answer
        TagText.placeOf(tag, p)?.let { place ->
            val sub = when {
                p.hasFix -> (p.haccM?.let { ctx.getString(R.string.where_gps, Format.distance(ctx, it.toDouble())) + " · " } ?: "") + Format.coord(p.lat, p.lon)
                tag.wifiTag || p.wifi != null -> ctx.getString(R.string.where_place_wifi)
                else -> null
            }
            return TagText.atPlace(ctx, place) to sub
        }
        if (p.hasFix) {
            val main = p.haccM?.let { ctx.getString(R.string.where_gps, Format.distance(ctx, it.toDouble())) } ?: ctx.getString(R.string.where_gps_noacc)
            return main to Format.coord(p.lat, p.lon)
        }
        tag.wifiFixes[p.n]?.let { w ->
            return ctx.getString(R.string.pos_wifi_area, Format.distance(ctx, w.r68)) to
                ctx.getString(R.string.where_wifi_sub, Format.coord(w.lat, w.lon),
                    WifiPositioning.serviceName(ctx, w.service))
        }
        val e = p.est
        val lastGps = when {
            p.lastFixTime > 0 -> ctx.getString(R.string.where_none_last, Format.agoAt(ctx, p.lastFixTime * 1000, now), Format.coord(p.lastLat, p.lastLon))
            else -> tag.positions.lastOrNull { it.hasFix && it.n < p.n }?.let {
                ctx.getString(R.string.where_none_last, Format.agoAt(ctx, it.receivedAt, now), Format.coord(it.lat, it.lon))
            }
        }
        return when {
            // the networks it heard are the clue: say so, the lookup button is below
            p.wifi != null && e?.state != "ok" -> TagText.wifiHeard(ctx, p) to lastGps
            e?.state == "ok" -> ctx.getString(R.string.where_area, Format.distance(ctx, e.r68)) to
                ctx.getString(R.string.where_area_sub, Format.coord(e.lat, e.lon), Format.distance(ctx, e.r95))
            e?.state == "ambiguous" -> ctx.getString(R.string.where_no_gps) to (lastGps?.let {
                ctx.getString(R.string.where_ambiguous_sub) + " " + it } ?: ctx.getString(R.string.where_ambiguous_sub))
            e?.state == "nodb" -> ctx.getString(R.string.where_no_gps) to ctx.getString(R.string.where_need_map_sub)
            e == null && p.neighbours.isNotEmpty() -> ctx.getString(R.string.where_no_gps) to ctx.getString(R.string.where_estimating_sub)
            p.nbTotal == 0 && lastGps == null -> ctx.getString(R.string.where_no_gps) to ctx.getString(R.string.pos_no_repeater)
            else -> ctx.getString(R.string.where_no_gps) to (lastGps ?: ctx.getString(R.string.where_none_sub))
        }
    }

    /** The Where row's line for a lookup that ran: "Looking up…", "BeaconDB doesn't know these networks", … */
    private fun lookupNote(st: WifiPositioning.State?): String? {
        val ctx = context
        st ?: return null
        if (st.running) return ctx.getString(R.string.wifi_looking)
        val svc = WifiPositioning.serviceName(ctx, st.service)
        return when (st.outcome) {
            WifiPositioning.Outcome.UNKNOWN -> ctx.getString(R.string.wifi_unknown, svc)
            WifiPositioning.Outcome.BUSY -> ctx.getString(R.string.wifi_busy, svc)
            WifiPositioning.Outcome.FAILED -> ctx.getString(R.string.wifi_failed)
            else -> null
        }
    }

    private fun row(key: String, value: View): View {
        val ctx = context
        val r = ctx.hbox {
            gravity = Gravity.TOP
            setPadding(0, dp(12), 0, dp(12))
            addView(ctx.text(R.style.TextAppearance_LibreTag_RowKey, key), LinearLayout.LayoutParams(dp(76), WRAP))
            addView(value, LinearLayout.LayoutParams(0, WRAP, 1f).margins(l = dp(8)))
        }
        return ctx.vbox { addView(r); addView(ctx.divider()) }
    }

    private fun bindCommands(tag: Tracker, s: TagStatus, now: Long) {
        val ctx = context
        cmdGrid.removeAllViews()
        val mode = Status.modeNow(tag, now)
        // in Lost or Track a fourth button ends it, and it is the primary action
        val stop = when (mode) {
            "LOST" -> R.string.cmd_stop_lost
            "TRACK" -> R.string.cmd_stop_track
            else -> null
        }
        val primary = when {
            stop != null -> CommandKind.NORMAL
            s.kind == StatusKind.ALARM || s.kind == StatusKind.SILENT -> CommandKind.LOST
            else -> CommandKind.BEEP
        }
        val enabled = tag.canCommand
        fun btn(k: CommandKind, label: String = TagText.cmdName(ctx, k)): MaterialButton {
            val icon = when (k) {
                CommandKind.LOST -> R.drawable.ic_lost
                CommandKind.TRACK -> R.drawable.ic_track
                CommandKind.BEEP -> R.drawable.ic_beep
                CommandKind.NORMAL -> R.drawable.ic_normal
                CommandKind.BLE_SCAN -> R.drawable.ic_bt
                CommandKind.INTERVAL -> R.drawable.ic_clock
                CommandKind.BLE_CONSOLE -> R.drawable.ic_bt
            }
            val kind = when {
                k != primary -> Btn.OUTLINED
                k == CommandKind.LOST -> Btn.CRIT
                else -> Btn.FILLED
            }
            return ctx.button(label, kind, icon) { host.command(tag, k) }.apply {
                minHeight = dp(52)
                isEnabled = enabled
                maxLines = 1
                textSize = 15f
                // CommandRowTest measures the labels with this padding
                setPaddingRelative(dp(12), paddingTop, dp(14), paddingBottom)
                iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START
            }
        }
        stop?.let { cmdGrid.addView(btn(CommandKind.NORMAL, ctx.getString(it)), lp().margins(b = dp(8))) }
        // one row of equal buttons when the labels fit, else wrapped rows; never a word broken (0.12)
        cmdGrid.addView(CommandRow(ctx).apply {
            listOf(CommandKind.LOST, CommandKind.TRACK, CommandKind.BEEP).forEach { k -> addView(btn(k)) }
        }, lp())
        cmdNotes.removeAllViews()
        slipBox.removeAllViews()
        val slip = TagText.slip(ctx, tag, s, now)
        if (!enabled) {
            cmdNotes.addView(note(ctx.getString(R.string.note_view_only, tag.sharedBy ?: "")))
        } else if (slip == null || slip.state == TagText.SlipState.CONFIRMED) {
            // the one line: when a command reaches the tag (a waiting command's slip replaces it)
            cmdNotes.addView(ctx.text(R.style.TextAppearance_LibreTag_Status, TagText.reachLine(ctx, tag, s, now), R.color.ink2).apply {
                setCompoundDrawablesRelative(ctx.tinted(R.drawable.ic_clock, ctx.col(R.color.ink2), 16), null, null, null)
                compoundDrawablePadding = dp(6)
            }, lp().margins(t = dp(10)))
        }
        slip?.let { sl ->
            val v = SlipView(ctx).apply { bind(sl); textSize = 14f; setPadding(dp(10), dp(6), dp(10), dp(6)) }
            slipBox.addView(ctx.hbox {
                gravity = Gravity.CENTER_VERTICAL
                addView(v, LinearLayout.LayoutParams(0, WRAP, 1f).apply { gravity = Gravity.START })
                if (tag.pending != null && tag.pending.sentAt == null && enabled) {
                    addView(ctx.button(ctx.getString(R.string.slip_cancel), Btn.TEXT) { host.cancelCommand(tag) })
                }
            }, flp())
        }
        supportBox.removeAllViews()
        host.supportStrip(tag, s)?.let { supportBox.addView(it, flp().apply { topMargin = dp(16) }) }
    }

    private fun note(s: String) = context.text(R.style.TextAppearance_LibreTag_Status, s).apply {
        background = context.rounded(context.col(R.color.surf3), 8f)
        setPadding(dp(12), dp(10), dp(12), dp(10))
        layoutParams = lp().margins(t = dp(10))
    }

    private fun bindTimeline(tag: Tracker, now: Long) {
        val ctx = context
        val sel = host.selOf(tag.id)
        val dIdx = sel.day.coerceIn(0, maxOf(0, dayList.size - 1))
        val day = dayList.getOrNull(dIdx)
        val reps = day?.reports ?: emptyList()
        tlCount.text = ctx.resources.getQuantityString(R.plurals.timeline_reports, reps.size, reps.size)
        // day chips
        if (days.childCount != dayList.size || days.tag != dayList.map { it.date }) {
            days.removeAllViews()
            days.tag = dayList.map { it.date }
            dayList.forEachIndexed { i, d ->
                days.addView(Chip(ctx).apply {
                    text = dayLabel(d.date)
                    isCheckable = true
                    isCheckedIconVisible = true
                    id = View.generateViewId()
                    setOnClickListener { host.select(tag.id, Sel(i, null)) }
                })
            }
        }
        for (i in 0 until days.childCount) (days.getChildAt(i) as Chip).isChecked = i == dIdx
        val (p, _, idx) = selected(tag)
        bindingSlider = true
        if (reps.size > 1) {
            slider.visibility = VISIBLE
            slider.valueFrom = 0f
            slider.valueTo = (reps.size - 1).toFloat()
            slider.stepSize = 1f
            slider.value = idx.coerceIn(0, reps.size - 1).toFloat()
        } else slider.visibility = GONE
        bindingSlider = false
        scrubLabel.text = if (p != null && reps.isNotEmpty()) ctx.getString(R.string.scrub_label, idx + 1, reps.size, Format.time(ctx, p.receivedAt))
            else ctx.getString(R.string.scrub_none)
        scrubRange.text = if (reps.size > 1) ctx.getString(R.string.scrub_range, Format.time(ctx, reps.first().receivedAt), Format.time(ctx, reps.last().receivedAt)) else ""
        // stations, newest first
        stations.removeAllViews()
        val c = BlazeColors.of(ctx, tag.markColor)
        val items = day?.items?.reversed() ?: emptyList()
        items.forEachIndexed { k, it ->
            val first = k == 0; val last = k == items.size - 1
            when (it) {
                is Timeline.Report -> {
                    val on = it.index == idx
                    stations.addView(StationView(ctx, c, false, first, last, on).apply {
                        time.text = Format.time(ctx, it.p.receivedAt)
                        what.text = stationWhat(tag, it.p)
                        sub.text = it.p.battery?.let { b -> ctx.getString(R.string.stn_sub_batt, it.p.n.toInt(), b) }
                            ?: ctx.getString(R.string.stn_sub, it.p.n.toInt(), TagText.route(ctx, it.p))
                        setOnClickListener { _ -> host.select(tag.id, Sel(dIdx, it.index)) }
                        contentDescription = time.text.toString() + ", " + what.text + ", " + sub.text
                    })
                }
                is Timeline.Quiet -> stations.addView(StationView(ctx, c, true, first, last, false).apply {
                    time.text = it.to?.let { to -> Format.time(ctx, to) } ?: ctx.getString(R.string.stn_now)
                    what.text = when {
                        it.owner -> ctx.getString(R.string.stn_quiet_owner)
                        it.parked -> ctx.getString(R.string.stn_quiet_parked)
                        it.where == WifiZoneTransit.UNNAMED -> ctx.getString(R.string.stn_quiet_wifi)
                        else -> ctx.getString(R.string.stn_quiet, TagText.zoneOrHome(ctx, it.where))
                    }
                    sub.text = if (it.to == null) ctx.getString(R.string.stn_since, Format.at(ctx, it.from, now))
                        else ctx.getString(R.string.stn_gap_sub, Format.at(ctx, it.from, now), Format.at(ctx, it.to, now))
                    isClickable = false
                })
                is Timeline.Gap -> stations.addView(StationView(ctx, c, true, first, last, false).apply {
                    time.text = Format.time(ctx, it.to)
                    what.text = ctx.getString(R.string.stn_gap)
                    sub.text = ctx.getString(R.string.stn_gap_sub, Format.at(ctx, it.from, now), Format.at(ctx, it.to, now))
                    isClickable = false
                })
            }
        }
    }

    private fun stationWhat(tag: Tracker, p: Position): String {
        val ctx = context
        val base = when {
            p.hasFix -> p.haccM?.let { ctx.getString(R.string.stn_gps, Format.distance(ctx, it.toDouble())) } ?: ctx.getString(R.string.stn_gps_noacc)
            tag.wifiFixes[p.n] != null -> ctx.getString(R.string.stn_area, Format.distance(ctx, tag.wifiFixes[p.n]!!.r68))
            p.est?.state == "ok" -> ctx.getString(R.string.stn_area, Format.distance(ctx, p.est.r68))
            p.est?.state == "ambiguous" -> ctx.getString(R.string.stn_unclear)
            else -> ctx.getString(R.string.stn_no_gps)
        }
        val extra = when (p.mode) {
            "LOST" -> " · " + ctx.getString(R.string.stn_lost)
            "TRACK" -> " · " + ctx.getString(R.string.stn_track)
            else -> ""
        }
        return base + extra
    }

    private fun dayLabel(d: LocalDate): String {
        val today = LocalDate.now()
        return when (d) {
            today -> context.getString(R.string.day_today)
            today.minusDays(1) -> context.getString(R.string.day_yesterday)
            else -> Format.day(d.atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli())
        }
    }

    /** The report the detail shows (for the map). */
    fun selectedReport(): Position? = t?.let { selected(it).first }
    fun dayReports(): List<Position> {
        val tag = t ?: return emptyList()
        return dayList.getOrNull(host.selOf(tag.id).day.coerceIn(0, maxOf(0, dayList.size - 1)))?.reports ?: emptyList()
    }
}

internal fun MaterialButton.small(): MaterialButton = apply {
    minHeight = dp(36); minimumHeight = dp(36)
    textSize = 13f
    setPadding(dp(14), 0, dp(14), 0)
    insetTop = dp(4); insetBottom = dp(4)
}

/** A row of views that wraps to the next line (for action buttons under a row). */
class FlowRow(ctx: Context) : android.view.ViewGroup(ctx) {
    private val gap = ctx.dp(8)
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val maxW = MeasureSpec.getSize(widthMeasureSpec)
        var x = 0; var y = 0; var rowH = 0
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (c.visibility == GONE) continue
            measureChild(c, MeasureSpec.makeMeasureSpec(maxW, MeasureSpec.AT_MOST), heightMeasureSpec)
            if (x > 0 && x + c.measuredWidth > maxW) { x = 0; y += rowH; rowH = 0 }
            x += c.measuredWidth + gap
            rowH = maxOf(rowH, c.measuredHeight)
        }
        setMeasuredDimension(maxW, y + rowH)
    }
    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val maxW = r - l
        var x = 0; var y = 0; var rowH = 0
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (c.visibility == GONE) continue
            if (x > 0 && x + c.measuredWidth > maxW) { x = 0; y += rowH; rowH = 0 }
            c.layout(x, y, x + c.measuredWidth, y + c.measuredHeight)
            x += c.measuredWidth + gap
            rowH = maxOf(rowH, c.measuredHeight)
        }
    }
}

/** One station of the timeline: time | dashed line with a dot | what and n. */
@SuppressLint("ViewConstructor")
class StationView(ctx: Context, private val color: Int, private val quiet: Boolean, private val first: Boolean, private val last: Boolean, on: Boolean) : LinearLayout(ctx) {
    val time: TextView = ctx.text(R.style.TextAppearance_LibreTag_Age).apply { textSize = 15f }
    val what: TextView = ctx.text(R.style.TextAppearance_LibreTag_Status, color = if (quiet) R.color.ink2 else R.color.ink)
    val sub: TextView = ctx.text(R.style.TextAppearance_LibreTag_LabelMedium, color = R.color.ink3)
    private val lineCol = object : View(ctx) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(c: Canvas) {
            val d = resources.displayMetrics.density
            val x = 12.5f * d
            p.style = Paint.Style.STROKE
            p.strokeWidth = 3 * d
            p.color = if (quiet) ctx.col(R.color.ink3) else color
            p.pathEffect = if (quiet) DashPathEffect(floatArrayOf(1 * d, 4 * d), 0f) else DashPathEffect(floatArrayOf(6 * d, 4 * d), 0f)
            p.strokeCap = if (quiet) Paint.Cap.ROUND else Paint.Cap.BUTT
            val top = if (first) 18 * d else 0f
            val bottom = if (last) 18 * d else height.toFloat()
            if (bottom > top) c.drawLine(x, top, x, bottom, p)
            p.pathEffect = null
            p.strokeCap = Paint.Cap.BUTT
            val cy = 17 * d
            if (quiet) {
                p.style = Paint.Style.FILL; p.color = ctx.col(R.color.chip)
                c.drawRect(x - 6.5f * d, cy - 6.5f * d, x + 6.5f * d, cy + 6.5f * d, p)
                p.style = Paint.Style.STROKE; p.color = ctx.col(R.color.ink3)
                c.drawRoundRect(x - 6.5f * d, cy - 6.5f * d, x + 6.5f * d, cy + 6.5f * d, 3 * d, 3 * d, p)
            } else {
                p.style = Paint.Style.FILL; p.color = if (this@StationView.isSelected) color else ctx.col(R.color.chip)
                c.drawCircle(x, cy, 7.5f * d, p)
                p.style = Paint.Style.STROKE; p.color = color
                c.drawCircle(x, cy, 6f * d, p)
            }
        }
    }

    init {
        orientation = HORIZONTAL
        minimumHeight = dp(48)
        setPadding(dp(4), dp(6), dp(4), dp(6))
        addView(time, LayoutParams(dp(56), WRAP))
        addView(lineCol, LayoutParams(dp(26), MATCH))
        addView(ctx.vbox { addView(what); addView(sub) }, LayoutParams(0, WRAP, 1f).margins(l = dp(6)))
        isSelected = on
        if (on) background = ctx.rounded(ctx.col(R.color.sel), 8f)
        else {
            val tv = android.util.TypedValue()
            ctx.theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
            setBackgroundResource(tv.resourceId)
        }
        isClickable = !quiet
        isFocusable = !quiet
        lineCol.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
    }

    override fun setSelected(selected: Boolean) {
        super.setSelected(selected)
        lineCol.invalidate()
    }
}
