package app.libretag.ui

import android.content.Context
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.TextAppearanceSpan
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import app.libretag.R
import app.libretag.data.StatusKind
import app.libretag.data.TagStatus
import app.libretag.data.Tracker

/**
 * The signpost row: a plate with an arrow tip, the tag's blaze, name and type, the one status
 * sentence (or a red/orange band across the plate for alarms), position and certainty, the
 * command slip, and on the right the age and battery. Alarms are a band, never just a red blaze.
 */
class TagRowView(ctx: Context) : FrameLayout(ctx) {
    private val plate = PlateDrawable(ctx, ctx.col(R.color.plate_bg), ctx.col(R.color.plate))
    private val band = ctx.text(R.style.TextAppearance_LibreTag_Band).apply {
        setPadding(dp(12), dp(6), dp(12), dp(6))
        compoundDrawablePadding = dp(8)
        gravity = Gravity.CENTER_VERTICAL
    }
    private val blaze = ImageView(ctx).apply { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
    private val blazeDrawable = BlazeDrawable(ctx, "yellow", "stripe")
    private val name = ctx.text(R.style.TextAppearance_LibreTag_TagName)
    private val status = ctx.text(R.style.TextAppearance_LibreTag_Status).apply { compoundDrawablePadding = dp(6) }
    private val pos = ctx.text(R.style.TextAppearance_LibreTag_PosLine)
    private val slip = SlipView(ctx)
    private val age = ctx.text(R.style.TextAppearance_LibreTag_Age).apply { gravity = Gravity.END }
    private val batt = ctx.text(R.style.TextAppearance_LibreTag_LabelMedium, color = R.color.ink2).apply {
        compoundDrawablePadding = dp(4)
        gravity = Gravity.END or Gravity.CENTER_VERTICAL
    }
    private val battDrawable = BatteryDrawable(ctx, null, ctx.col(R.color.ink2))

    init {
        background = plate
        setPadding(dp(2), dp(2), plate.tipWidth, dp(2))
        blaze.setImageDrawable(blazeDrawable)
        battDrawable.setBounds(0, 0, dp(22), dp(12))
        batt.setCompoundDrawablesRelative(battDrawable, null, null, null)
        val main = ctx.vbox {
            addView(name)
            addView(status, lp().margins(t = dp(2)))
            addView(pos, lp().margins(t = dp(2)))
            addView(slip, lp(WRAP, WRAP).margins(t = dp(6)))
        }
        val meta = ctx.vbox {
            gravity = Gravity.END
            addView(age, lp(WRAP, WRAP))
            addView(batt, lp(WRAP, WRAP).margins(t = dp(4)))
        }
        val row = ctx.hbox {
            gravity = Gravity.TOP
            setPadding(dp(10), dp(10), dp(2), dp(10))
            addView(blaze, LinearLayout.LayoutParams(dp(24), dp(28)).margins(t = dp(2)))
            addView(main, LinearLayout.LayoutParams(0, WRAP, 1f).margins(l = dp(12), r = dp(8)))
            addView(meta, lp(WRAP, WRAP))
        }
        addView(ctx.vbox { addView(band); addView(row) }, flp())
        isClickable = true
        isFocusable = true
        val tv = android.util.TypedValue()
        ctx.theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
        foreground = ctx.getDrawable(tv.resourceId)
        layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).margins(b = dp(8))
    }

