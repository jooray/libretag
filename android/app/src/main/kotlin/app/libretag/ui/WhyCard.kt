package app.libretag.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.widget.LinearLayout
import app.libretag.R
import app.libretag.data.DiagContext
import app.libretag.data.Diagnosis
import app.libretag.data.TagStatus
import app.libretag.data.Tracker

/**
 * The "Why" card under the detail's plate (docs/design/tag-diagnosis.md 7.2): a signpost plate
 * with a 4 dp left edge (warn for a WARN cause, ink3 otherwise), one or two sentences of evidence,
 * at most two actions, and "All checks" that opens the checklist. Hidden when there is nothing to say.
 */
class WhyCard(ctx: Context, private val onAction: (Tracker, Diagnosis.Action) -> Unit) : LinearLayout(ctx) {
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG)
    private val title = ctx.text(R.style.TextAppearance_LibreTag_LabelLarge, ctx.getString(R.string.dg_why), R.color.ink2)
    private val body = ctx.text(R.style.TextAppearance_LibreTag_BodyMedium)
    private val acts = FlowRow(ctx)
    private val checks = ctx.vbox()
    private var open = false
    private var t: Tracker? = null

    init {
        orientation = VERTICAL
        setWillNotDraw(false)
        background = ctx.rounded(ctx.col(R.color.plate_bg), 4f, ctx.col(R.color.plate))
        setPadding(dp(16), dp(12), dp(12), dp(12))
        addView(title)
        addView(body, lp().margins(t = dp(4)))
        addView(acts, lp().margins(t = dp(8)))
        addView(checks, lp().margins(t = dp(4)))
        isFocusable = false
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawRect(0f, 0f, dp(4).toFloat(), height.toFloat(), edge)
    }

    /** Returns false (and hides itself) when there is no diagnosis to show, no MISSED plate and no finding. */
    fun bind(t: Tracker, s: TagStatus, d: Diagnosis.Result?, c: DiagContext, bundledFw: String?, now: Long): Boolean {
        this.t = t
        val ctx = context
        val shown = d != null && Diagnosis.shown(d, s)
        val found = Diagnosis.findings(t, c, now, bundledFw).filter { f ->
            // the headline already says it
            !(shown && (f.finding == Diagnosis.Finding.RESTARTING && d!!.cause == Diagnosis.Cause.RESTARTING ||
                f.finding == Diagnosis.Finding.COUNTER_AHEAD && d!!.cause == Diagnosis.Cause.COUNTER_AHEAD ||
                f.finding == Diagnosis.Finding.NODE_DOWN && d!!.cause == Diagnosis.Cause.NODE_DOWN))
        }
        val text = mutableListOf<String>()
        val actions = mutableListOf<Diagnosis.Action>()
        when {
            shown -> { text += DiagText.card(ctx, t, s, d!!, now); actions += d!!.actions.take(2) }
            s.missed > 0 -> text += DiagText.missedCard(ctx, s, now)
        }
        found.forEach { text += DiagText.finding(ctx, it, now) }
        if (text.isEmpty()) { visibility = GONE; return false }
        visibility = VISIBLE
        edge.color = ctx.col(if (shown && d!!.level == Diagnosis.Level.WARN) R.color.warn else R.color.ink3)
        invalidate()
        body.text = text.joinToString("\n\n")
        acts.removeAllViews()
        actions.forEachIndexed { i, a ->
            acts.addView(ctx.button(DiagText.action(ctx, a), if (i == 0) Btn.TONAL else Btn.TEXT) { onAction(t, a) }.small())
        }
        acts.addView(ctx.button(ctx.getString(if (open) R.string.dg_hide_checks else R.string.dg_all_checks), Btn.TEXT) {
            open = !open
            bind(t, s, d, c, bundledFw, System.currentTimeMillis())
        }.small())
        checks.removeAllViews()
        if (open) for (r in Diagnosis.checklist(t, c, now, bundledFw)) {
            val (k, v) = DiagText.check(ctx, t, r, now)
            val mark = when (r.state) {
                Diagnosis.CheckState.OK -> R.drawable.ic_check
                Diagnosis.CheckState.WARN -> R.drawable.ic_alert
                Diagnosis.CheckState.UNKNOWN -> null
            }
            checks.addView(ctx.hbox {
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(0, dp(4), 0, dp(4))
                addView(ctx.text(R.style.TextAppearance_LibreTag_RowKey, k).apply {
                    compoundDrawablePadding = dp(6)
                    setCompoundDrawablesRelative(mark?.let { ctx.tinted(it, ctx.col(if (r.state == Diagnosis.CheckState.WARN) R.color.warn else R.color.ink2), 14) }
                        ?: ctx.tinted(R.drawable.ic_info, ctx.col(R.color.ink3), 14), null, null, null)
                }, LinearLayout.LayoutParams(0, WRAP, 1f))
                addView(ctx.text(R.style.TextAppearance_LibreTag_RowValue, v).apply { gravity = android.view.Gravity.END },
                    LinearLayout.LayoutParams(0, WRAP, 1.2f).margins(l = dp(8)))
                contentDescription = "$k: $v"
            }, lp())
        }
        contentDescription = null
        return true
    }
}