    fun bind(t: Tracker, s: TagStatus, selected: Boolean, now: Long = System.currentTimeMillis()) {
        val ctx = context
        blazeDrawable.color = t.markColor; blazeDrawable.symbol = t.markSymbol; blazeDrawable.invalidateSelf()
        val sb = SpannableStringBuilder(t.name)
        // guarding (armed, no alarm): a small lock after the name, no words
        if (t.guarding && !t.guardAlarm) {
            sb.append("  ")
            val lock = ctx.tinted(R.drawable.ic_lock, ctx.col(R.color.ink2), 14)
            sb.setSpan(android.text.style.ImageSpan(lock, android.text.style.DynamicDrawableSpan.ALIGN_BASELINE), sb.length - 1, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        val typeTxt = "  " + TagText.typeName(ctx, t.type) + if (t.isShared) " · " + ctx.getString(R.string.shared_suffix) else ""
        val start = sb.length
        sb.append(typeTxt)
        sb.setSpan(TextAppearanceSpan(ctx, R.style.TextAppearance_LibreTag_LabelMedium), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.setSpan(android.text.style.ForegroundColorSpan(ctx.col(R.color.ink3)), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        name.text = sb
        // 0.12: the diagnosis of a late tag (docs/design/tag-diagnosis.md 7.1)
        val d = runCatching { app.libretag.LibreTagApp.instance.diag.value[t.id] }.getOrNull()
        val shown = d != null && app.libretag.data.Diagnosis.shown(d, s)
        val sentence = DiagText.status(ctx, t, s, d, now, compact = true)
        val showBand = app.libretag.data.Diagnosis.band(d, s)
        val iconRes = if (shown && d!!.level == app.libretag.data.Diagnosis.Level.NOTE) R.drawable.ic_info
            else if (shown && d!!.level == app.libretag.data.Diagnosis.Level.CALM) null else statusIcon(s.kind)
        when (if (showBand) s.level else TagStatus.Level.NONE) {
            TagStatus.Level.CRIT, TagStatus.Level.WARN -> {
                val crit = s.level == TagStatus.Level.CRIT
                band.visibility = VISIBLE
                band.text = sentence
                val fg = ctx.col(if (crit) R.color.on_crit else R.color.on_warn)
                band.setTextColor(fg)
                band.setBackgroundColor(ctx.col(if (crit) R.color.crit else R.color.warn))
                band.setCompoundDrawablesRelative(iconRes?.let { ctx.tinted(it, fg, 18) }, null, null, null)
                status.visibility = GONE
                plate.strokeColor = ctx.col(if (crit) R.color.crit else R.color.plate)
            }
            else -> {
                band.visibility = GONE
                status.visibility = VISIBLE
                status.text = sentence
                status.setCompoundDrawablesRelative(iconRes?.let { ctx.tinted(it, ctx.col(R.color.ink2), 16) }, null, null, null)
                plate.strokeColor = ctx.col(R.color.plate)
            }
        }
        plate.fillColor = ctx.col(if (selected) R.color.sel else R.color.plate_bg)
        plate.invalidateSelf()
        pos.text = TagText.position(ctx, t, t.last, true, now)
        // the list's slip: command and time only; the full sentence is in the detail
        val sl = TagText.slip(ctx, t, s, now, compact = true)
        slip.visibility = if (sl != null) VISIBLE else GONE
        sl?.let { slip.bind(it) }
        age.text = s.heardAt?.let { Format.ageShort(ctx, it, now) } ?: "–"
        val b = s.battery
        batt.visibility = if (b != null) VISIBLE else GONE
        battDrawable.level = b
        battDrawable.color = ctx.col(if (b != null && b < 20) R.color.ink else R.color.ink2)
        battDrawable.invalidateSelf()
        batt.text = b?.let { Format.pct(it) }
        batt.setTextColor(ctx.col(if (b != null && b < 20) R.color.ink else R.color.ink2))
        contentDescription = TagText.aria(ctx, t, s, now)
        isSelected = selected
        // the whole plate is one element for TalkBack
        for (v in listOf(band, name, status, pos, slip, age, batt)) v.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    companion object {
        fun statusIcon(k: StatusKind): Int? = when (k) {
            StatusKind.LOST -> R.drawable.ic_lost
            StatusKind.ALARM -> R.drawable.ic_alert
            StatusKind.SILENT -> R.drawable.ic_clock
            StatusKind.LOW_BATTERY -> R.drawable.ic_alert
            StatusKind.TRACKING -> R.drawable.ic_track
            StatusKind.QUIET -> R.drawable.ic_moon
            StatusKind.WAITING -> R.drawable.ic_clock
            StatusKind.OK -> null
            StatusKind.LEFT -> R.drawable.ic_zone
        }
    }
}

/** The command slip (ticket): dashed while waiting, solid once sent, green when confirmed, orange when not confirmed. */
class SlipView(ctx: Context) : androidx.appcompat.widget.AppCompatTextView(ctx) {
    private val d = SlipDrawable(ctx, ctx.col(R.color.ink2), ctx.col(R.color.bg), true)

    init {
        setTextAppearance(R.style.TextAppearance_LibreTag_LabelMedium)
        setTextColor(ctx.col(R.color.ink))
        background = d
        setPadding(dp(8), dp(4), dp(8), dp(4))
        compoundDrawablePadding = dp(6)
        gravity = Gravity.CENTER_VERTICAL
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
    }

    fun bind(s: TagText.Slip) {
        text = s.text
        val (stroke, dashed, iconRes) = when (s.state) {
            TagText.SlipState.WAITING -> Triple(R.color.ink2, true, R.drawable.ic_clock)
            TagText.SlipState.SENT -> Triple(R.color.ink2, false, R.drawable.ic_send)
            TagText.SlipState.CONFIRMED -> Triple(R.color.ok, false, R.drawable.ic_check)
            TagText.SlipState.FAILED -> Triple(R.color.warn, false, R.drawable.ic_alert)
        }
        d.strokeColor = context.col(stroke); d.dashed = dashed; d.fillColor = context.col(R.color.bg); d.invalidateSelf()
        setCompoundDrawablesRelative(context.tinted(iconRes, context.col(R.color.ink), 14), null, null, null)
    }
}
